package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateSimpleRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleViewTranslatorTest {

    @Mock
    private SupportedLanguagePolicy policy;
    @Mock
    private SystranGateway gateway;

    @InjectMocks
    private SimpleViewTranslator translator;

    private TranslateSimpleRequest blob(String message, String... detected) {
        TranslateSimpleRequest request = new TranslateSimpleRequest();
        request.setMessage(message);
        request.setDetectedLanguages(detected.length == 0 ? null : new ArrayList<>(Arrays.asList(detected)));
        return request;
    }

    private SystranOutcome.Translated translated(String text) {
        return new SystranOutcome.Translated(text, "fr");
    }

    // ================================================================== skips

    @Nested
    @DisplayName("blobs that never reach Systran")
    class Skips {

        @Test
        @DisplayName("blank and markup-only payloads have nothing to translate")
        void noTranslatableContent() {
            TranslateSimpleRequest blank = blob("", "fr");
            TranslateSimpleRequest tagsOnly = blob("<p><br /></p>", "fr");

            translator.translate(List.of(blank, tagsOnly), "en");

            assertThat(List.of(blank, tagsOnly)).allMatch(b -> b.getTranslationStatus()
                    == MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT);
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("every detected language equals the target")
        void allTargetLanguage() {
            TranslateSimpleRequest request = blob("<p>hello</p>", "en", "en-GB");

            translator.translate(List.of(request), "en");

            assertThat(request.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE);
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("no detected language resolves - recorded in notSupportedLanguages")
        void noneSupported() {
            when(policy.resolveSource("th", "en")).thenReturn(Optional.empty());
            when(policy.resolveSource("km", "en")).thenReturn(Optional.empty());
            TranslateSimpleRequest request = blob("<p>sawasdee</p>", "th", "km");

            translator.translate(List.of(request), "en");

            assertThat(request.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE);
            assertThat(request.getNotSupportedLanguages()).containsExactly("th", "km");
            assertThat(request.getMessage()).isEqualTo("<p>sawasdee</p>");
        }

        @Test
        @DisplayName("blank entries inside detectedLanguages are ignored")
        void blankEntriesIgnored() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("hello"));
            TranslateSimpleRequest request = blob("<p>bonjour</p>", "fr", "", "   ");

            translator.translate(List.of(request), "en");

            verify(gateway, times(1)).translate(anyString(), anyString(), anyString());
        }
    }

    // ================================================================== chaining

    @Nested
    @DisplayName("chain planning and execution")
    class Chaining {

        @Test
        @DisplayName("empty detectedLanguages falls through to auto")
        void emptyDetectedUsesAuto() {
            when(gateway.translate(any(), eq("auto"), eq("en")))
                    .thenReturn(new SystranOutcome.Translated("hello", "fr"));
            TranslateSimpleRequest request = blob("<p>bonjour</p>");

            translator.translate(List.of(request), "en");

            assertThat(request.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
            // Systran's own detection is echoed back only on the auto path.
            assertThat(request.getDetectedLanguages()).containsExactly("fr");
        }

        @Test
        @DisplayName("detection is NOT echoed back when languages were supplied")
        void noEchoWhenExplicit() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenReturn(new SystranOutcome.Translated("hello", "de"));
            TranslateSimpleRequest request = blob("<p>bonjour</p>", "fr");

            translator.translate(List.of(request), "en");

            assertThat(request.getDetectedLanguages()).containsExactly("fr");   // caller's value kept
        }

        @Test
        @DisplayName("zh-Hans and zh dedupe to a single hop")
        void dedupeOnResolvedCode() {
            when(policy.resolveSource("zh-hans", "en")).thenReturn(Optional.of("zh"));
            when(policy.resolveSource("zh", "en")).thenReturn(Optional.of("zh"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("hello"));

            translator.translate(List.of(blob("<p>ni hao</p>", "zh-Hans", "zh")), "en");

            verify(gateway, times(1)).translate(anyString(), eq("zh"), eq("en"));
        }

        @Test
        @DisplayName("each hop feeds the next")
        void hopsAreSequential() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(policy.resolveSource("hu", "en")).thenReturn(Optional.of("hu"));
            when(gateway.translate(eq("<p>original</p>"), eq("fr"), eq("en")))
                    .thenReturn(translated("after-fr"));
            when(gateway.translate(eq("after-fr"), eq("hu"), eq("en")))
                    .thenReturn(translated("after-hu"));

            TranslateSimpleRequest request = blob("<p>original</p>", "fr", "hu");
            translator.translate(List.of(request), "en");

            assertThat(request.getMessage()).isEqualTo("after-hu");
        }

        @Test
        @DisplayName("target language is filtered out of the chain")
        void targetFilteredFromChain() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("hello"));

            translator.translate(List.of(blob("<p>mixed</p>", "fr", "en")), "en");

            verify(gateway, times(1)).translate(anyString(), anyString(), anyString());
            verify(gateway, never()).translate(anyString(), eq("en"), anyString());
        }
    }

    // ================================================================== failures

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("406 mid-chain records the language and continues")
        void rejectionMidChainContinues() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(policy.resolveSource("hu", "en")).thenReturn(Optional.of("hu"));
            when(gateway.translate(any(), eq("fr"), any()))
                    .thenReturn(new SystranOutcome.SourceRejected("406"));
            when(gateway.translate(any(), eq("hu"), any())).thenReturn(translated("after-hu"));

            TranslateSimpleRequest request = blob("<p>original</p>", "fr", "hu");
            translator.translate(List.of(request), "en");

            assertThat(request.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
            assertThat(request.getMessage()).isEqualTo("after-hu");
            assertThat(request.getNotSupportedLanguages()).containsExactly("fr");
        }

        @Test
        @DisplayName("406 on every hop leaves the original in place")
        void allHopsRejected() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenReturn(new SystranOutcome.SourceRejected("406"));

            TranslateSimpleRequest request = blob("<p>original</p>", "fr");
            translator.translate(List.of(request), "en");

            assertThat(request.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE);
            assertThat(request.getMessage()).isEqualTo("<p>original</p>");
        }

        @Test
        @DisplayName("FIRST hop fails -> blob reverts to original and the request 502s")
        void firstHopFailurePropagates() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));

            assertThatThrownBy(() -> translator.translate(List.of(blob("<p>original</p>", "fr")), "en"))
                    .isInstanceOf(TranslationException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);
        }

        @Test
        @DisplayName("LATER hop fails -> completed work is kept, 200 with PARTIALLY_TRANSLATED")
        void laterHopFailureKeepsProgress() {
            // This is the ["fr","auto"] case: fr succeeded, auto returned 500.
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(eq("<p>original</p>"), eq("fr"), eq("en")))
                    .thenReturn(translated("after-fr"));
            when(gateway.translate(eq("after-fr"), eq("auto"), eq("en")))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));

            TranslateSimpleRequest request = blob("<p>original</p>", "fr", "auto");
            List<TranslateSimpleRequest> result = translator.translate(List.of(request), "en");

            assertThat(result).hasSize(1);   // no exception - the fr work is not discarded
            assertThat(request.getMessage()).isEqualTo("after-fr");
            assertThat(request.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.PARTIALLY_TRANSLATED);
            assertThat(request.getTranslationNote()).contains("SYSTRAN_API_ERROR");
        }

        @Test
        @DisplayName("PARTIALLY_TRANSLATED is both translated and retryable")
        void partialIsTranslatedAndRetryable() {
            assertThat(MessageTranslationStatus.PARTIALLY_TRANSLATED.isTranslated()).isTrue();
            assertThat(MessageTranslationStatus.PARTIALLY_TRANSLATED.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("one blob fails and another succeeds -> 200")
        void partialBatchReturns200() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(policy.resolveSource("hu", "en")).thenReturn(Optional.of("hu"));
            when(gateway.translate(any(), eq("fr"), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_TIMEOUT, "timeout"));
            when(gateway.translate(any(), eq("hu"), any())).thenReturn(translated("HU"));

            TranslateSimpleRequest bad = blob("<p>un</p>", "fr");
            TranslateSimpleRequest good = blob("<p>szia</p>", "hu");

            translator.translate(List.of(bad, good), "en");

            assertThat(bad.getTranslationStatus()).isEqualTo(MessageTranslationStatus.FAILED_UPSTREAM);
            assertThat(bad.getMessage()).isEqualTo("<p>un</p>");
            assertThat(good.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
        }

        @Test
        @DisplayName("every attempted blob fails -> propagates for 504")
        void allBlobsFailPropagates() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_TIMEOUT, "timeout"));

            assertThatThrownBy(() -> translator.translate(
                    List.of(blob("<p>a</p>", "fr"), blob("<p>b</p>", "fr")), "en"))
                    .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_TIMEOUT);
        }

        @Test
        @DisplayName("a partial blob does NOT count as failed for the batch rule")
        void partialDoesNotCountAsFailed() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(eq("<p>original</p>"), eq("fr"), eq("en")))
                    .thenReturn(translated("after-fr"));
            when(gateway.translate(eq("after-fr"), eq("auto"), eq("en")))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));

            // Single blob, and its only hop failure came after a success -> must not throw.
            assertThat(translator.translate(List.of(blob("<p>original</p>", "fr", "auto")), "en"))
                    .hasSize(1);
        }
    }
}
