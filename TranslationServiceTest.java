package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.dto.TranslateMessageRequest;
import com.citi.uno.items.translation.dto.TranslateSimpleRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private FormattedViewTranslator formattedViewTranslator;
    @Mock
    private SimpleViewTranslator simpleViewTranslator;

    @InjectMocks
    private TranslationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "systranEnabled", true);
    }

    @Test
    @DisplayName("formatted view delegates with the target unchanged")
    void formattedDelegates() {
        List<TranslateMessageRequest> messages = List.of(new TranslateMessageRequest());
        when(formattedViewTranslator.translate(messages, "ja")).thenReturn(messages);

        assertThat(service.translateFormattedView(messages, "ja")).isSameAs(messages);
        verify(formattedViewTranslator).translate(messages, "ja");
    }

    @Test
    @DisplayName("simple view delegates with the target unchanged")
    void simpleDelegates() {
        List<TranslateSimpleRequest> requests = List.of(new TranslateSimpleRequest());
        when(simpleViewTranslator.translate(requests, "ja")).thenReturn(requests);

        assertThat(service.translateSimpleView(requests, "ja")).isSameAs(requests);
        verify(simpleViewTranslator).translate(requests, "ja");
    }

    @Test
    @DisplayName("empty list is a no-op in BOTH views - the guard-order fix")
    void emptyListIsNoOpInBothViews() {
        assertThat(service.translateFormattedView(Collections.emptyList(), "en")).isEmpty();
        assertThat(service.translateSimpleView(Collections.emptyList(), "en")).isEmpty();

        verifyNoInteractions(formattedViewTranslator, simpleViewTranslator);
    }

    @Test
    @DisplayName("null list is tolerated in both views")
    void nullListIsTolerated() {
        assertThat(service.translateFormattedView(null, "en")).isNull();
        assertThat(service.translateSimpleView(null, "en")).isNull();

        verifyNoInteractions(formattedViewTranslator, simpleViewTranslator);
    }

    @Test
    @DisplayName("empty list does NOT trip the disabled check - order matters")
    void emptyListShortCircuitsBeforeEnabledCheck() {
        ReflectionTestUtils.setField(service, "systranEnabled", false);

        // Previously the formatted view threw 503 here while the simple view returned 200.
        assertThat(service.translateFormattedView(Collections.emptyList(), "en")).isEmpty();
        assertThat(service.translateSimpleView(Collections.emptyList(), "en")).isEmpty();
    }

    @Test
    @DisplayName("disabled service throws 503 for both views")
    void disabledThrowsForBothViews() {
        ReflectionTestUtils.setField(service, "systranEnabled", false);

        assertThatThrownBy(() ->
                service.translateFormattedView(List.of(new TranslateMessageRequest()), "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        TranslationErrorCode.TRANSLATION_SERVICE_DISABLED);

        assertThatThrownBy(() ->
                service.translateSimpleView(List.of(new TranslateSimpleRequest()), "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        TranslationErrorCode.TRANSLATION_SERVICE_DISABLED);

        verifyNoInteractions(formattedViewTranslator, simpleViewTranslator);
    }

    @Test
    @DisplayName("TRANSLATION_SERVICE_DISABLED maps to 503")
    void disabledStatusMapping() {
        assertThat(TranslationErrorCode.TRANSLATION_SERVICE_DISABLED.getStatus().value()).isEqualTo(503);
        assertThat(TranslationErrorCode.SYSTRAN_API_ERROR.getStatus().value()).isEqualTo(502);
        assertThat(TranslationErrorCode.SYSTRAN_TIMEOUT.getStatus().value()).isEqualTo(504);
        assertThat(TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH.getStatus().value()).isEqualTo(502);
    }

    @Test
    @DisplayName("translator exceptions are not swallowed")
    void exceptionsPropagate() {
        List<TranslateMessageRequest> messages = List.of(new TranslateMessageRequest());
        when(formattedViewTranslator.translate(any(), anyString()))
                .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_TIMEOUT, "timeout"));

        assertThatThrownBy(() -> service.translateFormattedView(messages, "en"))
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_TIMEOUT);
    }
}
