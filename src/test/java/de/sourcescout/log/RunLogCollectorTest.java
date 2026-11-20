package de.sourcescout.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogCollectorTest {

    @Test
    void collectsInfoAndAboveWhileRunIsActive() {
        RunLogCollector.attach();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(RunLogCollectorTest.class);

        RunLogCollector.startRun();
        LoggingEvent event = new LoggingEvent("marker", logger, Level.WARN, "Fehler passiert", null, null);
        logger.callAppenders(event);
        List<String> events = RunLogCollector.snapshotAndStop();

        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("Fehler passiert"));
        assertEquals(0, RunLogCollector.snapshotAndStop().size());
    }
}
