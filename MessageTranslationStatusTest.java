package com.citi.uno.items.translation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retryable/permanent split is the contract callers depend on, so it is asserted
 * explicitly rather than left implicit in the enum declaration.
 */
class MessageTranslationStatusTest {

    @Test
    @DisplayName("only upstream failures are retryable")
    void retryableSet() {
        assertThat(Arrays.stream(MessageTranslationStatus.values())
                .filter(MessageTranslationStatus::isRetryable)
                .toList())
                .containsExactlyInAnyOrder(
                        MessageTranslationStatus.FAILED_UPSTREAM,
                        MessageTranslationStatus.PARTIALLY_TRANSLATED);
    }

    @Test
    @DisplayName("only translated and partial report translated=true")
    void translatedSet() {
        assertThat(Arrays.stream(MessageTranslationStatus.values())
                .filter(MessageTranslationStatus::isTranslated)
                .toList())
                .containsExactlyInAnyOrder(
                        MessageTranslationStatus.TRANSLATED,
                        MessageTranslationStatus.PARTIALLY_TRANSLATED);
    }

    @ParameterizedTest
    @EnumSource(value = MessageTranslationStatus.class, names = "SKIPPED_.*", mode = EnumSource.Mode.MATCH_ALL)
    @DisplayName("every SKIPPED_ outcome is permanent - retrying will not change it")
    void skippedIsNeverRetryable(MessageTranslationStatus status) {
        assertThat(status.isRetryable()).isFalse();
        assertThat(status.isTranslated()).isFalse();
    }

    @Test
    @DisplayName("PARTIALLY_TRANSLATED is the only status that is both")
    void partialIsBoth() {
        assertThat(MessageTranslationStatus.PARTIALLY_TRANSLATED.isTranslated()).isTrue();
        assertThat(MessageTranslationStatus.PARTIALLY_TRANSLATED.isRetryable()).isTrue();
    }
}
