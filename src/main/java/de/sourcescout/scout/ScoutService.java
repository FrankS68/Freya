package de.sourcescout.scout;

import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.core.SearchHit;
import de.sourcescout.core.UrlNormalizer;
import de.sourcescout.evaluate.EvaluationService;
import de.sourcescout.portal.PortalPublisher;
import de.sourcescout.search.SearchProvider;
import de.sourcescout.store.ProcessedItem;
import de.sourcescout.store.ProcessedItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ScoutService {

    private static final Logger log = LoggerFactory.getLogger(ScoutService.class);

    private final ScoutProperties properties;
    private final List<SearchProvider> searchProviders;
    private final EvaluationService evaluationService;
    private final PortalPublisher portalPublisher;
    private final ProcessedItemStore store;

    public ScoutService(ScoutProperties properties,
                        List<SearchProvider> searchProviders,
                        EvaluationService evaluationService,
                        PortalPublisher portalPublisher,
                        ProcessedItemStore store) {
        this.properties = properties;
        this.searchProviders = searchProviders;
        this.evaluationService = evaluationService;
        this.portalPublisher = portalPublisher;
        this.store = store;
    }

    public void runOnce() {
        ScoutProperties.Search searchConfig = properties.getSearch();
        List<SearchProvider> active = searchProviders.stream()
                .filter(provider -> searchConfig.getProviders().contains(provider.id()))
                .toList();
        if (active.isEmpty()) {
            log.warn("Kein Suchanbieter aktiv (scout.search.providers={}) - Lauf wird uebersprungen.",
                    searchConfig.getProviders());
            return;
        }
        if (!portalPublisher.isConfigured()) {
            log.warn("Portal-API ist nicht konfiguriert (scout.portal.url) - Treffer werden nur geloggt.");
        }

        log.info("Scout-Lauf gestartet: {} Thema(ta), Anbieter: {}", searchConfig.getTopics().size(),
                active.stream().map(SearchProvider::id).toList());

        Set<String> seenInRun = new HashSet<>();
        List<SearchHit> candidates = new ArrayList<>();
        for (String topic : searchConfig.getTopics()) {
            for (SearchProvider provider : active) {
                try {
                    List<SearchHit> hits = provider.search(topic);
                    log.info("Anbieter '{}' lieferte {} Treffer fuer '{}'", provider.id(), hits.size(), topic);
                    for (SearchHit hit : hits) {
                        String key = UrlNormalizer.normalize(hit.url());
                        if (key.isBlank() || !seenInRun.add(key) || store.isProcessed(key)) {
                            continue;
                        }
                        candidates.add(hit);
                    }
                } catch (Exception ex) {
                    log.warn("Anbieter '{}' fehlgeschlagen fuer '{}': {}", provider.id(), topic, ex.getMessage());
                }
            }
        }
        if (candidates.isEmpty()) {
            log.info("Keine neuen Kandidaten gefunden.");
            return;
        }

        int maxCandidates = Math.min(properties.getEvaluation().getMaxCandidates(), candidates.size());
        List<EvaluatedItem> evaluated = evaluationService.evaluateAll(candidates.subList(0, maxCandidates));
        log.info("{} von {} Kandidaten bewertet.", evaluated.size(), candidates.size());

        List<EvaluatedItem> eligible = evaluated.stream()
                .filter(item -> item.relevance() >= properties.getEvaluation().getMinScore())
                .sorted(Comparator.comparingInt(EvaluatedItem::relevance).reversed())
                .limit(properties.getEvaluation().getMaxItemsPerRun())
                .toList();

        int published = 0;
        for (EvaluatedItem item : eligible) {
            if (!portalPublisher.isConfigured()) {
                log.info("Wuerde veroeffentlichen (Score {}): {} - {}", item.relevance(), item.hit().title(),
                        item.hit().url());
                continue;
            }
            try {
                portalPublisher.publish(item);
                store.markProcessed(new ProcessedItem(
                        UrlNormalizer.normalize(item.hit().url()),
                        item.hit().title(),
                        item.relevance(),
                        item.hit().provider(),
                        Instant.now()));
                published++;
                log.info("Veroeffentlicht (Score {}): {} - {}", item.relevance(), item.hit().title(), item.hit().url());
            } catch (Exception ex) {
                log.error("Veroeffentlichen fehlgeschlagen fuer {}: {}", item.hit().url(), ex.getMessage());
            }
        }
        log.info("Scout-Lauf beendet: {} Kandidaten, {} bewertet, {} veroeffentlicht.",
                candidates.size(), evaluated.size(), published);
    }
}
