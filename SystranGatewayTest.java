package com.citi.uno.items.translation.client;

import com.citi.uno.items.translation.dto.SystranTranslationRequest;
import com.citi.uno.items.translation.dto.SystranTranslationResponse;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystranGatewayTest {

    @Mock
    private SystranClient systranClient;

    @InjectMocks
    private SystranGateway gateway;

    @BeforeEach
    void setUp() {
        // Only needed if you added the slow-call logging field.
        ReflectionTestUtils.setField(gateway, "slowCallThresholdMs", 5000L);
    }

    private SystranTranslationResponse ok(String text, String source, String detectedSource) {
        SystranTranslationResponse.Body body = new SystranTranslationResponse.Body();
        body.setText(text);
        body.setSource(source);
        body.setDetectedSource(detectedSource);

        SystranTranslationResponse response = new SystranTranslationResponse();
        response.setBody(body);
        response.setStatus("success");
        return response;
    }

    @Test
    @DisplayName("success returns Translated with the response text")
    void success() {
        when(systranClient.translate(any())).thenReturn(ok("hello", "fr", "fr"));

        SystranOutcome outcome = gateway.translate("bonjour", "fr", "en");

        assertThat(outcome).isInstanceOf(SystranOutcome.Translated.class);
        assertThat(((SystranOutcome.Translated) outcome).text()).isEqualTo("hello");
    }

    @Test
    @DisplayName("detectedSource wins, then source, then the requested code")
    void detectedSourceFallbackChain() {
        when(systranClient.translate(any())).thenReturn(ok("hello", "fr", "de"));
        assertThat(((SystranOutcome.Translated) gateway.translate("x", "fr", "en")).detectedSource())
                .isEqualTo("de");

        when(systranClient.translate(any())).thenReturn(ok("hello", "fr", null));
        assertThat(((SystranOutcome.Translated) gateway.translate("x", "auto", "en")).detectedSource())
                .isEqualTo("fr");

        when(systranClient.translate(any())).thenReturn(ok("hello", null, "  "));
        assertThat(((SystranOutcome.Translated) gateway.translate("x", "auto", "en")).detectedSource())
                .isEqualTo("auto");
    }

    @Test
    @DisplayName("406 becomes SourceRejected, not an exception")
    void notAcceptableBecomesValue() {
        when(systranClient.translate(any())).thenThrow(mock(FeignException.NotAcceptable.class));

        SystranOutcome outcome = gateway.translate("bonjour", "th", "en");

        assertThat(outcome).isInstanceOf(SystranOutcome.SourceRejected.class);
        assertThat(((SystranOutcome.SourceRejected) outcome).detail()).contains("th").contains("en");
    }

    @Test
    @DisplayName("timeout maps to SYSTRAN_TIMEOUT -> 504")
    void timeout() {
        when(systranClient.translate(any())).thenThrow(mock(RetryableException.class));

        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_TIMEOUT);
    }

    @Test
    @DisplayName("5xx maps to SYSTRAN_API_ERROR -> 502")
    void serverError() {
        FeignException ex = mock(FeignException.class);
        lenient().when(ex.status()).thenReturn(500);
        when(systranClient.translate(any())).thenThrow(ex);

        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);
    }

    @Test
    @DisplayName("HTTP 200 with status=error is still a failure")
    void statusErrorOn200() {
        SystranTranslationResponse response = ok(null, "fr", "fr");
        response.setStatus("error");
        response.getBody().setMessage("quota exceeded");
        when(systranClient.translate(any())).thenReturn(response);

        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);
    }

    @Test
    @DisplayName("upstream error detail is not echoed to the caller")
    void upstreamDetailNotLeaked() {
        SystranTranslationResponse response = ok(null, "fr", "fr");
        response.setStatus("error");
        response.getBody().setMessage("failed on input: Bonjour Jean-Pierre, votre compte...");
        when(systranClient.translate(any())).thenReturn(response);

        // Submitted content may be echoed into body.message - it must not reach our response.
        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .hasMessageNotContaining("Jean-Pierre");
    }

    @Test
    @DisplayName("null response and null body are rejected, not NPE'd")
    void nullResponses() {
        when(systranClient.translate(any())).thenReturn(null);
        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);

        when(systranClient.translate(any())).thenReturn(new SystranTranslationResponse());
        assertThatThrownBy(() -> gateway.translate("x", "fr", "en"))
                .isInstanceOf(TranslationException.class);
    }

    @Test
    @DisplayName("the source we were handed is the source that goes on the wire")
    void sendsResolvedCodeVerbatim() {
        when(systranClient.translate(any())).thenReturn(ok("hello", "zh", "zh"));

        gateway.translate("x", "zh", "en");

        org.mockito.ArgumentCaptor<SystranTranslationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SystranTranslationRequest.class);
        org.mockito.Mockito.verify(systranClient).translate(captor.capture());
        // Adjust the getter if your DTO field names differ.
        assertThat(captor.getValue().getSource()).isEqualTo("zh");
        assertThat(captor.getValue().getTarget()).isEqualTo("en");
    }
}
