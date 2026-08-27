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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single place that knows how Systran fails.
 *
 * <p>Everything above this class deals in {@link SystranOutcome} and {@link TranslationException};
 * nothing above it touches Feign types or raw HTTP status codes. A 406 comes back as a value
 * because it is a routine answer ("I don't do that language pair"), while anything that a retry
 * might fix is thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystranGateway {

    private final SystranClient systranClient;

    @Value("${systran.api.slow-call-threshold-ms:5000}")
    private long slowCallThresholdMs;

    public SystranOutcome translate(String text, String source, String target) {
        long startedAt = System.nanoTime();
        try {
            SystranOutcome outcome = call(text, source, target);
            logCall(source, target, text, startedAt, describe(outcome));
            return outcome;

        } catch (TranslationException ex) {
            logCall(source, target, text, startedAt, ex.getErrorCode().name());
            throw ex;
        }
    }

    /** Everything that can go wrong with one call, mapped to our own vocabulary. */
    private SystranOutcome call(String text, String source, String target) {
        try {
            return interpret(systranClient.translate(buildRequest(text, source, target)), source, target);

        } catch (FeignException.NotAcceptable ex) {
            // Systran advertised this pair but declines it at call time. Routine, not a failure.
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

        // Systran can answer HTTP 200 with status="error" and the detail in body.message.
        if ("error".equalsIgnoreCase(response.getStatus())) {
            // Truncated, and never echoed to the caller - Systran may quote submitted text back.
            log.warn("Systran reported an error for {} -> {}: {}", source, target,
                    StringUtils.abbreviate(response.getBody().getMessage(), 200));
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran could not complete the translation.");
        }

        String detected = StringUtils.defaultIfBlank(response.getBody().getDetectedSource(),
                StringUtils.defaultIfBlank(response.getBody().getSource(), source));

        return new SystranOutcome.Translated(stripSystranMeta(response.getBody().getText()), detected);
    }

    private SystranTranslationRequest buildRequest(String text, String source, String target) {
        // TODO(mahesh): confirm against SystranTranslationRequest's actual field names.
        SystranTranslationRequest request = new SystranTranslationRequest();
        request.setInput(text);
        request.setSource(source);
        request.setTarget(target);
        return request;
    }

    /**
     * TODO(mahesh): move the body of the existing private stripSystranMeta(String) from
     * TranslationService into here verbatim. Stripping at the boundary means no caller ever
     * sees un-cleaned Systran text.
     */
    private String stripSystranMeta(String text) {
        return text;
    }

    // ---------------------------------------------------------------- logging

    private String describe(SystranOutcome outcome) {
        return outcome instanceof SystranOutcome.SourceRejected ? "rejected-406" : "ok";
    }

    /** Never logs the payload - these bodies are customer chat and email content. */
    private void logCall(String source, String target, String text, long startedAt, String result) {
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        int chars = StringUtils.length(text);
        if (ms > slowCallThresholdMs) {
            log.warn("Systran call slow - {} -> {}, chars: {}, result: {}, {} ms",
                    source, target, chars, result, ms);
        } else {
            log.info("Systran call - {} -> {}, chars: {}, result: {}, {} ms",
                    source, target, chars, result, ms);
        }
    }
}
