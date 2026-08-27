package com.citi.uno.items.translation.dto;

/**
 * Per-item outcome. Replaces the {@code boolean translated} + free-text reason pair, which
 * forced callers to string-match on the reason to tell one outcome from another.
 *
 * <p>{@link #isTranslated()} backs the derived {@code translated} field on both view DTOs.
 * Everything else that matters is the constant itself: the note text and any UI treatment
 * branch on the status, not on a helper flag.
 */
public enum MessageTranslationStatus {

    /** Systran returned a translation and it was written back. */
    TRANSLATED(true),

    /**
     * Simple view only. Some source languages were translated and others failed upstream, so
     * the text is translated but not fully.
     */
    PARTIALLY_TRANSLATED(true),

    /** subType = INFO. Never translated by design. */
    SKIPPED_INFO_MESSAGE(false),

    /** Body was blank, or contained no letters after tag stripping. */
    SKIPPED_NO_TRANSLATABLE_CONTENT(false),

    /** Source already equals the requested target. */
    SKIPPED_ALREADY_TARGET_LANGUAGE(false),

    /** Systran does not offer this source -> target pair, by pre-check or by 406 at call time. */
    SKIPPED_UNSUPPORTED_SOURCE(false),

    /** Systran was reachable-but-broken, timed out, or returned a malformed batch. */
    FAILED_UPSTREAM(false);

    private final boolean translated;

    MessageTranslationStatus(boolean translated) {
        this.translated = translated;
    }

    /** Backing value for the legacy {@code translated} flag, kept for wire compatibility. */
    public boolean isTranslated() {
        return translated;
    }
}
