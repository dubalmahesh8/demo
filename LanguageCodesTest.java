package com.citi.uno.items.translation.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCodesTest {

    @ParameterizedTest
    @CsvSource({
            "fr, fr",
            "FR, fr",
            "'  fr  ', fr",
            "zh-Hans, zh-hans",
            "pt-BR, pt-br",
            "AUTO, auto"
    })
    @DisplayName("normalize lower-cases and trims")
    void normalize(String input, String expected) {
        assertThat(LanguageCodes.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("normalize collapses null/blank to auto")
    void normalizeBlank(String input) {
        assertThat(LanguageCodes.normalize(input)).isEqualTo(LanguageCodes.AUTO);
    }

    @ParameterizedTest
    @CsvSource({
            "zh-Hans, zh",
            "pt-BR, pt",
            "fr, fr",
            "zho, zho",
            "auto, auto"
    })
    @DisplayName("base strips script/region subtags")
    void base(String input, String expected) {
        assertThat(LanguageCodes.base(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("base handles a trailing dash without exploding")
    void baseEdgeCases() {
        assertThat(LanguageCodes.base("zh-")).isEqualTo("zh");
        assertThat(LanguageCodes.base("-zh")).isEmpty();   // leading dash - degenerate input
    }

    @ParameterizedTest
    @CsvSource({
            "zh-Hans, zh, true",
            "zh, zh-Hant, true",
            "pt-BR, pt-PT, true",
            "fr, en, false",
            "zh, ja, false"
    })
    @DisplayName("sameLanguage compares base codes only")
    void sameLanguage(String a, String b, boolean expected) {
        assertThat(LanguageCodes.sameLanguage(a, b)).isEqualTo(expected);
    }

    @Test
    @DisplayName("isAuto is true for blank input, since blank normalizes to auto")
    void isAuto() {
        assertThat(LanguageCodes.isAuto("auto")).isTrue();
        assertThat(LanguageCodes.isAuto("AUTO")).isTrue();
        assertThat(LanguageCodes.isAuto(null)).isTrue();
        assertThat(LanguageCodes.isAuto("")).isTrue();
        assertThat(LanguageCodes.isAuto("fr")).isFalse();
    }
}
