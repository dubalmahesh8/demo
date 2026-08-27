package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.dto.TranslateMessageRequest;
import com.citi.uno.items.translation.dto.TranslateSimpleRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * Entry point for both translation views.
 *
 * <p>Holds no translation logic of its own. It enforces the two cross-cutting preconditions
 * and delegates:
 *
 * <ul>
 *   <li>{@link FormattedViewTranslator} - messages grouped by source language, one batched
 *       Systran call per group.</li>
 *   <li>{@link SimpleViewTranslator} - one payload blob per request, translated once
 *       per detected language.</li>
 * </ul>
 *
 * <p>The guard order is now identical for both views. Previously the formatted view checked
 * {@code systranEnabled} <em>before</em> the empty-list check and the simple view
 * <em>after</em>, so an empty list returned 200 from one endpoint and 503 from the other.
 * Empty input is a no-op in both, because there is nothing to fail at.
 *
 * <p>Both views return 200 with per-item statuses when some work succeeded, and let a
 * {@link TranslationException} propagate to {@code InputValidationExceptionHandler} (which
 * maps it to 502 / 503 / 504 via {@link TranslationErrorCode}) when nothing did.
 */
@Service
@RequiredArgsConstructor
public class TranslationService {

    private final FormattedViewTranslator formattedViewTranslator;
    private final SimpleViewTranslator simpleViewTranslator;

    @Value("${systran.api.enabled:true}")
    private boolean systranEnabled;

    /** Formatted View: groups messages by source language and translates each group in one call. */
    public List<TranslateMessageRequest> translateFormattedView(List<TranslateMessageRequest> messages,
                                                                String target) {
        if (CollectionUtils.isEmpty(messages)) {
            return messages;
        }
        assertEnabled();
        return formattedViewTranslator.translate(messages, target);
    }

    /** Simple View: translates each payload blob once per language it contains. */
    public List<TranslateSimpleRequest> translateSimpleView(List<TranslateSimpleRequest> requests,
                                                            String target) {
        if (CollectionUtils.isEmpty(requests)) {
            return requests;
        }
        assertEnabled();
        return simpleViewTranslator.translate(requests, target);
    }

    private void assertEnabled() {
        if (!systranEnabled) {
            throw new TranslationException(TranslationErrorCode.TRANSLATION_SERVICE_DISABLED,
                    "Translation service is currently disabled for this environment.");
        }
    }
}
