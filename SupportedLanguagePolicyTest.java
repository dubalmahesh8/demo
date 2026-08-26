package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.client.SystranClient;
import com.citi.uno.items.translation.dto.SystranSupportedLanguagesResponse;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportedLanguagePolicyTest {

    @Mock
    private SystranClient systranClient;

    @InjectMocks
    private SupportedLanguagePolicy policy;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(policy, "ttlSeconds", 900L);
    }

    /** Mirrors the real payload: pairs are nested under body, published as base codes. */
    private SystranSupportedLanguagesResponse response(String... sourceTargetPairs) {
        List<SystranSupportedLanguagesResponse.LanguagePair> pairs = new ArrayList<>();
        for (int i = 0; i < sourceTargetPairs.length; i += 2) {
            SystranSupportedLanguagesResponse.LanguagePair pair =
                    new SystranSupportedLanguagesResponse.LanguagePair();
            pair.setSource(sourceTargetPairs[i]);
            pair.setTarget(sourceTargetPairs[i + 1]);
            pairs.add(pair);
        }
        SystranSupportedLanguagesResponse.Body body = new SystranSupportedLanguagesResponse.Body();
        body.setLanguagePairs(pairs);

        SystranSupportedLanguagesResponse response = new SystranSupportedLanguagesResponse();
        response.setBody(body);
        return response;
    }

    private SystranSupportedLanguagesResponse defaultPairs() {
        return response("fr", "en", "hu", "en", "zh", "en", "auto", "en", "en", "ja");
    }

    // ------------------------------------------------------------------ resolution

    @Test
    @DisplayName("exact published code resolves to itself")
    void resolvesExact() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        assertThat(policy.resolveSource("fr", "en")).contains("fr");
    }

    @Test
    @DisplayName("BCP-47 tag resolves to the published base code - the 406 bug")
    void resolvesBcp47ToBase() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        // Must return "zh", never "zh-Hans" - Systran only knows the base code.
        assertThat(policy.resolveSource("zh-Hans", "en")).contains("zh");
        assertThat(policy.resolveSource("ZH-HANS", "en")).contains("zh");
    }

    @Test
    @DisplayName("auto short-circuits without consulting the pair list")
    void autoShortCircuits() {
        assertThat(policy.resolveSource("auto", "en")).contains("auto");
        assertThat(policy.resolveSource(null, "en")).contains("auto");   // blank normalizes to auto

        verifyNoMoreInteractions(systranClient);   // no network call at all
    }

    @Test
    @DisplayName("unsupported pair resolves to empty")
    void unsupportedPair() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        assertThat(policy.resolveSource("th", "en")).isEmpty();
        assertThat(policy.resolveSource("fr", "ja")).isEmpty();   // fr->en exists, fr->ja does not
    }

    @Test
    @DisplayName("target is matched case-insensitively")
    void targetNormalized() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        assertThat(policy.resolveSource("fr", "EN")).contains("fr");
    }

    @Test
    @DisplayName("unknown target yields an empty source set, not an exception")
    void unknownTarget() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        assertThat(policy.sourcesFor("xx")).isEmpty();
        assertThat(policy.resolveSource("fr", "xx")).isEmpty();
    }

    @Test
    @DisplayName("snapshot holds only published codes - base codes are not injected into the set")
    void snapshotNotWidened() {
        when(systranClient.supportedLanguages()).thenReturn(response("zh", "en"));

        // If zh-hans leaked into the set, resolveSource could hand back an unpublishable code.
        assertThat(policy.sourcesFor("en")).containsExactly("zh");
    }

    // ------------------------------------------------------------------ caching

    @Test
    @DisplayName("pair list is fetched once and reused across calls")
    void cachesAcrossCalls() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        for (int i = 0; i < 25; i++) {
            policy.resolveSource("fr", "en");
        }

        verify(systranClient, times(1)).supportedLanguages();
    }

    @Test
    @DisplayName("expired snapshot triggers a refresh")
    void refreshesAfterTtl() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());
        ReflectionTestUtils.setField(policy, "ttlSeconds", 0L);   // everything is instantly stale

        policy.resolveSource("fr", "en");
        policy.resolveSource("fr", "en");

        verify(systranClient, times(2)).supportedLanguages();
    }

    @Test
    @DisplayName("refresh failure serves the previous snapshot rather than failing the batch")
    void servesStaleOnRefreshFailure() {
        when(systranClient.supportedLanguages())
                .thenReturn(defaultPairs())
                .thenThrow(new RuntimeException("Systran unreachable"));
        ReflectionTestUtils.setField(policy, "ttlSeconds", 0L);

        policy.resolveSource("fr", "en");                          // warms the cache
        assertThat(policy.resolveSource("fr", "en")).contains("fr");   // refresh fails, stale served

        verify(systranClient, times(2)).supportedLanguages();
    }

    // ------------------------------------------------------------------ failure modes

    @Test
    @DisplayName("cold-start failure propagates as SYSTRAN_API_ERROR")
    void coldStartFailure() {
        when(systranClient.supportedLanguages()).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> policy.resolveSource("fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.SYSTRAN_API_ERROR);
    }

    @Test
    @DisplayName("missing body wrapper is rejected loudly - the DTO-shape bug")
    void missingBodyWrapper() {
        SystranSupportedLanguagesResponse malformed = new SystranSupportedLanguagesResponse();
        // body left null, as happened when the DTO declared languagePairs at the top level
        when(systranClient.supportedLanguages()).thenReturn(malformed);

        assertThatThrownBy(() -> policy.resolveSource("fr", "en"))
                .isInstanceOf(TranslationException.class)
                .hasMessageContaining("empty supported language pair list");
    }

    @Test
    @DisplayName("null response and empty pair list are both rejected")
    void emptyPayloads() {
        when(systranClient.supportedLanguages()).thenReturn(null);
        assertThatThrownBy(() -> policy.resolveSource("fr", "en"))
                .isInstanceOf(TranslationException.class);
    }

    @Test
    @DisplayName("cold-start failure is not cached - a later call can still succeed")
    void failureIsNotCached() {
        when(systranClient.supportedLanguages())
                .thenThrow(new RuntimeException("blip"))
                .thenReturn(defaultPairs());

        assertThatThrownBy(() -> policy.resolveSource("fr", "en"))
                .isInstanceOf(TranslationException.class);
        assertThat(policy.resolveSource("fr", "en")).contains("fr");
    }

    @Test
    @DisplayName("duplicate pairs collapse")
    void duplicatePairs() {
        when(systranClient.supportedLanguages())
                .thenReturn(response("fr", "en", "fr", "en", "FR", "EN"));

        assertThat(policy.sourcesFor("en")).containsExactly("fr");
    }

    @Test
    @DisplayName("returned source set is immutable")
    void sourcesAreImmutable() {
        when(systranClient.supportedLanguages()).thenReturn(defaultPairs());

        assertThatThrownBy(() -> policy.sourcesFor("en").add("xx"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
