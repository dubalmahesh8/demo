package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.dto.MessageTranslationStatus;
import com.citi.uno.items.translation.exception.TranslationException;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counts one request's work and decides whether it succeeded overall.
 *
 * <p>Two independent tallies, because they answer different questions:
 * <ul>
 *   <li><b>Items</b> - every message or blob, by final status. Purely for the log line, so
 *       "what happened to this request?" is answerable from one entry.</li>
 *   <li><b>Units of work</b> - a language group in the formatted view, a blob in the simple
 *       view. This is what the 502/504 rule counts.</li>
 * </ul>
 *
 * <p>A partially translated unit produced value, so it counts as attempted-and-not-failed.
 * The formatted view simply never reports one, since a group is a single call.
 */
@Slf4j
public class RequestSummary {

    private final String viewName;
    private final String unitName;

    private final Map<MessageTranslationStatus, Integer> itemCounts =
            new EnumMap<>(MessageTranslationStatus.class);

    private int unitsAttempted;
    private int unitsFailed;
    private int unitsPartial;
    private TranslationException lastFailure;

    /**
     * @param viewName e.g. "Formatted view"
     * @param unitName what a unit of work is called in this view, e.g. "group" or "blob"
     */
    public RequestSummary(String viewName, String unitName) {
        this.viewName = viewName;
        this.unitName = unitName;
    }

    /** Every item, once, with the status it ended on. Narrative only. */
    public void countItem(MessageTranslationStatus status) {
        itemCounts.merge(status, 1, Integer::sum);
    }

    public void recordUnitSucceeded(MessageTranslationStatus finalStatus) {
        unitsAttempted++;
        if (finalStatus == MessageTranslationStatus.PARTIALLY_TRANSLATED) {
            unitsPartial++;
        }
    }

    public void recordUnitFailed(TranslationException ex) {
        unitsAttempted++;
        unitsFailed++;
        lastFailure = ex;
    }

    /** 200 would claim the content is untranslatable when really Systran is unwell. */
    public void failIfNothingSucceeded() {
        // unitsAttempted > 0: a request where everything was skipped tried nothing, so nothing failed.
        if (unitsAttempted > 0 && unitsFailed == unitsAttempted) {
            throw lastFailure;
        }
    }

    /** One line per request, carrying the whole shape of what happened. */
    public void log(String target, int itemsIn) {
        String detail = String.format(
                "target: %s, in: %d, %ss attempted: %d (failed: %d, partial: %d), outcome: %s",
                target, itemsIn, unitName, unitsAttempted, unitsFailed, unitsPartial, itemCounts);

        if (unitsFailed > 0 || unitsPartial > 0) {
            log.warn("{} degraded - {}", viewName, detail);
        } else {
            log.info("{} complete - {}", viewName, detail);
        }
    }
}
