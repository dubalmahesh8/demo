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
 * <h2>Flow</h2>
 * <pre>
 *   translate(messages, target)
 *     |
 *     +-- STEP 1  classifyAndGroup   decide each message's fate; group the survivors
 *     |                              by the code Systran actually accepts
 *     |
 *     +-- STEP 2  translateGroups    one batched call per group, recording outcomes
 *     |             |
 *     |             +-- translateGroup      join -> call -> split -> write back
 *     |                   |
 *     |                   +-- splitDelimiterUnsafe   isolate bodies holding the delimiter
 *     |
 *     +-- STEP 3  failIfNothingSucceeded   502/504 only when every attempt failed
 * </pre>
 *
 * <p>Each step is total: after STEP 1 every message either has a terminal status or sits in
 * a group; after STEP 2 every message has a status. Nothing is left half-decided between
 * phases, which is what makes the flow safe to read top-to-bottom.
 *
 * <p>Systran accepts ~50MB per call, so size never forces a split. The only reason a group
 * is broken up is a body that already contains the segment delimiter.
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

        // STEP 1 - Decide what to translate.
        Map<String, List<TranslateMessageRequest>> groups = classifyAndGroup(messages, normalizedTarget);

        // STEP 2 - Translate it.
        BatchOutcome outcome = translateGroups(groups, normalizedTarget);

        // STEP 3 - Decide whether the request as a whole succeeded.
        outcome.failIfNothingSucceeded();
        outcome.logSummary(normalizedTarget, groups.size());

        return messages;
    }

    // ============================================================ STEP 1: classify and group

    /**
     * Assigns a terminal status to every message that will not be sent to Systran, and returns
     * the remainder grouped by the resolved Systran source code.
     *
     * <p>The checks run cheapest-first, and each one is a complete answer for that message:
     * <ol>
     *   <li>INFO subtype - never translated by design</li>
     *   <li>no translatable content - nothing to send</li>
     *   <li>already in the target language - nothing to gain</li>
     *   <li>unsupported pair - Systran would only reject it</li>
     * </ol>
     * Anything surviving all four is groupable, and the group key is the code Systran
     * publishes rather than the caller's tag, so {@code zh-Hans} and {@code zh} share a call.
     */
    private Map<String, List<TranslateMessageRequest>> classifyAndGroup(
            List<TranslateMessageRequest> messages, String target) {

        Map<String, List<TranslateMessageRequest>> groups = new LinkedHashMap<>();

        for (TranslateMessageRequest message : messages) {

            // 1.1 - INFO messages are excluded by product rule, not by capability.
            if (INFO.equalsIgnoreCase(message.getSubType())) {
                apply(message, MessageTranslationStatus.SKIPPED_INFO_MESSAGE, null);
                continue;
            }

            // 1.2 - Nothing to translate: blank, or markup and punctuation only.
            if (!hasTranslatableContent(message.getHtml())) {
                apply(message, MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                        "Message body contains no translatable text.");
                continue;
            }

            String declared = LanguageCodes.normalize(message.getDetectedLanguage());

            // 1.3 - Already in the target language. Base-code comparison, so zh-Hans matches zh.
            if (!LanguageCodes.isAuto(declared) && LanguageCodes.sameLanguage(declared, target)) {
                apply(message, MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE,
                        "Message is already in the target language.");
                continue;
            }

            // 1.4 - Pre-check and code resolution are the same question: if Systran publishes
            // this pair, we learn the exact code to send; if not, we skip the call entirely.
            Optional<String> systranSource = supportedLanguagePolicy.resolveSource(declared, target);
            if (systranSource.isEmpty()) {
                apply(message, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                        String.format("Source language '%s' is not supported for target '%s'.",
                                declared, target));
                continue;
            }

            // 1.5 - Survivor. Keyed by the publishable code, not the caller's tag.
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

    // ============================================================ STEP 2: translate

    /** Runs each group independently, so one bad language cannot sink the others. */
    private BatchOutcome translateGroups(Map<String, List<TranslateMessageRequest>> groups, String target) {
        BatchOutcome outcome = new BatchOutcome();

        for (Map.Entry<String, List<TranslateMessageRequest>> entry : groups.entrySet()) {
            String systranSource = entry.getKey();
            List<TranslateMessageRequest> group = entry.getValue();
            try {
                translateGroup(group, systranSource, target);
                outcome.recordSuccess();
            } catch (TranslationException ex) {
                markFailed(group, systranSource, target, ex);
                outcome.recordFailure(ex);
            }
        }
        return outcome;
    }

    /** One group: join the bodies, call once, split the reply, write each segment back. */
    private void translateGroup(List<TranslateMessageRequest> group, String systranSource, String target) {
        for (List<TranslateMessageRequest> batch : splitDelimiterUnsafe(group)) {

            // 2.1 - Join every body into a single delimited payload.
            List<String> bodies = batch.stream()
                    .map(message -> StringUtils.defaultString(message.getHtml()))
                    .toList();

            // 2.2 - One call for the whole batch.
            SystranOutcome outcome =
                    systranGateway.translate(segmentCodec.join(bodies), systranSource, target);

            // 2.3 - Systran declined the pair. Permanent for this language; keep the originals.
            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Original retained for {} message(s).", rejected.detail(), batch.size());
                markUnsupported(batch, systranSource, target);
                continue;
            }

            // 2.4 - Split the reply. Throws TRANSLATION_SEGMENT_MISMATCH rather than risk
            // writing one message's translation into another.
            SystranOutcome.Translated translated = (SystranOutcome.Translated) outcome;
            List<String> segments = segmentCodec.split(translated.text(), batch.size());

            // 2.5 - Positional write-back, safe only because 2.4 verified the count.
            for (int i = 0; i < batch.size(); i++) {
                TranslateMessageRequest message = batch.get(i);
                message.setHtml(segments.get(i));
                apply(message, MessageTranslationStatus.TRANSLATED, null);
            }
        }
    }

    /**
     * Isolates any message whose body already contains the segment delimiter — batching it
     * would desynchronise the split. Everything else travels together.
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

    // ============================================================ STEP 3: batch verdict

    /**
     * Tally across groups, and the rule for turning it into an HTTP outcome.
     *
     * <p>Exists so {@code attempted > 0 && failed == attempted} has a name instead of being an
     * unexplained condition, and so the three counters cannot be updated inconsistently:
     * every recorded outcome increments {@code attempted} exactly once.
     */
    private static final class BatchOutcome {

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

        /**
         * Propagates only when every group we actually tried failed. Returning 200 in that case
         * would tell the caller the content is untranslatable, when in fact Systran is unwell.
         *
         * <p>Note {@code attempted > 0}: a request where every message was skipped attempted
         * nothing, so nothing failed, and 200 is the honest answer.
         */
        void failIfNothingSucceeded() {
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

    // ============================================================ status helpers

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
        log.error("Group '{}' -> '{}' failed for {} message(s): {}",
                systranSource, target, group.size(),
                group.stream().map(TranslateMessageRequest::getSubject).toList(), ex);
    }

    /** Single write point for message status. Every exit path goes through here. */
    private void apply(TranslateMessageRequest message, MessageTranslationStatus status, String reason) {
        message.setTranslationStatus(status);
        message.setTranslationSkippedReason(reason);
    }
}
