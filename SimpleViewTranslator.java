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
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Simple View: each request carries one payload blob that may contain several languages,
 * so the blob is chained sequentially through each detected source language.
 *
 * <h2>Behaviour changes vs. the original</h2>
 * <ul>
 *   <li><b>A 406 mid-chain no longer aborts the blob.</b> Previously a rejection on the
 *       second of three languages discarded the first hop's work and returned the untouched
 *       original. Now that language is recorded in {@code notSupportedLanguages} and the
 *       chain continues.</li>
 *   <li><b>A transient failure mid-chain reverts to the original text</b> and marks the blob
 *       FAILED_UPSTREAM, so each blob is all-or-nothing and a retry is clean. To keep partial
 *       progress instead, move the {@code setMessage} in {@link #chain} to run after every
 *       successful hop rather than once at the end.</li>
 *   <li><b>The supported-pair lookup no longer runs per blob.</b> It is served from
 *       {@link SupportedLanguagePolicy}'s cache.</li>
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

            if (plan.sources().isEmpty()) {
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

    private record Plan(List<String> sources,
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

        static Plan of(List<String> sources, List<String> unsupported, boolean usedAuto) {
            return new Plan(sources, unsupported, usedAuto, null, null);
        }
    }

    /** Decides which source languages this blob should be chained through, if any. */
    private Plan plan(TranslateSimpleRequest request, String target) {
        if (StringUtils.isBlank(request.getMessage())
                || !hasTranslatableContent(request.getMessage())) {
            return Plan.skip(MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    "Payload contains no translatable text.");
        }

        if (CollectionUtils.isEmpty(request.getDetectedLanguages())) {
            // No hint from the caller - let Systran detect.
            return Plan.of(List.of(LanguageCodes.AUTO), List.of(), true);
        }

        List<String> sources = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        boolean sawTargetLanguage = false;

        // LinkedHashSet preserves caller order while removing duplicates.
        for (String raw : new LinkedHashSet<>(request.getDetectedLanguages())) {
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            String lang = LanguageCodes.normalize(raw);

            if (LanguageCodes.sameLanguage(lang, target)) {
                sawTargetLanguage = true;
                continue;
            }
            if (supportedLanguagePolicy.supports(lang, target)) {
                sources.add(lang);
            } else {
                unsupported.add(lang);
                log.info("Skipping unsupported source '{}' -> target '{}' (original retained).",
                        lang, target);
            }
        }

        if (!sources.isEmpty()) {
            return Plan.of(sources, unsupported, false);
        }

        // Nothing left to translate. Distinguish why, so the caller knows whether the
        // content was already fine or genuinely could not be handled.
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
     * Runs the blob through each source language in turn, feeding each result into the next
     * call. The request is only mutated once the whole chain has settled, which is what makes
     * a transient failure leave the original intact.
     */
    private void chain(TranslateSimpleRequest request, Plan plan, String target) {
        String text = request.getMessage();
        List<String> unsupported = new ArrayList<>(plan.unsupported());
        SystranOutcome.Translated lastTranslated = null;

        for (String source : plan.sources()) {
            SystranOutcome outcome = systranGateway.translate(text, source, target);

            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                // Systran advertised this pair but declined it. Record and keep going -
                // the other languages in this blob may still translate.
                log.info("{} Continuing with remaining source(s).", rejected.detail());
                unsupported.add(source);
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
        apply(request, MessageTranslationStatus.TRANSLATED, null);

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
