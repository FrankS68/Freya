package de.sourcescout.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class RunLogCollector extends AppenderBase<ILoggingEvent> {

    private static final RunLogCollector INSTANCE = new RunLogCollector();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final ThreadLocal<List<String>> RUN_EVENTS = new ThreadLocal<>();

    private RunLogCollector() {
        setName("run-log-collector");
    }

    public static void attach() {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender("run-log-collector") == null) {
            INSTANCE.start();
            root.addAppender(INSTANCE);
        }
    }

    public static void startRun() {
        RUN_EVENTS.set(new ArrayList<>());
    }

    public static List<String> snapshotAndStop() {
        List<String> events = RUN_EVENTS.get();
        RUN_EVENTS.remove();
        return events == null ? List.of() : List.copyOf(events);
    }

    @Override
    protected void append(ILoggingEvent event) {
        List<String> events = RUN_EVENTS.get();
        if (events == null || !event.getLevel().isGreaterOrEqual(Level.INFO)) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append(TIME.format(Instant.ofEpochMilli(event.getTimeStamp()))).append(' ')
                .append(event.getLevel()).append(' ')
                .append(shortName(event.getLoggerName())).append("  ")
                .append(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null && throwable.getMessage() != null) {
            line.append(" [").append(throwable.getClassName()).append(": ").append(throwable.getMessage()).append(']');
        }
        events.add(line.toString());
    }

    private static String shortName(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
    }
}
