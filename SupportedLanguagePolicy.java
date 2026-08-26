package com.citi.uno.items.translation.service.support;

import com.citi.uno.items.translation.client.SystranClient;
import com.citi.uno.items.translation.dto.SystranSupportedLanguagesResponse;
import com.citi.uno.items.translation.exception.TranslationErrorCode;
import com.citi.uno.items.translation.exception.TranslationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Answers "can Systran translate {@code source -> target}?" without hitting the network
 * on every call.
 *
 * <p>Before this class, {@code supportedSourceLanguages(target)} called
 * {@code GET /v1/supportedLanguages} once per formatted-view request and once per blob in
 * the simple view — an extra round-trip (and an extra failure mode) in front of every
 * translation. The pair list changes on Systran release cadence, not per request, so a
 * TTL cache is safe.
 *
 * <p>If a refresh fails but we still hold a previously-loaded snapshot, we serve the stale
 * one rather than failing the batch. Only a cold-start failure propagates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportedLanguagePolicy {

    private final SystranClient systranClient;

    @Value("${systran.supported-languages.ttl-seconds:900}")
    private long ttlSeconds;

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private record Snapshot(Map<String, Set<String>> sourcesByTarget, Instant loadedAt) {
        boolean isStale(Duration ttl) {
            return Instant.now().isAfter(loadedAt.plus(ttl));
        }
    }

    /**
     * Sources Systran accepts for the given target. Normalized, and containing both the
     * full code and its base form so BCP-47 inputs match base-level pairs.
     */
    public Set<String> sourcesFor(String target) {
        return snapshot().sourcesByTarget()
                .getOrDefault(LanguageCodes.normalize(target), Collections.emptySet());
    }

    public boolean supports(String source, String target) {
        if (LanguageCodes.isAuto(source)) {
            return true; // let Systran detect; a genuine mismatch comes back as 406
        }
        Set<String> supported = sourcesFor(target);
        String normalized = LanguageCodes.normalize(source);
        return supported.contains(normalized) || supported.contains(LanguageCodes.base(normalized));
    }

    private Snapshot snapshot() {
        Snapshot current = cache.get();
        if (current != null && !current.isStale(Duration.ofSeconds(ttlSeconds))) {
            return current;
        }
        try {
            Snapshot refreshed = load();
            cache.set(refreshed);
            return refreshed;
        } catch (RuntimeException ex) {
            if (current != null) {
                log.warn("supportedLanguages refresh failed; serving snapshot from {}.",
                        current.loadedAt(), ex);
                return current;
            }
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Unable to load supported language pairs from Systran.", ex);
        }
    }

    private Snapshot load() {
        SystranSupportedLanguagesResponse response = systranClient.supportedLanguages();
        Map<String, Set<String>> byTarget = new LinkedHashMap<>();

        if (response != null && response.getLanguagePairs() != null) {
            response.getLanguagePairs().forEach(pair -> {
                String target = LanguageCodes.normalize(pair.getTarget());
                String source = LanguageCodes.normalize(pair.getSource());
                Set<String> sources = byTarget.computeIfAbsent(target, k -> new HashSet<>());
                sources.add(source);
                sources.add(LanguageCodes.base(source));
            });
        }

        if (byTarget.isEmpty()) {
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran returned an empty supported language pair list.");
        }

        log.info("Loaded Systran supported languages: {} target(s).", byTarget.size());
        return new Snapshot(Map.copyOf(byTarget), Instant.now());
    }
}
