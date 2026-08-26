package com.citi.uno.items.translation.service;

import com.citi.uno.items.translation.client.SystranGateway;
import com.citi.uno.items.translation.client.SystranOutcome;
import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslateMessageRequest;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import com.citi.uno.items.translation.service.support.SegmentCodec;
import com.citi.uno.items.translation.service.support.SupportedLanguagePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormattedViewTranslatorTest {

    @Mock
    private SupportedLanguagePolicy policy;
    @Mock
    private SystranGateway gateway;

    private FormattedViewTranslator translator;

    @BeforeEach
    void setUp() {
        // Real codec - its join/split behaviour is part of what we are testing here.
        translator = new FormattedViewTranslator(policy, new SegmentCodec(), gateway);
    }

    private TranslateMessageRequest message(String html, String lang, String subType) {
        TranslateMessageRequest msg = new TranslateMessageRequest();
        msg.setHtml(html);
        msg.setDetectedLanguage(lang);
        msg.setSubType(subType);
        msg.setSubject("CHAT-fs:TEST");
        return msg;
    }

    private TranslateMessageRequest im(String html, String lang) {
        return message(html, lang, "IM");
    }

    private SystranOutcome.Translated translated(String text) {
        return new SystranOutcome.Translated(text, "fr");
    }

    // ================================================================== skips

    @Nested
    @DisplayName("messages that never reach Systran")
    class Skips {

        @Test
        @DisplayName("INFO messages are skipped by design")
        void infoSkipped() {
            TranslateMessageRequest msg = message("<p>bonjour</p>", "fr", "INFO");

            translator.translate(List.of(msg), "en");

            assertThat(msg.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_INFO_MESSAGE);
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("INFO check is case-insensitive")
        void infoCaseInsensitive() {
            TranslateMessageRequest msg = message("<p>bonjour</p>", "fr", "info");

            translator.translate(List.of(msg), "en");

            assertThat(msg.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_INFO_MESSAGE);
        }

        @Test
        @DisplayName("blank, null and markup-only bodies have nothing to translate")
        void noTranslatableContent() {
            TranslateMessageRequest blank = im("", "fr");
            TranslateMessageRequest nullHtml = im(null, "fr");
            TranslateMessageRequest tagsOnly = im("<p><br /></p>", "fr");
            TranslateMessageRequest digitsOnly = im("<p>123 456 !!</p>", "fr");

            translator.translate(List.of(blank, nullHtml, tagsOnly, digitsOnly), "en");

            assertThat(List.of(blank, nullHtml, tagsOnly, digitsOnly))
                    .allMatch(m -> m.getTranslationStatus()
                            == MessageTranslationStatus.SKIPPED_NO_TRANSLATABLE_CONTENT);
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a single letter counts as translatable content")
        void singleLetterIsContent() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("a"));
            TranslateMessageRequest msg = im("<p>a</p>", "fr");

            translator.translate(List.of(msg), "en");

            assertThat(msg.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
        }

        @Test
        @DisplayName("source already equals target, including BCP-47 variants")
        void alreadyTargetLanguage() {
            TranslateMessageRequest exact = im("<p>hello</p>", "en");
            TranslateMessageRequest variant = im("<p>ni hao</p>", "zh-Hans");

            translator.translate(List.of(exact), "en");
            translator.translate(List.of(variant), "zh");

            assertThat(exact.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE);
            assertThat(variant.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_ALREADY_TARGET_LANGUAGE);
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("unsupported pair is pre-checked, no Systran call")
        void unsupportedSourcePreCheck() {
            when(policy.resolveSource("th", "en")).thenReturn(Optional.empty());
            TranslateMessageRequest msg = im("<p>sawasdee</p>", "th");

            translator.translate(List.of(msg), "en");

            assertThat(msg.getTranslationStatus())
                    .isEqualTo(MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE);
            assertThat(msg.getTranslationSkippedReason()).contains("th").contains("en");
            assertThat(msg.getHtml()).isEqualTo("<p>sawasdee</p>");   // original retained
            verify(gateway, never()).translate(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("all messages skipped means nothing was attempted - still 200")
        void nothingAttemptedDoesNotThrow() {
            List<TranslateMessageRequest> messages = List.of(
                    message("<p>x</p>", "fr", "INFO"),
                    im("", "fr"),
                    im("<p>hello</p>", "en"));

            assertThat(translator.translate(messages, "en")).hasSize(3);
        }
    }

    // ================================================================== grouping

    @Nested
    @DisplayName("grouping and code resolution")
    class Grouping {

        @Test
        @DisplayName("zh-Hans and zh collapse into one group and one call")
        void bcp47AndBaseShareAGroup() {
            when(policy.resolveSource("zh-hans", "en")).thenReturn(Optional.of("zh"));
            when(policy.resolveSource("zh", "en")).thenReturn(Optional.of("zh"));
            when(gateway.translate(any(), eq("zh"), eq("en")))
                    .thenReturn(translated("A|||SEG|||B"));

            TranslateMessageRequest a = im("<p>a</p>", "zh-Hans");
            TranslateMessageRequest b = im("<p>b</p>", "zh");
            translator.translate(List.of(a, b), "en");

            verify(gateway, times(1)).translate(anyString(), anyString(), anyString());
            assertThat(a.getHtml()).isEqualTo("A");
            assertThat(b.getHtml()).isEqualTo("B");
        }

        @Test
        @DisplayName("the resolved code goes on the wire, never the caller's tag")
        void sendsResolvedCode() {
            when(policy.resolveSource("zh-hans", "en")).thenReturn(Optional.of("zh"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("A"));

            translator.translate(List.of(im("<p>a</p>", "zh-Hans")), "en");

            verify(gateway).translate(anyString(), eq("zh"), eq("en"));
        }

        @Test
        @DisplayName("the caller's detectedLanguage is left untouched")
        void detectedLanguageNotRewritten() {
            when(policy.resolveSource("zh-hans", "en")).thenReturn(Optional.of("zh"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("A"));
            TranslateMessageRequest msg = im("<p>a</p>", "zh-Hans");

            translator.translate(List.of(msg), "en");

            assertThat(msg.getDetectedLanguage()).isEqualTo("zh-Hans");
        }

        @Test
        @DisplayName("messages missing detectedLanguage fall through to auto")
        void nullLanguageBecomesAuto() {
            when(policy.resolveSource("auto", "en")).thenReturn(Optional.of("auto"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("A"));

            translator.translate(List.of(im("<p>a</p>", null)), "en");

            verify(gateway).translate(anyString(), eq("auto"), eq("en"));
        }

        @Test
        @DisplayName("segments are written back in order")
        void segmentOrderPreserved() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any())).thenReturn(translated("one|||SEG|||two|||SEG|||three"));

            TranslateMessageRequest a = im("<p>un</p>", "fr");
            TranslateMessageRequest b = im("<p>deux</p>", "fr");
            TranslateMessageRequest c = im("<p>trois</p>", "fr");
            translator.translate(List.of(a, b, c), "en");

            assertThat(a.getHtml()).isEqualTo("one");
            assertThat(b.getHtml()).isEqualTo("two");
            assertThat(c.getHtml()).isEqualTo("three");
        }

        @Test
        @DisplayName("a body containing the delimiter is sent on its own")
        void delimiterUnsafeIsolated() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenReturn(translated("SAFE"))
                    .thenReturn(translated("UNSAFE"));

            TranslateMessageRequest safe = im("<p>bonjour</p>", "fr");
            TranslateMessageRequest unsafe = im("<p>a|||SEG|||b</p>", "fr");
            translator.translate(List.of(safe, unsafe), "en");

            verify(gateway, times(2)).translate(anyString(), anyString(), anyString());
            assertThat(safe.getHtml()).isEqualTo("SAFE");
            assertThat(unsafe.getHtml()).isEqualTo("UNSAFE");
        }
    }

    // ================================================================== failures

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("406 marks the whole group unsupported and keeps originals")
        void sourceRejectedMarksGroup() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenReturn(new SystranOutcome.SourceRejected("rejected"));

            TranslateMessageRequest a = im("<p>un</p>", "fr");
            TranslateMessageRequest b = im("<p>deux</p>", "fr");
            translator.translate(List.of(a, b), "en");

            assertThat(List.of(a, b)).allMatch(m ->
                    m.getTranslationStatus() == MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE);
            assertThat(a.getHtml()).isEqualTo("<p>un</p>");
        }

        @Test
        @DisplayName("segment mismatch fails the group WITHOUT overwriting any body")
        void segmentMismatchDoesNotCorrupt() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(policy.resolveSource("hu", "en")).thenReturn(Optional.of("hu"));
            // fr group: 3 sent, 2 returned. hu group succeeds.
            when(gateway.translate(any(), eq("fr"), any())).thenReturn(translated("A|||SEG|||B"));
            when(gateway.translate(any(), eq("hu"), any())).thenReturn(translated("HU"));

            TranslateMessageRequest a = im("<p>un</p>", "fr");
            TranslateMessageRequest b = im("<p>deux</p>", "fr");
            TranslateMessageRequest c = im("<p>trois</p>", "fr");
            TranslateMessageRequest hu = im("<p>szia</p>", "hu");

            translator.translate(List.of(a, b, c, hu), "en");

            // This is the assertion that matters: no message got someone else's translation.
            assertThat(a.getHtml()).isEqualTo("<p>un</p>");
            assertThat(b.getHtml()).isEqualTo("<p>deux</p>");
            assertThat(c.getHtml()).isEqualTo("<p>trois</p>");
            assertThat(List.of(a, b, c)).allMatch(m ->
                    m.getTranslationStatus() == MessageTranslationStatus.FAILED_UPSTREAM);
            // The healthy group is unaffected.
            assertThat(hu.getHtml()).isEqualTo("HU");
            assertThat(hu.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
        }

        @Test
        @DisplayName("one group fails, another succeeds -> 200 with mixed statuses")
        void partialDegradeReturns200() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(policy.resolveSource("hu", "en")).thenReturn(Optional.of("hu"));
            when(gateway.translate(any(), eq("fr"), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));
            when(gateway.translate(any(), eq("hu"), any())).thenReturn(translated("HU"));

            TranslateMessageRequest fr = im("<p>un</p>", "fr");
            TranslateMessageRequest hu = im("<p>szia</p>", "hu");

            List<TranslateMessageRequest> result = translator.translate(List.of(fr, hu), "en");

            assertThat(result).hasSize(2);
            assertThat(fr.getTranslationStatus()).isEqualTo(MessageTranslationStatus.FAILED_UPSTREAM);
            assertThat(fr.getTranslationSkippedReason()).contains("SYSTRAN_API_ERROR");
            assertThat(hu.getTranslationStatus()).isEqualTo(MessageTranslationStatus.TRANSLATED);
        }

        @Test
        @DisplayName("every attempted group fails -> exception propagates for 502")
        void allGroupsFailPropagates() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));

            assertThatThrownBy(() -> translator.translate(List.of(im("<p>un</p>", "fr")), "en"))
                    .isInstanceOf(TranslationException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);
        }

        @Test
        @DisplayName("timeout on every group propagates SYSTRAN_TIMEOUT for 504")
        void allGroupsTimeoutPropagates() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_TIMEOUT, "timeout"));

            assertThatThrownBy(() -> translator.translate(List.of(im("<p>un</p>", "fr")), "en"))
                    .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_TIMEOUT);
        }

        @Test
        @DisplayName("a failing group alongside skipped messages still throws - skips are not successes")
        void skipsDoNotMaskFailure() {
            when(policy.resolveSource("fr", "en")).thenReturn(Optional.of("fr"));
            when(gateway.translate(any(), any(), any()))
                    .thenThrow(new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR, "500"));

            List<TranslateMessageRequest> messages = List.of(
                    message("<p>x</p>", "fr", "INFO"),   // skipped, not attempted
                    im("<p>un</p>", "fr"));              // attempted, fails

            assertThatThrownBy(() -> translator.translate(messages, "en"))
                    .isInstanceOf(TranslationException.class);
        }
    }
}
