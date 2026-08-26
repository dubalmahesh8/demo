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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a caller-supplied language code to the code Systran will actually accept.
 *
 * <p>Systran publishes its pairs as plain ISO codes ({@code en}, {@code ja}, {@code zh}),
 * but the UI sends BCP-47 tags such as {@code zh-Hans} or {@code pt-BR}. Answering a plain
 * "is this supported?" is not enough: matching on the base code and then sending the caller's
 * original tag gets a 406, because the pre-check and the outbound call disagree about which
 * string is in play.
 *
 * <p>{@link #resolveSource} therefore returns the <em>publishable</em> code, and callers must
 * send exactly what it hands back. The snapshot stores only what Systran published — nothing
 * is silently widened — and the base-code fallback happens at lookup time where the result is
 * a concrete code rather than a boolean.
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

    /** Exactly the source codes Systran published for this target. Not widened. */
    public Set<String> sourcesFor(String target) {
        return snapshot().sourcesByTarget()
                .getOrDefault(LanguageCodes.normalize(target), Collections.emptySet());
    }

    /**
     * The code to send to Systran for this source, or empty if the pair is unsupported.
     *
     * <p>{@code resolveSource("zh-Hans", "en")} returns {@code "zh"} when Systran publishes
     * {@code zh -> en}. Send the returned value, never the argument.
     */
    public Optional<String> resolveSource(String source, String target) {
        String normalized = LanguageCodes.normalize(source);
        if (LanguageCodes.isAuto(normalized)) {
            // Always allowed through; if Systran has no auto pair for this target it answers 406,
            // which the gateway turns into a SourceRejected outcome.
            return Optional.of(LanguageCodes.AUTO);
        }

        Set<String> published = sourcesFor(target);
        if (published.contains(normalized)) {
            return Optional.of(normalized);
        }

        // BCP-47 tag against a base-code pair list: zh-Hans -> zh, pt-BR -> pt.
        String base = LanguageCodes.base(normalized);
        if (published.contains(base)) {
            log.debug("Resolved source '{}' to Systran code '{}' for target '{}'.",
                    normalized, base, target);
            return Optional.of(base);
        }
        return Optional.empty();
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

        // Systran wraps the list in "body", exactly like the textTranslation response.
        if (response != null
                && response.getBody() != null
                && response.getBody().getLanguagePairs() != null) {

            response.getBody().getLanguagePairs().forEach(pair ->
                    byTarget.computeIfAbsent(LanguageCodes.normalize(pair.getTarget()),
                                    k -> new LinkedHashSet<>())
                            .add(LanguageCodes.normalize(pair.getSource())));
        }

        if (byTarget.isEmpty()) {
            throw new TranslationException(TranslationErrorCode.SYSTRAN_API_ERROR,
                    "Systran returned an empty supported language pair list.");
        }

        log.info("Loaded Systran supported languages: {} target(s).", byTarget.size());
        return new Snapshot(Map.copyOf(byTarget), Instant.now());
    }
}
