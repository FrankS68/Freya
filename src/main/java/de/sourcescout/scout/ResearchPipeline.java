package de.sourcescout.scout;

import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.core.SearchHit;
import de.sourcescout.core.UrlNormalizer;
import de.sourcescout.evaluate.EvaluationService;
import de.sourcescout.search.SearchProvider;
import de.sourcescout.store.ProcessedItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class ResearchPipeline {

    private static final Logger log = LoggerFactory.getLogger(ResearchPipeline.class);

    private final ScoutProperties properties;
    private final List<SearchProvider> searchProviders;
    private final EvaluationService evaluationService;
    private final ProcessedItemStore store;

    public ResearchPipeline(ScoutProperties properties,
                            List<SearchProvider> searchProviders,
                            EvaluationService evaluationService,
                            ProcessedItemStore store) {
        this.properties = properties;
        this.searchProviders = searchProviders;
        this.evaluationService = evaluationService;
        this.store = store;
    }

    public List<EvaluatedItem> researchAndRank(String topic, Set<String> seenInRun) {
        List<SearchProvider> active = searchProviders.stream()
                .filter(provider -> properties.getSearch().getProviders().contains(provider.id()))
                .toList();
        if (active.isEmpty()) {
            log.warn("Kein Suchanbieter aktiv (scout.search.providers={}).", properties.getSearch().getProviders());
            return List.of();
        }
        List<SearchHit> candidates = new ArrayList<>();
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
        if (candidates.isEmpty()) {
            return List.of();
        }
        int maxCandidates = Math.min(properties.getEvaluation().getMaxCandidates(), candidates.size());
        List<EvaluatedItem> evaluated = evaluationService.evaluateAll(candidates.subList(0, maxCandidates));
        log.info("{} von {} Kandidaten bewertet fuer '{}'.", evaluated.size(), candidates.size(), topic);
        return evaluated.stream()
                .filter(item -> item.relevance() >= properties.getEvaluation().getMinScore())
                .sorted(Comparator.comparingInt(EvaluatedItem::relevance).reversed())
                .limit(properties.getEvaluation().getMaxItemsPerRun())
                .toList();
    }
}
