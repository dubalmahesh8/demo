package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.dto.TranslatableItem;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

/**
 * The parts of "deciding an outcome" that are identical in both views: writing a status,
 * phrasing a reason, and judging whether a body is worth sending at all.
 *
 * <p>These lived twice before. Keeping the wording in one place also means a caller matching
 * on the note text sees the same string from both endpoints.
 */
public final class TranslationOutcomes {

    private TranslationOutcomes() {
    }

    /** Single write point for an item's outcome. */
    public static void record(TranslatableItem item, MessageTranslationStatus status, String note) {
        item.setTranslationStatus(status);
        item.setTranslationNote(note);
    }

    public static void markTranslated(TranslatableItem item) {
        record(item, MessageTranslationStatus.TRANSLATED, null);
    }

    public static void markUnsupportedSource(TranslatableItem item, String callerCode, String target) {
        record(item, MessageTranslationStatus.SKIPPED_UNSUPPORTED_SOURCE,
                String.format("Source language '%s' is not supported for target '%s'.",
                        callerCode, target));
    }

    /** Upstream trouble that a retry might fix. The original text is left in place. */
    public static void markFailedUpstream(TranslatableItem item, TranslationException ex) {
        record(item, MessageTranslationStatus.FAILED_UPSTREAM,
                "Translation temporarily unavailable" + suffix(ex) + "; original retained.");
    }

    /** Simple view only: some languages were translated before upstream trouble stopped the rest. */
    public static void markPartiallyTranslated(TranslatableItem item, TranslationException ex) {
        record(item, MessageTranslationStatus.PARTIALLY_TRANSLATED,
                "Some source languages were translated; the rest failed upstream"
                        + suffix(ex) + ". Retry to complete.");
    }

    private static String suffix(TranslationException ex) {
        TranslationErrorCode code = ex.getErrorCode();
        return code == null ? "" : " (" + code.name() + ")";
    }

    /** False for blank bodies and for markup, digits or punctuation with no letters. */
    public static boolean hasTranslatableContent(String html) {
        if (StringUtils.isBlank(html)) {
            return false;
        }
        return Jsoup.parse(html).text().codePoints().anyMatch(Character::isLetter);
    }
}
