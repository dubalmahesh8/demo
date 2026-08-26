package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentCodecTest {

    private final SegmentCodec codec = new SegmentCodec();

    @Test
    @DisplayName("round-trips a normal batch")
    void roundTrip() {
        List<String> parts = List.of("<p>bonjour</p>", "<p>merci</p>", "<p>salut</p>");
        String joined = codec.join(parts);

        assertThat(codec.split(joined, 3)).containsExactlyElementsOf(parts);
    }

    @Test
    @DisplayName("tolerates Systran dropping a pipe from either end of the delimiter")
    void tolerantSplit() {
        assertThat(codec.split("a|||SEG||b", 2)).containsExactly("a", "b");   // trailing pipe dropped
        assertThat(codec.split("a||SEG|||b", 2)).containsExactly("a", "b");   // leading pipe dropped
        assertThat(codec.split("a||SEG||b", 2)).containsExactly("a", "b");    // one from each
    }

    @Test
    @DisplayName("absorbs whitespace Systran adds around the delimiter")
    void whitespaceAroundDelimiter() {
        assertThat(codec.split("a  |||SEG|||\n b", 2)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("preserves empty segments rather than collapsing them")
    void emptySegmentsPreserved() {
        // split(-1) limit matters: a trailing empty body must still occupy its slot.
        assertThat(codec.split("a|||SEG||||||SEG|||c", 3)).containsExactly("a", "", "c");
        assertThat(codec.split("a|||SEG|||", 2)).containsExactly("a", "");
    }

    @Test
    @DisplayName("single segment needs no delimiter")
    void singleSegment() {
        assertThat(codec.join(List.of("only"))).isEqualTo("only");
        assertThat(codec.split("only", 1)).containsExactly("only");
    }

    @Test
    @DisplayName("too few segments is rejected - this is the data-corruption guard")
    void tooFewSegments() {
        assertThatThrownBy(() -> codec.split("a|||SEG|||b", 3))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH)
                .hasMessageContaining("sent 3")
                .hasMessageContaining("received 2");
    }

    @Test
    @DisplayName("too many segments is rejected")
    void tooManySegments() {
        assertThatThrownBy(() -> codec.split("a|||SEG|||b|||SEG|||c", 2))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH);
    }

    @Test
    @DisplayName("null response is rejected, not NPE'd")
    void nullRaw() {
        assertThatThrownBy(() -> codec.split(null, 2))
                .isInstanceOf(TranslationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH);
    }

    @Test
    @DisplayName("isSafeToBatch flags bodies containing the delimiter")
    void isSafeToBatch() {
        assertThat(codec.isSafeToBatch("<p>hello</p>")).isTrue();
        assertThat(codec.isSafeToBatch(null)).isTrue();
        assertThat(codec.isSafeToBatch("")).isTrue();
        assertThat(codec.isSafeToBatch("before|||SEG|||after")).isFalse();
        assertThat(codec.isSafeToBatch("before||SEG||after")).isFalse();   // tolerant form too
    }
}
