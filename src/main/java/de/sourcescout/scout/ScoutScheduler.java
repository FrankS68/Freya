package de.sourcescout.scout;

import de.sourcescout.config.ScoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ScoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScoutScheduler.class);

    private final ScoutService scoutService;
    private final ScoutProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public ScoutScheduler(ScoutService scoutService, ScoutProperties properties) {
        this.scoutService = scoutService;
        this.properties = properties;
    }

    @Scheduled(cron = "${scout.schedule.cron:0 0 */6 * * *}")
    public void scheduledRun() {
        run();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.getSchedule().isRunOnStartup()) {
            run();
        }
    }

    private void run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Ein Lauf ist bereits aktiv - dieser Lauf wird uebersprungen.");
            return;
        }
        try {
            scoutService.runOnce();
        } finally {
            running.set(false);
        }
    }
}
