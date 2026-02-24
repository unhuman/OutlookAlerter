package com.unhuman.outlookalerter.util;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.EnumSet;
import java.util.Set;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Utility class for managing logs in the application.
 * Maintains a buffer of recent log messages and supports displaying them in a UI component.
 */
public class LogManager {
    private static LogManager instance;
    private static final int MAX_LOG_LINES = 2000;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_ERROR = "ERROR";
    private final ConcurrentLinkedDeque<LogEntry> logBuffer = new ConcurrentLinkedDeque<>();
    private final Set<LogCategory> activeFilters = EnumSet.allOf(LogCategory.class);
    private volatile String textFilter = "";
    private JTextArea logTextArea = null;

    private LogManager() {}

    public static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }

    public void info(String message) {
        log(LEVEL_INFO, message, LogCategory.GENERAL);
    }

    public void info(LogCategory category, String message) {
        log(LEVEL_INFO, message, category);
    }

    public void warn(String message) {
        log(LEVEL_WARN, message, LogCategory.GENERAL);
    }

    public void warn(LogCategory category, String message) {
        log(LEVEL_WARN, message, category);
    }

    public void error(String message) {
        log(LEVEL_ERROR, message, LogCategory.GENERAL);
    }

    public void error(LogCategory category, String message) {
        log(LEVEL_ERROR, message, category);
    }

    public void error(String message, Throwable e) {
        error(LogCategory.GENERAL, message, e);
    }

    public void error(LogCategory category, String message, Throwable e) {
        StringBuilder sb = new StringBuilder(message);
        sb.append(": ").append(e.getMessage());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        sb.append("\n").append(sw.toString());
        log(LEVEL_ERROR, sb.toString(), category);
    }

    private static final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private void log(String level, String message, LogCategory category) {
        if (Boolean.TRUE.equals(isLogging.get())) {
            return;
        }
        isLogging.set(Boolean.TRUE);
        try {
            String timestamp = DATE_FORMAT.format(LocalDateTime.now());
            LogEntry entry = new LogEntry(timestamp, level, message, category);

            PrintStream outputStream = (LEVEL_ERROR.equals(level))
                ? getOriginalErrStream()
                : getOriginalOutStream();

            if (outputStream != null) {
                outputStream.println(entry.getFormattedMessage());
            }

            addToBuffer(entry);
            updateLogTextArea();
        } finally {
            isLogging.set(Boolean.FALSE);
        }
    }

    private static PrintStream originalOut;
    private static PrintStream originalErr;

    public static void setOriginalOutStream(PrintStream out) {
        originalOut = out;
    }

    public static void setOriginalErrStream(PrintStream err) {
        originalErr = err;
    }

    private static PrintStream getOriginalOutStream() {
        return originalOut != null ? originalOut : System.out;
    }

    private static PrintStream getOriginalErrStream() {
        return originalErr != null ? originalErr : System.err;
    }

    private synchronized void addToBuffer(LogEntry entry) {
        logBuffer.add(entry);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst();
        }
    }

    public void setFilterEnabled(LogCategory category, boolean enabled) {
        if (enabled) {
            activeFilters.add(category);
        } else {
            activeFilters.remove(category);
        }
        refreshLogDisplay();
    }

    public boolean isFilterEnabled(LogCategory category) {
        return activeFilters.contains(category);
    }

    public static LogCategory[] getCategories() {
        return LogCategory.values();
    }

    /**
     * Set the text filter string. When non-empty, only log lines containing
     * this string (case-insensitive) are shown.
     */
    public void setTextFilter(String filter) {
        this.textFilter = (filter == null) ? "" : filter;
        refreshLogDisplay();
    }

    public String getTextFilter() {
        return textFilter;
    }

    private boolean matchesTextFilter(LogEntry entry) {
        String filter = textFilter;
        if (filter.isEmpty()) {
            return true;
        }
        return entry.getFormattedMessage().toLowerCase().contains(filter.toLowerCase());
    }

    public void setLogTextArea(JTextArea textArea) {
        this.logTextArea = textArea;
        if (textArea != null) {
            refreshLogDisplay();
        }
    }

    private void updateLogTextArea() {
        if (logTextArea != null) {
            final JTextArea textAreaRef = logTextArea;
            final LogEntry latestEntry = logBuffer.peekLast();
            if (latestEntry == null) return;

            SwingUtilities.invokeLater(() -> {
                try {
                    if (textAreaRef == null || !textAreaRef.isDisplayable()) {
                        return;
                    }
                    if (!activeFilters.contains(latestEntry.getCategory())) {
                        return;
                    }
                    if (!matchesTextFilter(latestEntry)) {
                        return;
                    }
                    final int docLength = textAreaRef.getDocument().getLength();
                    final int caretPosition = textAreaRef.getCaretPosition();
                    final boolean isAtEnd = (caretPosition == docLength);
                    textAreaRef.append(latestEntry.getFormattedMessage() + "\n");
                    if (isAtEnd) {
                        textAreaRef.setCaretPosition(textAreaRef.getDocument().getLength());
                    }
                } catch (Exception e) {
                    PrintStream err = getOriginalErrStream();
                    if (err != null) {
                        err.println("Error updating log display: " + e.getMessage());
                    }
                }
            });
        }
    }

    private void refreshLogDisplay(JTextArea textArea) {
        if (textArea != null) {
            StringBuilder sb = new StringBuilder();
            for (LogEntry entry : logBuffer) {
                if (activeFilters.contains(entry.getCategory()) && matchesTextFilter(entry)) {
                    sb.append(entry.getFormattedMessage()).append("\n");
                }
            }
            String text = sb.toString();
            if (SwingUtilities.isEventDispatchThread()) {
                textArea.setText(text);
            } else {
                SwingUtilities.invokeLater(() -> textArea.setText(text));
            }
        }
    }

    private void refreshLogDisplay() {
        refreshLogDisplay(logTextArea);
    }

    public String getLogsAsString() {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : logBuffer) {
            if (activeFilters.contains(entry.getCategory()) && matchesTextFilter(entry)) {
                sb.append(entry.getFormattedMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    public void clearLogs() {
        logBuffer.clear();
        refreshLogDisplay();
    }
}
