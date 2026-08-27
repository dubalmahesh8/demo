package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateSimpleRequest;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.LanguageCodes;
import com.citi.uno.items.translation.service.support.RequestSummary;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import com.citi.uno.items.translation.service.support.TranslationOutcomes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
 * <p>Three steps per blob: {@link #selectSourceLanguages}, {@link #runTranslations},
 * {@link #applyResult}. Then the request-level verdict in {@link RequestSummary}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleViewTranslator {

    private final SupportedLanguagePolicy supportedLanguagePolicy;
    private final SystranGateway systranGateway;

    public List<TranslateSimpleRequest> translate(List<TranslateSimpleRequest> requests, String target) {
        String normalizedTarget = LanguageCodes.normalize(target);
        RequestSummary summary = new RequestSummary("Simple view", "blob");

        log.info("Simple view start - target: {}, blobs: {}", normalizedTarget, requests.size());
        requests.forEach(request -> translateBlob(request, normalizedTarget, summary));

        summary.failIfNothingSucceeded();
        summary.log(normalizedTarget, requests.size());
        return requests;
    }

    private void translateBlob(TranslateSimpleRequest request, String target, RequestSummary summary) {
        List<SourceLanguage> sources = selectSourceLanguages(request, target);

        // Nothing to send. selectSourceLanguages already set the status, and since we never
        // called Systran this blob cannot drag the request towards a 502.
        if (sources.isEmpty()) {
            summary.countItem(request.getTranslationStatus());
            return;
        }

        log.info("Translating blob through {} language(s) -> {}: {}", sources.size(), target,
                sources.stream().map(SourceLanguage::systranCode).toList());

        try {
            TranslationRun run = runTranslations(request, sources, target);
            applyResult(request, run, target);
            summary.recordUnitSucceeded(request.getTranslationStatus());
            summary.countItem(request.getTranslationStatus());
        } catch (TranslationException ex) {
            // Thrown only when the very first language failed, so nothing was salvaged.
            TranslationOutcomes.markFailedUpstream(request, ex);
            summary.recordUnitFailed(ex);
            summary.countItem(MessageTranslationStatus.FAILED_UPSTREAM);
            log.error("Blob failed on its first source language; original retained.", ex);
        }
    }

    // ---------------------------------------------------------------- step 1: pick the languages

    /** A language to translate from: the code Systran accepts, and the code the caller sent. */
    private record SourceLanguage(String systranCode, String callerCode) {
    }

    /**
     * Returns the languages to translate this blob from. An empty list means there is nothing
     * to do, and the blob's final status has already been written.
     */
    private List<SourceLanguage> selectSourceLanguages(TranslateSimpleRequest request, String target) {
        if (!TranslationOutcomes.hasTranslatableContent(request.getMessage())) {
            TranslationOutcomes.record(request, MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    "Payload contains no translatable text.");
            return List.of();
        }

        // No languages from the caller: ask Systran to detect, and echo its answer back later.
        if (CollectionUtils.isEmpty(request.getDetectedLanguages())) {
            log.debug("No detectedLanguages supplied; falling back to Systran auto-detection.");
            return List.of(new SourceLanguage(LanguageCodes.AUTO, LanguageCodes.AUTO));
        }

        // Keyed by the Systran code so zh-Hans and zh collapse into one pass instead of two.
        Map<String, SourceLanguage> selected = new LinkedHashMap<>();
        boolean sawTargetLanguage = false;

        for (String rawCode : new LinkedHashSet<>(request.getDetectedLanguages())) {
            if (StringUtils.isBlank(rawCode)) {
                continue;
            }
            String callerCode = LanguageCodes.normalize(rawCode);

            if (LanguageCodes.sameLanguage(callerCode, target)) {
                sawTargetLanguage = true;
                log.debug("Dropping '{}': already the target language.", callerCode);
                continue;
            }

            Optional<String> systranCode = supportedLanguagePolicy.resolveSource(callerCode, target);
            if (systranCode.isPresent()) {
                selected.putIfAbsent(systranCode.get(),
                        new SourceLanguage(systranCode.get(), callerCode));
            } else {
                addUnsupported(request, callerCode);
                log.debug("Dropping '{}': unsupported for target '{}' (original retained).",
                        callerCode, target);
            }
        }

        if (!selected.isEmpty()) {
            return List.copyOf(selected.values());
        }

        // Nothing usable left. Say which of the two reasons it was.
        if (CollectionUtils.isEmpty(request.getNotSupportedLanguages())) {
            TranslationOutcomes.record(request,
                    sawTargetLanguage ? MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE
                            : MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                    sawTargetLanguage ? "Payload is already in the target language."
                            : "No usable source language was detected.");
        } else {
            TranslationOutcomes.record(request, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "No detected source language is supported for target '" + target + "'.");
        }
        return List.of();
    }

    // ---------------------------------------------------------------- step 2: call Systran

    /** What the passes produced: the text so far, whether any succeeded, and what stopped them. */
    private record TranslationRun(String text, boolean anythingTranslated,
                                  TranslationException stoppedBy) {
    }

    /**
     * Translates the blob once per source language, each pass working on the previous pass's
     * text. Does not touch the request's message, so a caller that gives up still has the original.
     *
     * @throws TranslationException if the FIRST pass fails; after that a failure just stops the
     *         loop and is reported through {@link TranslationRun#stoppedBy}
     */
    private TranslationRun runTranslations(TranslateSimpleRequest request,
                                           List<SourceLanguage> sources, String target) {
        String text = request.getMessage();
        boolean anythingTranslated = false;

        for (SourceLanguage source : sources) {
            SystranOutcome outcome;
            try {
                outcome = systranGateway.translate(text, source.systranCode(), target);
            } catch (TranslationException ex) {
                if (!anythingTranslated) {
                    throw ex;
                }
                // An earlier language already translated part of the blob; keeping that beats
                // throwing away finished work.
                log.warn("Translating from '{}' to '{}' failed after an earlier language succeeded.",
                        source.systranCode(), target, ex);
                return new TranslationRun(text, true, ex);
            }

            // A 406 rules out this language only - record it and move to the next one.
            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Continuing with the remaining language(s).", rejected.detail());
                addUnsupported(request, source.callerCode());
                continue;
            }

            text = ((SystranOutcome.Translated) outcome).text();
            anythingTranslated = true;
            log.debug("Translated blob from '{}' to '{}'.", source.systranCode(), target);

            // Echo Systran's detection back only when we actually asked it to detect.
            if (LanguageCodes.isAuto(source.systranCode())) {
                echoDetectedLanguage(request, (SystranOutcome.Translated) outcome);
            }
        }
        return new TranslationRun(text, anythingTranslated, null);
    }

    // ---------------------------------------------------------------- step 3: record the outcome

    private void applyResult(TranslateSimpleRequest request, TranslationRun run, String target) {
        if (!run.anythingTranslated()) {
            TranslationOutcomes.record(request, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                    "Systran rejected every detected source language for target '" + target + "'.");
            return;
        }

        request.setMessage(run.text());

        if (run.stoppedBy() == null) {
            TranslationOutcomes.markTranslated(request);
        } else {
            TranslationOutcomes.markPartiallyTranslated(request, run.stoppedBy());
        }
    }

    private void addUnsupported(TranslateSimpleRequest request, String callerCode) {
        List<String> unsupported = request.getNotSupportedLanguages() == null
                ? new ArrayList<>() : new ArrayList<>(request.getNotSupportedLanguages());
        unsupported.add(callerCode);
        request.setNotSupportedLanguages(unsupported);
    }

    private void echoDetectedLanguage(TranslateSimpleRequest request, SystranOutcome.Translated result) {
        if (StringUtils.isNotBlank(result.detectedSource())) {
            request.setDetectedLanguages(List.of(result.detectedSource()));
        }
    }
}
