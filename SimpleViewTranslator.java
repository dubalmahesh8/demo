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
 * Simple View: one payload blob per request. A blob holds a whole conversation and can mix
 * several languages, so it is translated once per language it contains, each pass working on
 * the text the previous pass produced.
 *
 * <p>Unlike the formatted view a blob can end up partially translated, and a partially
 * translated blob does not count as a failure - which is why a single blob whose second pass
 * fails returns 200, not 502.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleViewTranslator {

    private final SupportedLanguagePolicy supportedLanguagePolicy;
    private final SystranGateway systranGateway;

    public List<TranslateSimpleRequest> translate(List<TranslateSimpleRequest> requests, String target) {
        String normalizedTarget = LanguageCodes.normalize(target);

        RequestTally tally = new RequestTally();
        requests.forEach(request -> translateBlob(request, normalizedTarget, tally));

        tally.failIfNothingSucceeded();
        tally.logSummary(normalizedTarget, requests.size());
        return requests;
    }

    private void translateBlob(TranslateSimpleRequest request, String target, RequestTally tally) {
        TranslationPlan plan = planLanguages(request, target);

        // Decided without calling Systran, so it cannot fail the request.
        if (plan.nothingToTranslate()) {
            if (!plan.unsupportedLanguages().isEmpty()) {
                request.setNotSupportedLanguages(plan.unsupportedLanguages());
            }
            recordStatus(request, plan.skipStatus(), plan.skipReason());
            return;
        }

        try {
            tally.recordAttempt(translateEachLanguage(request, plan, target));
        } catch (TranslationException ex) {
            // Only reachable when the first language failed - nothing was salvaged.
            markFailed(request, ex);
            tally.recordOutrightFailure(ex);
        }
    }

    // ---------------------------------------------------------------- deciding what to translate

    /** A language to translate from: the code Systran accepts, and the code the caller sent. */
    private record SourceLanguage(String systranCode, String callerCode) {
    }

    /**
     * Which languages this blob needs translating from, or why none of them do.
     * {@code skipStatus} is non-null exactly when {@code sourceLanguages} is empty.
     */
    private record TranslationPlan(List<SourceLanguage> sourceLanguages,
                                   List<String> unsupportedLanguages,
                                   boolean letSystranDetect,
                                   MessageTranslationStatus skipStatus,
                                   String skipReason) {

        boolean nothingToTranslate() {
            return sourceLanguages.isEmpty();
        }

        static TranslationPlan skip(MessageTranslationStatus status, String reason) {
            return new TranslationPlan(List.of(), List.of(), false, status, reason);
        }

        static TranslationPlan skip(MessageTranslationStatus status, String reason,
                                    List<String> unsupported) {
            return new TranslationPlan(List.of(), unsupported, false, status, reason);
        }

        static TranslationPlan translateFrom(List<SourceLanguage> languages,
                                             List<String> unsupported, boolean letSystranDetect) {
            return new TranslationPlan(languages, unsupported, letSystranDetect, null, null);
        }
    }

    private TranslationPlan planLanguages(TranslateSimpleRequest request, String target) {
        if (StringUtils.isBlank(request.getMessage())
                || !hasTranslatableContent(request.getMessage())) {
            return TranslationPlan.skip(MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    "Payload contains no translatable text.");
        }

        // No languages from the caller: ask Systran to detect, and echo its answer back afterwards.
        if (CollectionUtils.isEmpty(request.getDetectedLanguages())) {
            return TranslationPlan.translateFrom(
                    List.of(new SourceLanguage(LanguageCodes.AUTO, LanguageCodes.AUTO)), List.of(), true);
        }

        // Keyed by the Systran code so zh-Hans and zh collapse into one pass instead of two.
        Map<String, SourceLanguage> toTranslate = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        boolean sawTargetLanguage = false;

        for (String rawCode : new LinkedHashSet<>(request.getDetectedLanguages())) {
            if (StringUtils.isBlank(rawCode)) {
                continue;
            }
            String callerCode = LanguageCodes.normalize(rawCode);

            if (LanguageCodes.sameLanguage(callerCode, target)) {
                sawTargetLanguage = true;
                continue;
            }

            Optional<String> systranCode = supportedLanguagePolicy.resolveSource(callerCode, target);
            if (systranCode.isEmpty()) {
                unsupported.add(callerCode);
                log.info("Skipping unsupported source '{}' -> target '{}' (original retained).",
                        callerCode, target);
                continue;
            }
            toTranslate.putIfAbsent(systranCode.get(), new SourceLanguage(systranCode.get(), callerCode));
        }

        if (!toTranslate.isEmpty()) {
            return TranslationPlan.translateFrom(List.copyOf(toTranslate.values()), unsupported, false);
        }

        // Nothing left. Distinguish "already fine" from "we cannot handle these languages".
        if (!unsupported.isEmpty()) {
            return TranslationPlan.skip(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "No detected source language is supported for target '" + target + "'.", unsupported);
        }
        return TranslationPlan.skip(
                sawTargetLanguage ? MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE
                        : MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                sawTargetLanguage ? "Payload is already in the target language."
                        : "No usable source language was detected.");
    }

    private boolean hasTranslatableContent(String html) {
        return Jsoup.parse(html).text().codePoints().anyMatch(Character::isLetter);
    }

    // ---------------------------------------------------------------- doing the translation

    /** How far a blob got. All three are non-failures as far as the HTTP status is concerned. */
    private enum BlobResult {
        FULLY_TRANSLATED, PARTIALLY_TRANSLATED, NONE_ACCEPTED
    }

    /**
     * Translates the blob once per source language, each pass working on the text the previous
     * pass produced. The request is only updated once every pass has finished, so a failure on
     * the first pass leaves the caller's original text untouched.
     *
     * @throws TranslationException only if the FIRST pass fails; translateBlob's catch relies on this
     */
    private BlobResult translateEachLanguage(TranslateSimpleRequest request, TranslationPlan plan,
                                             String target) {
        String text = request.getMessage();
        List<String> unsupported = new ArrayList<>(plan.unsupportedLanguages());
        SystranOutcome.Translated lastSuccess = null;
        TranslationException failureAfterSuccess = null;

        for (SourceLanguage source : plan.sourceLanguages()) {
            SystranOutcome outcome;
            try {
                outcome = systranGateway.translate(text, source.systranCode(), target);
            } catch (TranslationException ex) {
                if (lastSuccess == null) {
                    throw ex;
                }
                // An earlier language already translated part of the blob; keeping that beats
                // throwing away finished work.
                log.warn("Translating from '{}' to '{}' failed after an earlier language succeeded; "
                        + "keeping the partial result.", source.systranCode(), target, ex);
                failureAfterSuccess = ex;
                break;
            }

            // A 406 rules out this language only - record it and move to the next one.
            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Continuing with the remaining language(s).", rejected.detail());
                unsupported.add(source.callerCode());
                continue;
            }

            lastSuccess = (SystranOutcome.Translated) outcome;
            text = lastSuccess.text();
        }

        if (!unsupported.isEmpty()) {
            request.setNotSupportedLanguages(unsupported);
        }

        if (lastSuccess == null) {
            recordStatus(request, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "Systran rejected every detected source language for target '" + target + "'.");
            return BlobResult.NONE_ACCEPTED;
        }

        request.setMessage(text);

        if (failureAfterSuccess != null) {
            TranslationErrorCode code = failureAfterSuccess.getErrorCode();
            recordStatus(request, MessageTranslationStatus.PARTIALLY_TRANSLATED,
                    "Some source languages were translated; the rest failed upstream"
                            + (code == null ? "" : " (" + code.name() + ")") + ". Retry to complete.");
            return BlobResult.PARTIALLY_TRANSLATED;
        }

        recordStatus(request, MessageTranslationStatus.TRANSLATED, null);

        // Echo Systran's detection back only when we actually asked it to detect.
        if (plan.letSystranDetect() && StringUtils.isNotBlank(lastSuccess.detectedSource())) {
            request.setDetectedLanguages(List.of(lastSuccess.detectedSource()));
        }
        return BlobResult.FULLY_TRANSLATED;
    }

    // ---------------------------------------------------------------- deciding the HTTP status

    /** Running count across the blobs in one request, and the rule for turning it into a status. */
    private static final class RequestTally {

        private int attempted;
        private int failedOutright;
        private int partial;
        private TranslationException lastFailure;

        void recordAttempt(BlobResult result) {
            attempted++;
            if (result == BlobResult.PARTIALLY_TRANSLATED) {
                partial++;
            }
        }

        void recordOutrightFailure(TranslationException ex) {
            attempted++;
            failedOutright++;
            lastFailure = ex;
        }

        /** 200 would claim the content is untranslatable when really Systran is unwell. */
        void failIfNothingSucceeded() {
            // attempted > 0: a request where every blob was skipped tried nothing, so nothing failed.
            if (attempted > 0 && failedOutright == attempted) {
                throw lastFailure;
            }
        }

        void logSummary(String target, int blobCount) {
            if (failedOutright > 0 || partial > 0) {
                log.warn("Simple view degraded - target: {}, blobs: {}, failed: {}, partial: {}.",
                        target, blobCount, failedOutright, partial);
            } else {
                log.info("Simple view complete - target: {}, blobs: {}.", target, blobCount);
            }
        }
    }

    // ---------------------------------------------------------------- status helpers

    private void markFailed(TranslateSimpleRequest request, TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        recordStatus(request, MessageTranslationStatus.FAILED_UPSTREAM,
                "Translation temporarily unavailable"
                        + (code == null ? "" : " (" + code.name() + ")") + "; original retained.");
        log.error("Blob failed on its first source language; original retained.", ex);
    }

    /** Single write point for blob status; every path out of this class goes through here. */
    private void recordStatus(TranslateSimpleRequest request, MessageTranslationStatus status,
                              String reason) {
        request.setTranslationStatus(status);
        request.setTranslationNote(reason);
    }
}
