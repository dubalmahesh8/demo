package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateMessageRequest;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.LanguageCodes;
import com.citi.uno.items.translation.service.support.RequestSummary;
import com.citi.uno.items.translation.service.support.MessageBatcher;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import com.citi.uno.items.translation.service.support.TranslationOutcomes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Formatted View: messages arrive already split into individual chat lines, so they are grouped
 * by source language and each group is translated in a single Systran call.
 *
 * <p>Three steps: {@link #groupBySourceLanguage}, {@link #translateGroup}, then the request-level
 * verdict in {@link RequestSummary}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormattedViewTranslator {

    private static final String INFO = "INFO";

    private final SupportedLanguagePolicy supportedLanguagePolicy;
    private final MessageBatcher messageBatcher;
    private final SystranGateway systranGateway;

    public List<TranslateMessageRequest> translate(List<TranslateMessageRequest> messages, String target) {
        String normalizedTarget = LanguageCodes.normalize(target);
        RequestSummary summary = new RequestSummary("Formatted view", "group");

        log.info("Formatted view start - target: {}, messages: {}", normalizedTarget, messages.size());

        Map<String, List<TranslateMessageRequest>> groups =
                groupBySourceLanguage(messages, normalizedTarget, summary);

        log.info("Grouped by source language: {}", describe(groups));

        groups.forEach((sourceLanguage, group) ->
                translateOneGroup(group, sourceLanguage, normalizedTarget, summary));

        summary.failIfNothingSucceeded();
        summary.log(normalizedTarget, messages.size());
        return messages;
    }

    private void translateOneGroup(List<TranslateMessageRequest> group, String sourceLanguage,
                                   String target, RequestSummary summary) {
        log.info("Sending {} message(s) to Systran: {} -> {}", group.size(), sourceLanguage, target);
        try {
            translateGroup(group, sourceLanguage, target);
            summary.recordUnitSucceeded(MessageTranslationStatus.TRANSLATED);
        } catch (TranslationException ex) {
            markGroupFailed(group, sourceLanguage, target, ex, summary);
            summary.recordUnitFailed(ex);
        }
    }

    /** Readable group breakdown for the log, e.g. {fr=3, zh=2}. */
    private String describe(Map<String, List<TranslateMessageRequest>> groups) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        groups.forEach((language, group) -> sizes.put(language, group.size()));
        return sizes.toString();
    }

    // ---------------------------------------------------------------- step 1: group the messages

    /**
     * Gives every message that will not be sent its final status, and returns the rest grouped
     * by the language code Systran accepts.
     */
    private Map<String, List<TranslateMessageRequest>> groupBySourceLanguage(
            List<TranslateMessageRequest> messages, String target, RequestSummary summary) {

        Map<String, List<TranslateMessageRequest>> groups = new LinkedHashMap<>();

        for (TranslateMessageRequest message : messages) {
            if (INFO.equalsIgnoreCase(message.getSubType())) {
                skip(message, MessageTranslationStatus.SKIPPED_INFO_MESSAGE, null, summary);
                continue;
            }
            if (!TranslationOutcomes.hasTranslatableContent(message.getHtml())) {
                skip(message, MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT,
                        "Message body contains no translatable text.", summary);
                continue;
            }

            String callerCode = LanguageCodes.normalize(message.getDetectedLanguage());

            // Base-code comparison, so zh-Hans counts as already-target when the target is zh.
            if (!LanguageCodes.isAuto(callerCode) && LanguageCodes.sameLanguage(callerCode, target)) {
                skip(message, MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE,
                        "Message is already in the target language.", summary);
                continue;
            }

            // Checking support and finding the code to send are the same question: empty means
            // Systran does not offer this pair, so we skip the call entirely.
            Optional<String> systranCode = supportedLanguagePolicy.resolveSource(callerCode, target);
            if (systranCode.isEmpty()) {
                log.debug("Dropping message: source '{}' unsupported for target '{}'.", callerCode, target);
                TranslationOutcomes.markUnsupportedSource(message, callerCode, target);
                summary.countItem(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE);
                continue;
            }

            // Keyed by the Systran code, so zh-Hans and zh share one call.
            groups.computeIfAbsent(systranCode.get(), k -> new ArrayList<>()).add(message);
        }
        return groups;
    }

    private void skip(TranslateMessageRequest message, MessageTranslationStatus status,
                      String note, RequestSummary summary) {
        TranslationOutcomes.record(message, status, note);
        summary.countItem(status);
    }

    // ---------------------------------------------------------------- step 2: call Systran

    /** Joins the bodies into one payload, calls once, splits the reply, writes each part back. */
    private void translateGroup(List<TranslateMessageRequest> group, String systranCode, String target) {
        for (List<TranslateMessageRequest> batch : separateBodiesContainingDelimiter(group)) {
            List<String> bodies = batch.stream()
                    .map(message -> StringUtils.defaultString(message.getHtml()))
                    .toList();

            SystranOutcome outcome =
                    systranGateway.translate(messageBatcher.pack(bodies), systranCode, target);

            if (outcome instanceof SystranOutcome.SourceRejected rejected) {
                log.info("{} Original retained for {} message(s).", rejected.detail(), batch.size());
                batch.forEach(message -> TranslationOutcomes.markUnsupportedSource(message,
                        StringUtils.defaultIfBlank(message.getDetectedLanguage(), systranCode), target));
                continue;
            }

            // split throws on a count mismatch rather than risk writing one message's text into another.
            String translatedText = ((SystranOutcome.Translated) outcome).text();
            List<String> parts = messageBatcher.unpack(translatedText, batch.size());

            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).setHtml(parts.get(i));
                TranslationOutcomes.markTranslated(batch.get(i));
            }
            log.info("Wrote back {} translated segment(s) for '{}' -> '{}'.",
                    parts.size(), systranCode, target);
        }
    }

    /** Bodies holding the delimiter go alone; batching them would desynchronise the split. */
    private List<List<TranslateMessageRequest>> separateBodiesContainingDelimiter(
            List<TranslateMessageRequest> group) {

        List<List<TranslateMessageRequest>> batches = new ArrayList<>();
        List<TranslateMessageRequest> batchable = new ArrayList<>();

        for (TranslateMessageRequest message : group) {
            if (messageBatcher.isSafeToBatch(message.getHtml())) {
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

    // ---------------------------------------------------------------- step 3: record the outcome

    private void markGroupFailed(List<TranslateMessageRequest> group, String systranCode, String target,
                                 TranslationException ex, RequestSummary summary) {
        group.forEach(message -> {
            TranslationOutcomes.markFailedUpstream(message, ex);
            summary.countItem(MessageTranslationStatus.FAILED_UPSTREAM);
        });
        log.error("Group '{}' -> '{}' failed for {} message(s): {}", systranCode, target, group.size(),
                group.stream().map(TranslateMessageRequest::getSubject).toList(), ex);
    }
}
