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
 * Formatted View: groups messages by source language, one batched Systran call per group.
 * Flow: classifyAndGroup -> translateGroups -> failIfNothingSucceeded.
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
        RequestTally tally = translateGroups(groups, normalizedTarget);

        tally.failIfNothingSucceeded();
        tally.logSummary(normalizedTarget, groups.size());
        return messages;
    }

    // ---------------------------------------------------------------- STEP 1: classify and group

    /** Gives every skipped message a terminal status; groups the rest by resolved Systran code. */
    private Map<String, List<TranslateMessageRequest>> classifyAndGroup(
            List<TranslateMessageRequest> messages, String target) {

        Map<String, List<TranslateMessageRequest>> groups = new LinkedHashMap<>();

        for (TranslateMessageRequest message : messages) {
            if (INFO.equalsIgnoreCase(message.getSubType())) {
                recordStatus(message, MessageTranslationStatus.SKIPPED_INFO_MESSAGE, null);
                continue;
            }
            if (!hasTranslatableContent(message.getHtml())) {
                recordStatus(message, MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                        "Message body contains no translatable text.");
                continue;
            }

            String declared = LanguageCodes.normalize(message.getDetectedLanguage());

            // Base-code comparison, so zh-Hans counts as already-target when target is zh.
            if (!LanguageCodes.isAuto(declared) && LanguageCodes.sameLanguage(declared, target)) {
                recordStatus(message, MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE,
                        "Message is already in the target language.");
                continue;
            }

            // Pre-check and code resolution are one question: empty means skip the call entirely.
            Optional<String> systranSource = supportedLanguagePolicy.resolveSource(declared, target);
            if (systranSource.isEmpty()) {
                recordStatus(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                        String.format("Source language '%s' is not supported for target '%s'.",
                                declared, target));
                continue;
            }

            // Keyed by the publishable code, so zh-Hans and zh share one call.
            groups.computeIfAbsent(systranSource.get(), k -> new ArrayList<>()).add(message);
        }
        return groups;
    }

    private boolean hasTranslatableContent(String html) {
        if (StringUtils.isBlank(html)) {
            return false;
        }
        return Jsoup.parse(html).text().codePoints().anyMatch(Character::isLetter);
    }

    // ---------------------------------------------------------------- STEP 2: translate

    /** Each group runs independently, so one bad language cannot sink the others. */
    private RequestTally translateGroups(Map<String, List<TranslateMessageRequest>> groups, String target) {
        RequestTally tally = new RequestTally();

        groups.forEach((systranSource, group) -> {
            try {
                translateGroup(group, systranSource, target);
                tally.recordSuccess();
            } catch (TranslationException ex) {
                markFailed(group, systranSource, target, ex);
                tally.recordFailure(ex);
            }
        });
        return tally;
    }

    /** Join the bodies, call once, split the reply, write each segment back. */
    private void translateGroup(List<TranslateMessageRequest> group, String systranSource, String target) {
        for (List<TranslateMessageRequest> batch : separateBodiesContainingDelimiter(group)) {
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

            // split throws on a count mismatch rather than risk writing one message's text into another.
            SystranOutcome.Translated translated = (SystranOutcome.Translated) outcome;
            List<String> segments = segmentCodec.split(translated.text(), batch.size());

            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).setHtml(segments.get(i));
                recordStatus(batch.get(i), MessageTranslationStatus.TRANSLATED, null);
            }
        }
    }

    /** Bodies containing the delimiter go alone; batching them would desynchronise the split. */
    private List<List<TranslateMessageRequest>> separateBodiesContainingDelimiter(List<TranslateMessageRequest> group) {
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

    // ---------------------------------------------------------------- STEP 3: deciding the HTTP status

    /** Tally across groups. Exists so the 502 rule has a name and the counters cannot drift. */
    private static final class RequestTally {

        private int attempted;
        private int failed;
        private TranslationException lastFailure;

        void recordSuccess() {
            attempted++;
        }

        void recordFailure(TranslationException ex) {
            attempted++;
            failed++;
            lastFailure = ex;
        }

        /** 200 would claim the content is untranslatable when really Systran is unwell. */
        void failIfNothingSucceeded() {
            // attempted > 0: an all-skipped request tried nothing, so nothing failed.
            if (attempted > 0 && failed == attempted) {
                throw lastFailure;
            }
        }

        void logSummary(String target, int groupCount) {
            if (failed > 0) {
                log.warn("Formatted view degraded - target: {}, groups: {}, failed: {}.",
                        target, groupCount, failed);
            } else {
                log.info("Formatted view complete - target: {}, groups: {}.", target, groupCount);
            }
        }
    }

    // ---------------------------------------------------------------- status helpers

    private void markUnsupported(List<TranslateMessageRequest> group, String systranSource, String target) {
        // Reason quotes the caller's own code, not the resolved one, so they can correlate it.
        group.forEach(message -> recordStatus(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                String.format("Source language '%s' is not supported for target '%s'.",
                        StringUtils.defaultIfBlank(message.getDetectedLanguage(), systranSource), target)));
        log.info("Skipping {} message(s) - Systran rejected source '{}' -> target '{}'.",
                group.size(), systranSource, target);
    }

    private void markFailed(List<TranslateMessageRequest> group, String systranSource, String target,
                            TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        group.forEach(message -> recordStatus(message, MessageTranslationStatus.FAILED_UPSTREAM,
                "Translation temporarily unavailable"
                        + (code == null ? "" : " (" + code.name() + ")") + "; original retained."));
        log.error("Group '{}' -> '{}' failed for {} message(s): {}", systranSource, target, group.size(),
                group.stream().map(TranslateMessageRequest::getSubject).toList(), ex);
    }

    /** Single write point for message status; every exit path goes through here. */
    private void recordStatus(TranslateMessageRequest message, MessageTranslationStatus status, String reason) {
        message.setTranslationStatus(status);
        message.setTranslationSkippedReason(reason);
    }
}
