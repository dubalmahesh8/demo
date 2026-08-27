package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Packs several message bodies into one Systran payload and unpacks the reply.
 *
 * <p>This is the highest-risk code in the formatted view. If Systran merges, drops or
 * duplicates a segment and we re-map positionally anyway, we write the wrong translation
 * into the wrong message — silent data corruption, which is far worse than a failed group.
 * {@link #unpack} therefore refuses to return a mis-sized result.
 */
@Component
public class MessageBatcher {

    public static final String DELIMITER = "|||SEG|||";

    /**
     * Possessive quantifiers prevent backtracking (SonarQube ReDoS rule).
     * {@code \|{2,3}} on both sides tolerates Systran dropping a pipe from either end.
     * Compiled once — {@code String.split} recompiled this on every call.
     */
    private static final Pattern SPLIT = Pattern.compile("\\s*+\\|{2,3}SEG\\|{2,3}\\s*+");

    public String pack(List<String> segments) {
        return String.join(DELIMITER, segments);
    }

    /**
     * @param expected number of segments that were sent
     * @throws TranslationException with TRANSLATION_SEGMENT_MISMATCH if the count differs
     */
    public List<String> unpack(String raw, int expected) {
        if (raw == null) {
            throw new TranslationException(TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH,
                    "Systran returned no text for a batch of " + expected + " segment(s).");
        }
        List<String> parts = Arrays.asList(SPLIT.split(raw, -1));
        if (parts.size() != expected) {
            throw new TranslationException(TranslationErrorCode.TRANSLATION_SEGMENT_MISMATCH,
                    "Segment count mismatch: sent " + expected + ", received " + parts.size()
                            + ". Refusing to map translations positionally.");
        }
        return parts;
    }

    /**
     * Guards against a message body that already contains the delimiter, which would
     * desynchronise the split. Callers should route such messages through a single-message
     * call rather than a batch.
     */
    public boolean isSafeToBatch(String html) {
        return html == null || !SPLIT.matcher(html).find();
    }
}
