package com.citi.uno.items.translation.service.support;

import org.apache.commons.lang3.StringUtils;

/**
 * Central handling of language codes.
 *
 * <p>Previously every comparison in TranslationService did its own
 * {@code toLowerCase()} / {@code equalsIgnoreCase()} dance, which meant
 * {@code zh-Hans} and {@code zh} were treated as unrelated even though Systran's
 * supportedLanguages pairs are usually published at the base-code level.
 */
public final class LanguageCodes {

    public static final String AUTO = "auto";

    private LanguageCodes() {
    }

    /** Lower-cased, trimmed. Null/blank collapses to {@link #AUTO}. */
    public static String normalize(String code) {
        return StringUtils.isBlank(code) ? AUTO : code.trim().toLowerCase();
    }

    /** {@code zh-hans -> zh}, {@code pt-br -> pt}, {@code fr -> fr}. */
    public static String base(String code) {
        String normalized = normalize(code);
        int dash = normalized.indexOf('-');
        return dash < 0 ? normalized : normalized.substring(0, dash);
    }

    public static boolean isAuto(String code) {
        return AUTO.equals(normalize(code));
    }

    /**
     * True when two codes name the same language, ignoring script/region subtags.
     * Used to decide "this message is already in the target language".
     */
    public static boolean sameLanguage(String a, String b) {
        return base(a).equals(base(b));
    }
}
