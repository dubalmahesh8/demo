package com.citi.uno.items.translation.dto;

/**
 * Implemented by both view DTOs so the two translators can share the code that writes an
 * outcome. Deliberately tiny: status and note only. The translators still know how to reach
 * each DTO's own text field ({@code html} vs {@code message}), because that part genuinely
 * differs and forcing it through a common accessor would obscure more than it saves.
 *
 * <p>NOTE: this requires renaming {@code TranslateMessageRequest.translationSkippedReason}
 * to {@code translationNote}. The old name was already inaccurate - the field now carries
 * upstream-failure text too, not only skip reasons.
 */
public interface TranslatableItem {

    MessageTranslationStatus getTranslationStatus();

    void setTranslationStatus(MessageTranslationStatus status);

    void setTranslationNote(String note);
}
