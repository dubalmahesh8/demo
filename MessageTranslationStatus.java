package com.citi.uno.items.translation.dto;

/**
 * Per-message outcome. Replaces the {@code boolean translated} + free-text reason pair,
 * which forced callers to string-match on {@code translationSkippedReason}.
 *
 * <p>The critical distinction is {@link #isRetryable()}: a caller that persists or caches
 * our output must not treat FAILED_UPSTREAM the same as SKIPPED_UNSUPPORTED_SOURCE.
 * The first will succeed on retry; the second never will.
 */
public enum MessageTranslationStatus {

    /** Systran returned a translation and it was written back to the message. */
    TRANSLATED(true, false),

    /** subType = INFO. Never translated by design. */
    SKIPPED_INFO_MESSAGE(false, false),

    /** html was blank, or contained no letters after tag stripping. */
    SKIPPED_NO_TRANSLATABLE_CONTENT(false, false),

    /** detectedLanguage already equals the requested target. */
    SKIPPED_ALREADY_TARGET_LANGUAGE(false, false),

    /** Systran does not offer this source -> target pair (pre-check, or a 406 at call time). */
    SKIPPED_UNSUPPORTED_SOURCE(false, false),

    /**
     * Simple view only. Some source languages were translated and others failed upstream, so the
     * text is translated but not fully. Both translated and retryable: the caller has usable
     * content now, and a retry would complete it.
     */
    PARTIALLY_TRANSLATED(true, true),

    /** Systran was reachable-but-broken, timed out, or returned a malformed batch. Retry may succeed. */
    FAILED_UPSTREAM(false, true);

    private final boolean translated;
    private final boolean retryable;

    MessageTranslationStatus(boolean translated, boolean retryable) {
        this.translated = translated;
        this.retryable = retryable;
    }

    /** Backing value for the legacy {@code translated} flag, kept for wire compatibility. */
    public boolean isTranslated() {
        return translated;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
