package com.citi.uno.items.translation.client;

import com.citi.uno.items.translation.dto.SystranTranslationRequest;
import com.citi.uno.items.translation.dto.SystranTranslationResponse;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * The single place that knows how Systran fails.
 *
 * <p>Everything above this class deals in {@link SystranOutcome} and
 * {@link TranslationException}; nothing above it touches Feign types or raw HTTP status
 * codes. That keeps the retryable/permanent classification in one place instead of
 * scattered across two views.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystranGateway {

    private final SystranClient systranClient;

    public SystranOutcome translate(String text, String source, String target) {
        try {
            SystranTranslationResponse response = systranClient.translate(buildRequest(text, source, target));
            return interpret(response, source, target);

        } catch (FeignException.NotAcceptable ex) {
            // Systran advertised this pair but rejects it at call time.
            return new SystranOutcome.SourceRejected(
                    "Systran rejected source '" + source + "' for target '" + target + "' (406).");

        } catch (RetryableException ex) {
            // Feign wraps connect/read timeouts and connection refused here.
            throw new TranslationException(TranslationErrorCode.SYSTRAN_TIMEOUT,
                    "Systran did not respond in time.", ex);

        } catch (FeignException ex) {
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran returned HTTP " + ex.status() + ".", ex);
        }
    }

    private SystranOutcome interpret(SystranTranslationResponse response, String source, String target) {
        if (response == null || response.getBody() == null) {
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran returned an empty body for " + source + " -> " + target + ".");
        }

        // Systran can return HTTP 200 with status="error" and the detail in body.message.
        if ("error".equalsIgnoreCase(response.getStatus())) {
            // Deliberately not echoed to the client - upstream error text is not ours to leak.
            log.warn("Systran reported an error for {} -> {}: {}", source, target,
                    response.getBody().getMessage());
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran could not complete the translation.");
        }

        String detected = StringUtils.defaultIfBlank(
                response.getBody().getDetectedSource(),
                StringUtils.defaultIfBlank(response.getBody().getSource(), source));

        return new SystranOutcome.Translated(stripSystranMeta(response.getBody().getText()), detected);
    }

    private SystranTranslationRequest buildRequest(String text, String source, String target) {
        // TODO(mahesh): confirm against SystranTranslationRequest's actual field names -
        // I could not read them in the screenshots. Adjust setters if they differ.
        SystranTranslationRequest request = new SystranTranslationRequest();
        request.setInput(text);
        request.setSource(source);
        request.setTarget(target);
        return request;
    }

    /**
     * TODO(mahesh): move the body of the existing private stripSystranMeta(String) from
     * TranslationService into here verbatim. Doing the strip at the boundary means no
     * caller ever sees un-cleaned Systran text, and the method stops being duplicated
     * across the batched and single-blob paths.
     */
    private String stripSystranMeta(String text) {
        return text;
    }
}
