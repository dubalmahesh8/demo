package com.citi.uno.items.translation.client;

/**
 * Result of one Systran call.
 *
 * <p>A 406 means "I don't do that language pair" — a routine, expected, business-level
 * answer, not a failure. Modelling it as an exception forced an identical
 * {@code catch (TranslationException ex) { if (ex.getHttpStatus() == NOT_ACCEPTABLE) ... }}
 * block into both views, where it was easy to get subtly out of step. As a value, the
 * compiler makes callers handle it.
 *
 * <p>Genuine failures (5xx, timeout, unreachable) still throw.
 */
public sealed interface SystranOutcome {

    /** Systran translated the payload. {@code text} is already meta-stripped. */
    record Translated(String text, String detectedSource) implements SystranOutcome {
    }

    /** Systran declined the source -> target pair (HTTP 406). Original text should be kept. */
    record SourceRejected(String detail) implements SystranOutcome {
    }
}
