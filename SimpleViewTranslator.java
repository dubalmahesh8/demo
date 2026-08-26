package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateSimpleRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.LanguageCodes;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simple View: each request carries one payload blob that may contain several languages, so
 * the blob is chained sequentially through each detected source language.
 *
 * <h2>Flow</h2>
 * <pre>
 *   translate(requests, target)
 *     |
 *     +-- for each blob: translateBlob
 *     |     |
 *     |     +-- STEP 1  plan     which languages to chain through, in which order,
 *     |     |                    using the codes Systran actually accepts
 *     |     |
 *     |     +-- STEP 2  chain    one call per hop, each feeding the next
 *     |
 *     +-- STEP 3  failIfNothingSucceeded   502/504 only when every blob failed outright
 * </pre>
 *
 * <h2>How this differs from the formatted view</h2>
 * <ul>
 *   <li><b>Unit of work is the blob, not a language group.</b> One blob can make several calls.</li>
 *   <li><b>A blob can end up partially translated.</b> If hop 3 of 5 fails, hops 1-2 already
 *       rewrote the text. Discarding that would throw away completed work, so the blob keeps it
 *       and reports {@code PARTIALLY_TRANSLATED} — translated <em>and</em> retryable.</li>
 *   <li><b>A partial blob does not count as failed</b> for the batch verdict. Only a blob that
 *       salvaged nothing does. This is why a single blob whose second hop fails returns 200
 *       rather than 502.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleViewTranslator {

    private final SupportedLanguagePolicy supportedLanguagePolicy;
    private final SystranGateway systranGateway;

    public List<TranslateSimpleRequest> translate(List<TranslateSimpleRequest> requests, String target) {
        String normalizedTarget = LanguageCodes.normalize(target);

        BatchOutcome outcome = new BatchOutcome();
        for (TranslateSimpleRequest request : requests) {
            translateBlob(request, normalizedTarget, outcome);
        }

        // STEP 3 - Decide whether the request as a whole succeeded.
        outcome.failIfNothingSucceeded();
        outcome.logSummary(normalizedTarget, requests.size());

        return requests;
    }

    /** One blob, start to finish. Never throws unless the blob salvaged nothing. */
    private void translateBlob(TranslateSimpleRequest request, String target, BatchOutcome outcome) {

        // STEP 1 - Work out what, if anything, to send.
        Plan plan = plan(request, target);

        if (plan.isTerminal()) {
            // Decided without calling Systran. Not attempted, so it cannot fail the batch.
            if (!plan.unsupported().isEmpty()) {
                request.setNotSupportedLanguages(plan.unsupported());
            }
            apply(request, plan.terminalStatus(), plan.reason());
            return;
        }

        // STEP 2 - Run the chain.
        try {
            ChainVerdict verdict = chain(request, plan, target);
            outcome.recordAttempt(verdict);
        } catch (TranslationException ex) {
            // Only reaches here when the very first hop failed - nothing was salvaged.
            markFailed(request, ex);
            outcome.recordTotalFailure(ex);
        }
    }

    // ============================================================ STEP 1: plan the chain

    /** One chain step: the code Systran gets, and the code the caller gave us. */
    private record Hop(String systranCode, String declaredCode) {
    }

    /**
     * Either a list of hops to run, or a terminal status explaining why there are none.
     * {@code terminalStatus} is non-null exactly when {@code hops} is empty.
     */
    private record Plan(List<Hop> hops,
                        List<String> unsupported,
                        boolean usedAuto,
                        MessageTranslationStatus terminalStatus,
                        String reason) {

        boolean isTerminal() {
            return hops.isEmpty();
        }

        static Plan skip(MessageTranslationStatus status, String reason) {
            return new Plan(List.of(), List.of(), false, status, reason);
        }

        static Plan skip(MessageTranslationStatus status, String reason, List<String> unsupported) {
            return new Plan(List.of(), unsupported, false, status, reason);
        }

        static Plan of(List<Hop> hops, List<String> unsupported, boolean usedAuto) {
            return new Plan(hops, unsupported, usedAuto, null, null);
        }
    }

    /**
     * Decides which source languages this blob should be chained through.
     *
     * <p>Checks run cheapest-first:
     * <ol>
     *   <li>no translatable content - nothing to send</li>
     *   <li>no languages supplied - fall through to Systran's own detection</li>
     *   <li>otherwise resolve each supplied language, dropping target-language and
     *       unsupported entries</li>
     * </ol>
     */
    private Plan plan(TranslateSimpleRequest request, String target) {

        // 1.1 - Nothing to translate: blank, or markup and punctuation only.
        if (StringUtils.isBlank(request.getMessage())
                || !hasTranslatableContent(request.getMessage())) {
            return Plan.skip(MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    "Payload contains no translatable text.");
        }

        // 1.2 - No hint from the caller: a single auto hop, and we echo the detection back later.
        if (CollectionUtils.isEmpty(request.getDetectedLanguages())) {
            return Plan.of(List.of(new Hop(LanguageCodes.AUTO, LanguageCodes.AUTO)), List.of(), true);
        }

        // 1.3 - Resolve each supplied language. Keyed by resolved code so zh-Hans and zh
        // collapse to one hop instead of translating the same content twice.
        Map<String, Hop> hops = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        boolean sawTargetLanguage = false;

        for (String raw : new LinkedHashSet<>(request.getDetectedLanguages())) {
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            String declared = LanguageCodes.normalize(raw);

            // 1.3a - Already the target language: no hop needed.
            if (LanguageCodes.sameLanguage(declared, target)) {
                sawTargetLanguage = true;
                continue;
            }

            // 1.3b - Unsupported pair: record it, skip the call.
            Optional<String> resolved = supportedLanguagePolicy.resolveSource(declared, target);
            if (resolved.isEmpty()) {
                unsupported.add(declared);
                log.info("Skipping unsupported source '{}' -> target '{}' (original retained).",
                        declared, target);
                continue;
            }

            hops.putIfAbsent(resolved.get(), new Hop(resolved.get(), declared));
        }

        if (!hops.isEmpty()) {
            return Plan.of(List.copyOf(hops.values()), unsupported, false);
        }

        // 1.4 - No hops left. Distinguish why, so the caller knows whether the content was
        // already fine or genuinely could not be handled.
        if (!unsupported.isEmpty()) {
            return Plan.skip(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "No detected source language is supported for target '" + target + "'.",
                    unsupported);
        }
        return Plan.skip(
                sawTargetLanguage
                        ? MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE
                        : MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                sawTargetLanguage
                        ? "Payload is already in the target language."
                        : "No usable source language was detected.");
    }

    private boolean hasTranslatableContent(String html) {
        String plain = Jsoup.parse(html).text(); // drops tags
        return plain.codePoints().anyMatch(Character::isLetter);
    }

    // ============================================================ STEP 2: run the chain

    /** How a blob's chain ended. All three are non-failures for the batch verdict. */
    private enum ChainVerdict {
        /** Every hop that ran produced a translation. */
        COMPLETE,
        /** Some hops succeeded, then one failed upstream. Partial text kept. */
        PARTIAL,
        /** Systran declined every hop with a 406. Original text kept. */
        ALL_REJECTED
    }

    /**
     * Runs the blob through each hop in turn, feeding each result into the next call.
     *
     * <p>The request is only mutated once the chain has settled, so a first-hop failure leaves
     * the caller's original text untouched.
     *
     * @throws TranslationException only if the <em>first</em> hop fails; once any hop has
     *         succeeded, a later failure downgrades to {@link ChainVerdict#PARTIAL}
     */
    private ChainVerdict chain(TranslateSimpleRequest request, Plan plan, String target) {
        String text = request.getMessage();
        List<String> unsupported = new ArrayList<>(plan.unsupported());
        SystranOutcome.Translated lastTranslated = null;
        TranslationException hopFailure = null;

        for (Hop hop : plan.hops()) {

            // 2.1 - One call per hop, on the text produced by the previous hop.
            SystranOutcome outcome;
            try {
                outcome = systranGateway.translate(text, hop.systranCode(), target);
            } catch (TranslationException ex) {
                if (lastTranslated == null) {
                    // 2.2 - Nothing salvaged yet. Let the blob fail and keep the original.
                    throw ex;
                }
                // 2.3 - Earlier hops already produced usable text. Keeping it beats discarding
                // completed work and turning a partial success into a 502.
                log.warn("Hop '{}' -> '{}' failed after an earlier hop succeeded; keeping partial result.",
                        hop.systranCode(), target, ex);
                hopFailure = ex;
                break;
            }

            // 2.4 - Systran declined this pair. Permanent, so record it and try the next language.
            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Continuing with remaining source(s).", rejected.detail());
                unsupported.add(hop.declaredCode());
                continue;
            }

            lastTranslated = (SystranOutcome.Translated) outcome;
            text = lastTranslated.text();
        }

        // 2.5 - Settle the blob. One write point, one verdict.
        if (!unsupported.isEmpty()) {
            request.setNotSupportedLanguages(unsupported);
        }

        if (lastTranslated == null) {
            apply(request, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "Systran rejected every detected source language for target '" + target + "'.");
            return ChainVerdict.ALL_REJECTED;
        }

        request.setMessage(text);

        if (hopFailure != null) {
            TranslationErrorCode code = hopFailure.getErrorCode();
            apply(request, MessageTranslationStatus.PARTIALLY_TRANSLATED,
                    "Some source languages were translated; the rest failed upstream"
                            + (code == null ? "" : " (" + code.name() + ")")
                            + ". Retry to complete.");
            return ChainVerdict.PARTIAL;
        }

        apply(request, MessageTranslationStatus.TRANSLATED, null);

        // 2.6 - Echo Systran's own detection back only when we actually fell through to auto.
        if (plan.usedAuto() && StringUtils.isNotBlank(lastTranslated.detectedSource())) {
            request.setDetectedLanguages(List.of(lastTranslated.detectedSource()));
        }
        return ChainVerdict.COMPLETE;
    }

    // ============================================================ STEP 3: batch verdict

    /**
     * Tally across blobs, and the rule for turning it into an HTTP outcome.
     *
     * <p>The rule differs from the formatted view in one place: a {@link ChainVerdict#PARTIAL}
     * blob counts as an attempt that produced value, so it does <em>not</em> push the batch
     * toward a 502. Only a blob that salvaged nothing counts as failed.
     */
    private static final class BatchOutcome {

        private int attempted;
        private int failed;
        private int partial;
        private TranslationException lastFailure;

        void recordAttempt(ChainVerdict verdict) {
            attempted++;
            if (verdict == ChainVerdict.PARTIAL) {
                partial++;
            }
        }

        void recordTotalFailure(TranslationException ex) {
            attempted++;
            failed++;
            lastFailure = ex;
        }

        /**
         * Propagates only when every blob we tried failed outright. Returning 200 in that case
         * would tell the caller the content is untranslatable, when in fact Systran is unwell.
         *
         * <p>Note {@code attempted > 0}: a request where every blob was skipped attempted
         * nothing, so nothing failed, and 200 is the honest answer.
         */
        void failIfNothingSucceeded() {
            if (attempted > 0 && failed == attempted) {
                throw lastFailure;
            }
        }

        void logSummary(String target, int blobCount) {
            if (failed > 0 || partial > 0) {
                log.warn("Simple view degraded - target: {}, blobs: {}, failed: {}, partial: {}.",
                        target, blobCount, failed, partial);
            } else {
                log.info("Simple view complete - target: {}, blobs: {}.", target, blobCount);
            }
        }
    }

    // ============================================================ status helpers

    private void markFailed(TranslateSimpleRequest request, TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        apply(request, MessageTranslationStatus.FAILED_UPSTREAM,
                "Translation temporarily unavailable"
                        + (code == null ? "" : " (" + code.name() + ")") + "; original retained.");
        log.error("Simple view blob failed on its first hop; original retained.", ex);
    }

    /** Single write point for blob status. Every exit path goes through here. */
    private void apply(TranslateSimpleRequest request, MessageTranslationStatus status, String reason) {
        request.setTranslationStatus(status);
        request.setTranslationNote(reason);
    }
}
