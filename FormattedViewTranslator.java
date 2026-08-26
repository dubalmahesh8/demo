package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateMessageRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.LanguageCodes;
import com.citi.uno.items.translation.service.support.SegmentCodec;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Formatted View: groups messages by source language and translates each group in one
 * batched Systran call.
 *
 * <p>Groups are keyed by the <em>resolved</em> Systran code, not the caller's code. So
 * {@code zh-Hans} and {@code zh} land in the same group and produce one call, and the string
 * sent to Systran is always one it published. The message keeps its original
 * {@code detectedLanguage} — we translate on the caller's behalf, we don't correct their data.
 *
 * <p>Systran accepts ~50MB per call, so a group is sent as a single call regardless of size.
 * The one case that still splits a group is a message body containing the segment delimiter,
 * which would desynchronise the response split.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormattedViewTranslator {

    private static final String INFO = "INFO";

    private final SupportedLanguagePolicy supportedLanguagePolicy;
    private final SegmentCodec segmentCodec;
    private final SystranGateway systranGateway;

    public List<TranslateMessageRequest> translate(List<TranslateMessageRequest> messages, String target) {
        String normalizedTarget = LanguageCodes.normalize(target);

        Map<String, List<TranslateMessageRequest>> groups = classifyAndGroup(messages, normalizedTarget);

        int attempted = 0;
        int failed = 0;
        TranslationException lastFailure = null;

        for (Map.Entry<String, List<TranslateMessageRequest>> entry : groups.entrySet()) {
            String systranSource = entry.getKey();
            List<TranslateMessageRequest> group = entry.getValue();

            attempted++;
            try {
                translateGroup(group, systranSource, normalizedTarget);
            } catch (TranslationException ex) {
                failed++;
                lastFailure = ex;
                markFailed(group, systranSource, normalizedTarget, ex);
            }
        }

        if (attempted > 0 && failed == attempted) {
            // Nothing at all got translated and the cause was upstream, not the content.
            // Returning 200 here would tell the caller "this text is untranslatable".
            throw lastFailure;
        }

        if (failed > 0) {
            log.warn("Formatted view partially degraded: {}/{} language group(s) failed upstream.",
                    failed, attempted);
        }
        return messages;
    }

    // ---------------------------------------------------------------- phase 1: classify

    /**
     * Assigns a terminal status to every message that will not be sent to Systran, and returns
     * the remainder grouped by the resolved Systran source code. The unsupported-pair pre-check
     * lives here because resolution and support are now the same question.
     */
    private Map<String, List<TranslateMessageRequest>> classifyAndGroup(
            List<TranslateMessageRequest> messages, String target) {

        Map<String, List<TranslateMessageRequest>> groups = new LinkedHashMap<>();

        for (TranslateMessageRequest message : messages) {
            if (INFO.equalsIgnoreCase(message.getSubType())) {
                apply(message, MessageTranslationStatus.SKIPPED_INFO_MESSAGE, null);
                continue;
            }
            if (!hasTranslatableContent(message.getHtml())) {
                apply(message, MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                        "Message body contains no translatable text.");
                continue;
            }

            String declared = LanguageCodes.normalize(message.getDetectedLanguage());

            if (!LanguageCodes.isAuto(declared) && LanguageCodes.sameLanguage(declared, target)) {
                apply(message, MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE,
                        "Message is already in the target language.");
                continue;
            }

            // Pre-check and code resolution in one step: skip the Systran call entirely for
            // unsupported sources, and for supported ones learn the exact code to send.
            Optional<String> systranSource = supportedLanguagePolicy.resolveSource(declared, target);
            if (systranSource.isEmpty()) {
                apply(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                        String.format("Source language '%s' is not supported for target '%s'.",
                                declared, target));
                continue;
            }

            groups.computeIfAbsent(systranSource.get(), k -> new ArrayList<>()).add(message);
        }
        return groups;
    }

    private boolean hasTranslatableContent(String html) {
        if (StringUtils.isBlank(html)) {
            return false;
        }
        String plain = Jsoup.parse(html).text(); // drops tags
        return plain.codePoints().anyMatch(Character::isLetter);
    }

    // ---------------------------------------------------------------- phase 2: translate

    private void translateGroup(List<TranslateMessageRequest> group, String systranSource, String target) {
        for (List<TranslateMessageRequest> batch : splitDelimiterUnsafe(group)) {
            List<String> bodies = batch.stream()
                    .map(message -> StringUtils.defaultString(message.getHtml()))
                    .toList();

            SystranOutcome outcome =
                    systranGateway.translate(segmentCodec.join(bodies), systranSource, target);

            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Original retained for {} message(s).", rejected.detail(), batch.size());
                markUnsupported(batch, systranSource, target);
                continue;
            }

            SystranOutcome.Translated translated = (SystranOutcome.Translated) outcome;
            // Throws TRANSLATION_SEGMENT_MISMATCH rather than mis-aligning translations.
            List<String> segments = segmentCodec.split(translated.text(), batch.size());

            for (int i = 0; i < batch.size(); i++) {
                TranslateMessageRequest message = batch.get(i);
                message.setHtml(segments.get(i));
                apply(message, MessageTranslationStatus.TRANSLATED, null);
            }
        }
    }

    /**
     * Isolates any message whose body already contains the segment delimiter. Everything else
     * goes in a single batch — Systran's input ceiling is high enough that size never forces
     * a split.
     */
    private List<List<TranslateMessageRequest>> splitDelimiterUnsafe(List<TranslateMessageRequest> group) {
        List<List<TranslateMessageRequest>> batches = new ArrayList<>();
        List<TranslateMessageRequest> batchable = new ArrayList<>();

        for (TranslateMessageRequest message : group) {
            if (segmentCodec.isSafeToBatch(message.getHtml())) {
                batchable.add(message);
            } else {
                log.warn("Message body contains the segment delimiter; sending it as its own call.");
                batches.add(List.of(message));
            }
        }
        if (!batchable.isEmpty()) {
            batches.add(0, batchable);
        }
        return batches;
    }

    // ---------------------------------------------------------------- status helpers

    private void markUnsupported(List<TranslateMessageRequest> group, String systranSource, String target) {
        group.forEach(message -> apply(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                String.format("Source language '%s' is not supported for target '%s'.",
                        // Report the caller's own code, not the resolved one - they can correlate it.
                        StringUtils.defaultIfBlank(message.getDetectedLanguage(), systranSource), target)));
        log.info("Skipping {} message(s) - Systran rejected source '{}' -> target '{}'.",
                group.size(), systranSource, target);
    }

    private void markFailed(List<TranslateMessageRequest> group, String systranSource, String target,
                            TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        String reason = "Translation temporarily unavailable"
                + (code == null ? "" : " (" + code.name() + ")") + "; original retained.";
        group.forEach(message -> apply(message, MessageTranslationStatus.FAILED_UPSTREAM, reason));
        log.error("Group '{}' -> '{}' failed for {} message(s).",
                systranSource, target, group.size(), ex);
    }

    private void apply(TranslateMessageRequest message, MessageTranslationStatus status, String reason) {
        message.setTranslationStatus(status);
        message.setTranslated(status.isTranslated()); // legacy flag, kept in sync
        message.setTranslationSkippedReason(reason);
    }
}
