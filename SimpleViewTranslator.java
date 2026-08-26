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
 * Simple View: each request carries one payload blob that may contain several languages,
 * so the blob is chained sequentially through each detected source language.
 *
 * <p>Each detected language is resolved to the code Systran published before it is sent, and
 * the chain is de-duplicated <em>on the resolved code</em> — so {@code ["zh-Hans", "zh"]}
 * produces one hop rather than two identical ones. {@code notSupportedLanguages} still reports
 * the caller's original codes so they can correlate against what they sent.
 *
 * <h2>Behaviour notes</h2>
 * <ul>
 *   <li>A 406 mid-chain records that language and continues; it does not abort the blob.</li>
 *   <li>A transient failure mid-chain reverts the blob to its original text and marks it
 *       FAILED_UPSTREAM — all-or-nothing per blob. To keep partial progress instead, move the
 *       {@code setMessage} in {@link #chain} to run after every successful hop.</li>
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

        int attempted = 0;
        int failed = 0;
        TranslationException lastFailure = null;

        for (TranslateSimpleRequest request : requests) {
            Plan plan = plan(request, normalizedTarget);

            if (plan.hops().isEmpty()) {
                if (!plan.unsupported().isEmpty()) {
                    request.setNotSupportedLanguages(plan.unsupported());
                }
                apply(request, plan.terminalStatus(), plan.reason());
                continue;
            }

            attempted++;
            try {
                chain(request, plan, normalizedTarget);
            } catch (TranslationException ex) {
                failed++;
                lastFailure = ex;
                markFailed(request, ex);
            }
        }

        if (attempted > 0 && failed == attempted) {
            // Every blob we actually tried failed upstream. A 200 here would tell the caller
            // the content is untranslatable rather than that Systran is unwell.
            throw lastFailure;
        }
        if (failed > 0) {
            log.warn("Simple view partially degraded: {}/{} blob(s) failed upstream.", failed, attempted);
        }
        return requests;
    }

    // ---------------------------------------------------------------- planning

    /** One chain step: the code Systran gets, and the code the caller gave us. */
    private record Hop(String systranCode, String declaredCode) {
    }

    private record Plan(List<Hop> hops,
                        List<String> unsupported,
                        boolean usedAuto,
                        MessageTranslationStatus terminalStatus,
                        String reason) {

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

    private Plan plan(TranslateSimpleRequest request, String target) {
        if (StringUtils.isBlank(request.getMessage())
                || !hasTranslatableContent(request.getMessage())) {
            return Plan.skip(MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    "Payload contains no translatable text.");
        }

        if (CollectionUtils.isEmpty(request.getDetectedLanguages())) {
            // No hint from the caller - let Systran detect.
            return Plan.of(List.of(new Hop(LanguageCodes.AUTO, LanguageCodes.AUTO)), List.of(), true);
        }

        // Keyed by resolved code so zh-Hans and zh collapse to a single hop.
        Map<String, Hop> hops = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        boolean sawTargetLanguage = false;

        for (String raw : new LinkedHashSet<>(request.getDetectedLanguages())) {
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            String declared = LanguageCodes.normalize(raw);

            if (LanguageCodes.sameLanguage(declared, target)) {
                sawTargetLanguage = true;
                continue;
            }

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

        // Nothing left to translate. Distinguish why, so the caller knows whether the content
        // was already fine or genuinely could not be handled.
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

    // ---------------------------------------------------------------- chaining

    /**
     * Runs the blob through each hop in turn, feeding each result into the next call. The
     * request is only mutated once the whole chain has settled, which is what makes a
     * transient failure leave the original intact.
     */
    private void chain(TranslateSimpleRequest request, Plan plan, String target) {
        String text = request.getMessage();
        List<String> unsupported = new ArrayList<>(plan.unsupported());
        SystranOutcome.Translated lastTranslated = null;

        TranslationException hopFailure = null;

        for (Hop hop : plan.hops()) {
            SystranOutcome outcome;
            try {
                outcome = systranGateway.translate(text, hop.systranCode(), target);
            } catch (TranslationException ex) {
                if (lastTranslated == null) {
                    // Nothing salvaged yet - let the blob fail cleanly and keep the original.
                    throw ex;
                }
                // Earlier hops already produced usable text. Discarding it because a later
                // hop failed would throw away completed work and, for a single-blob request,
                // turn a partial success into a 502.
                log.warn("Hop '{}' -> '{}' failed after {} successful hop(s); keeping partial result.",
                        hop.systranCode(), target, plan.hops().indexOf(hop), ex);
                hopFailure = ex;
                break;
            }

            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                // Systran advertised this pair but declined it. Record and keep going -
                // the other languages in this blob may still translate.
                log.info("{} Continuing with remaining source(s).", rejected.detail());
                unsupported.add(hop.declaredCode());
                continue;
            }

            lastTranslated = (SystranOutcome.Translated) outcome;
            text = lastTranslated.text();
        }

        if (!unsupported.isEmpty()) {
            request.setNotSupportedLanguages(unsupported);
        }

        if (lastTranslated == null) {
            // Every hop was rejected: original text stands.
            apply(request, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "Systran rejected every detected source language for target '" + target + "'.");
            return;
        }

        request.setMessage(text);

        if (hopFailure != null) {
            TranslationErrorCode code = hopFailure.getErrorCode();
            apply(request, MessageTranslationStatus.PARTIALLY_TRANSLATED,
                    "Some source languages were translated; the rest failed upstream"
                            + (code == null ? "" : " (" + code.name() + ")")
                            + ". Retry to complete.");
        } else {
            apply(request, MessageTranslationStatus.TRANSLATED, null);
        }

        // Only echo Systran's own detection back when we actually fell through to auto.
        if (plan.usedAuto() && StringUtils.isNotBlank(lastTranslated.detectedSource())) {
            request.setDetectedLanguages(List.of(lastTranslated.detectedSource()));
        }
    }

    // ---------------------------------------------------------------- status helpers

    private void markFailed(TranslateSimpleRequest request, TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        apply(request, MessageTranslationStatus.FAILED_UPSTREAM,
                "Translation temporarily unavailable"
                        + (code == null ? "" : " (" + code.name() + ")") + "; original retained.");
        log.error("Simple view blob failed upstream; original retained.", ex);
    }

    private void apply(TranslateSimpleRequest request, MessageTranslationStatus status, String reason) {
        request.setTranslationStatus(status);
        request.setTranslated(status.isTranslated()); // legacy flag, kept in sync
        request.setTranslationNote(reason);
    }
}
