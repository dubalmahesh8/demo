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

/**
 * Formatted View: groups messages by source language and translates each group in one
 * batched Systran call.
 *
 * <p>A straight three-phase pipeline - classify, group, translate. The original interleaved
 * all three and addressed messages by {@code List<Integer>} index; grouping the objects
 * themselves removes every {@code messages.get(i)} lookup.
 *
 * <p>Systran accepts ~50MB per call, so a language group is sent as a single call regardless
 * of size. The one case that still splits a group is a message body that already contains
 * the segment delimiter - batching that would desynchronise the response split, so it is
 * sent on its own.
 *
 * <h2>Outcome contract</h2>
 * <ul>
 *   <li>Permanent, message-level outcomes (INFO, no content, already target, unsupported
 *       pair, 406) mark the message and return 200.</li>
 *   <li>Transient failures (5xx, timeout, segment mismatch) mark the group FAILED_UPSTREAM
 *       and return 200 <em>as long as some other group succeeded</em>.</li>
 *   <li>If every group that was actually attempted failed, the exception propagates so the
 *       caller gets 502/504 rather than a 200 that silently contains nothing but originals.</li>
 * </ul>
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
            String sourceLang = entry.getKey();
            List<TranslateMessageRequest> group = entry.getValue();

            if (!supportedLanguagePolicy.supports(sourceLang, normalizedTarget)) {
                // Pre-check: skip the Systran call entirely for unsupported source languages.
                markUnsupported(group, sourceLang, normalizedTarget);
                continue;
            }

            attempted++;
            try {
                translateGroup(group, sourceLang, normalizedTarget);
            } catch (TranslationException ex) {
                failed++;
                lastFailure = ex;
                markFailed(group, sourceLang, normalizedTarget, ex);
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
     * Assigns a terminal status to every message that will not be sent to Systran, and
     * returns the remainder grouped by normalized source language.
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

            String sourceLang = LanguageCodes.normalize(message.getDetectedLanguage());

            if (!LanguageCodes.isAuto(sourceLang) && LanguageCodes.sameLanguage(sourceLang, target)) {
                apply(message, MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE,
                        "Message is already in the target language.");
                continue;
            }

            groups.computeIfAbsent(sourceLang, k -> new ArrayList<>()).add(message);
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

    private void translateGroup(List<TranslateMessageRequest> group, String sourceLang, String target) {
        for (List<TranslateMessageRequest> batch : splitDelimiterUnsafe(group)) {
            List<String> bodies = batch.stream()
                    .map(message -> StringUtils.defaultString(message.getHtml()))
                    .toList();

            SystranOutcome outcome = systranGateway.translate(segmentCodec.join(bodies), sourceLang, target);

            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Original retained for {} message(s).", rejected.detail(), batch.size());
                markUnsupported(batch, sourceLang, target);
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
     * goes in a single batch - Systran's input ceiling is high enough that size never forces
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

    private void markUnsupported(List<TranslateMessageRequest> group, String sourceLang, String target) {
        String reason = String.format("Source language '%s' is not supported for target '%s'.",
                sourceLang, target);
        group.forEach(message ->
                apply(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE, reason));
        log.info("Skipping {} message(s) - unsupported source '{}' -> target '{}'.",
                group.size(), sourceLang, target);
    }

    private void markFailed(List<TranslateMessageRequest> group, String sourceLang, String target,
                            TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        String reason = "Translation temporarily unavailable"
                + (code == null ? "" : " (" + code.name() + ")") + "; original retained.";
        group.forEach(message -> apply(message, MessageTranslationStatus.FAILED_UPSTREAM, reason));
        log.error("Group '{}' -> '{}' failed for {} message(s).",
                sourceLang, target, group.size(), ex);
    }

    private void apply(TranslateMessageRequest message, MessageTranslationStatus status, String reason) {
        message.setTranslationStatus(status);
        message.setTranslated(status.isTranslated()); // legacy flag, kept in sync
        message.setTranslationSkippedReason(reason);
    }
}
