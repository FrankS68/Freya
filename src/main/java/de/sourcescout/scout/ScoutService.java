package de.sourcescout.scout;

import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.core.UrlNormalizer;
import de.sourcescout.log.MistralLogReporter;
import de.sourcescout.log.RunLogCollector;
import de.sourcescout.portal.DocsMapping;
import de.sourcescout.portal.PortalDocsService;
import de.sourcescout.portal.PortalPublisher;
import de.sourcescout.portal.PortalTopicsService;
import de.sourcescout.portal.QuestionResearchService;
import de.sourcescout.store.ProcessedItem;
import de.sourcescout.store.ProcessedItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ScoutService {

    private static final Logger log = LoggerFactory.getLogger(ScoutService.class);

    private final ScoutProperties properties;
    private final SelfUpdateService selfUpdateService;
    private final ResearchPipeline pipeline;
    private final PortalDocsService portalDocsService;
    private final QuestionResearchService questionResearchService;
    private final PortalPublisher portalPublisher;
    private final ProcessedItemStore store;
    private final MistralLogReporter logReporter;
    private final PortalTopicsService portalTopicsService;

    public ScoutService(ScoutProperties properties,
                        SelfUpdateService selfUpdateService,
                        ResearchPipeline pipeline,
                        PortalDocsService portalDocsService,
                        QuestionResearchService questionResearchService,
                        PortalPublisher portalPublisher,
                        ProcessedItemStore store,
                        MistralLogReporter logReporter,
                        PortalTopicsService portalTopicsService) {
        this.properties = properties;
        this.selfUpdateService = selfUpdateService;
        this.pipeline = pipeline;
        this.portalDocsService = portalDocsService;
        this.questionResearchService = questionResearchService;
        this.portalPublisher = portalPublisher;
        this.store = store;
        this.logReporter = logReporter;
        this.portalTopicsService = portalTopicsService;
        RunLogCollector.attach();
    }

    public void runOnce() {
        try {
            if (selfUpdateService.checkAndApply()) {
                log.info("Update installiert - dieser Lauf endet, der Dienst startet mit der neuen Version neu.");
                return;
            }
        } catch (Exception ex) {
            log.warn("Update-Pruefung fehlgeschlagen: {}", ex.getMessage());
        }

        RunLogCollector.startRun();
        try {
            pipeline.beginRun();
            Optional<DocsMapping> mapping = Optional.empty();
            try {
                mapping = portalDocsService.checkForChanges();
            } catch (Exception ex) {
                log.warn("Doku-Pruefung fehlgeschlagen: {}", ex.getMessage());
            }
            try {
                mapping.filter(DocsMapping::hasQuestionsEndpoint)
                        .ifPresent(questionResearchService::researchPending);
            } catch (Exception ex) {
                log.warn("Fragen-Recherche fehlgeschlagen: {}", ex.getMessage());
            }

            Set<String> seenInRun = new HashSet<>();
            if (!portalPublisher.isConfigured()) {
                log.warn("Portal-API ist nicht konfiguriert (scout.portal.url) - Treffer werden nur geloggt.");
            }
            List<String> topics = topicsForRun(mapping);
            log.info("Scout-Lauf gestartet: {} Thema(ta)", topics.size());

            int published = 0;
            for (String topic : topics) {
                List<EvaluatedItem> eligible = pipeline.researchAndRank(topic, seenInRun);
                published += publish(eligible);
            }
            log.info("Scout-Lauf beendet: {} veroeffentlicht.", published);
        } finally {
            List<String> lines = RunLogCollector.snapshotAndStop();
            int keep = Math.max(10, properties.getLogReport().getTailLines());
            if (lines.size() > keep) {
                lines = lines.subList(lines.size() - keep, lines.size());
            }
            logReporter.reportRun(lines);
        }
    }

    private List<String> topicsForRun(Optional<DocsMapping> mapping) {
        if (mapping.filter(DocsMapping::hasTopicsEndpoint).isPresent()) {
            try {
                List<String> portalTopics = portalTopicsService.fetchTopics(
                        mapping.filter(DocsMapping::hasTopicsEndpoint).get());
                if (!portalTopics.isEmpty()) {
                    log.info("{} Thema(ta) aus der Portal-API gezogen.", portalTopics.size());
                    return portalTopics;
                }
                List<String> fallback = properties.getSearch().getFallbackTopics();
                if (fallback != null && !fallback.isEmpty()) {
                    log.info("Portal liefert kein Thema - verwende Fallback-Thema(ta): {}.", fallback);
                    return fallback;
                }
                log.info("Portal liefert kein Thema und kein Fallback gesetzt - verwende konfigurierte Themen.");
            } catch (Exception ex) {
                log.warn("Themen konnten nicht aus dem Portal geladen werden: {}", ex.getMessage());
                List<String> fallback = properties.getSearch().getFallbackTopics();
                if (fallback != null && !fallback.isEmpty()) {
                    log.info("Verwende Fallback-Thema(ta): {}.", fallback);
                    return fallback;
                }
            }
        }
        return properties.getSearch().getTopics();
    }

    private int publish(List<EvaluatedItem> eligible) {
        int count = 0;
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
                count++;
                log.info("Veroeffentlicht (Score {}): {} - {}", item.relevance(), item.hit().title(), item.hit().url());
            } catch (Exception ex) {
                log.error("Veroeffentlichen fehlgeschlagen fuer {}: {}", item.hit().url(), ex.getMessage());
            }
        }
        return count;
    }
}
