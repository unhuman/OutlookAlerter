package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedDeque
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Log categories for filtering
 */
enum LogCategory {
    DATA_FETCH("Data Fetch"),
    MEETING_INFO("Meeting Info"),
    ALERT_PROCESSING("Alert Processing"),
    GENERAL("General")

    final String displayName

    LogCategory(String displayName) {
        this.displayName = displayName
    }

    @Override
    String toString() {
        return displayName
    }
}

/**
 * Represents a single log entry with category information
 */
@CompileStatic
class LogEntry {
    final String timestamp
    final String level
    final String message
    final LogCategory category
    final String formattedMessage

    LogEntry(String timestamp, String level, String message, LogCategory category) {
        this.timestamp = timestamp
        this.level = level
        this.message = message
        this.category = category
        this.formattedMessage = "${timestamp} [${level}] [${category.displayName}] ${message}"
    }
}

/**
 * Utility class for managing logs in the application.
 * Maintains a buffer of recent log messages and supports displaying them in a UI component.
 */
@CompileStatic
class LogManager {
    // Singleton instance
    private static LogManager instance

    // Maximum number of log lines to keep in memory
    private static final int MAX_LOG_LINES = 2000
    
    // Date format for log timestamps (DateTimeFormatter is thread-safe, unlike SimpleDateFormat)
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    // Collection to store log entries (thread-safe)
    private final ConcurrentLinkedDeque<LogEntry> logBuffer = new ConcurrentLinkedDeque<>()

    // Active category filters (categories to show)
    private final Set<LogCategory> activeFilters = EnumSet.allOf(LogCategory.class)

    // Reference to the text area where logs are displayed (if open)
    private JTextArea logTextArea = null

    /**
     * Private constructor for singleton pattern
     */
    private LogManager() {
        // Initialize empty
    }

    /**
     * Get the singleton instance
     * @return LogManager instance
     */
    static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager()
        }
        return instance
    }

    /**
     * Log an informational message (defaults to GENERAL category)
     * @param message The message to log
     */
    void info(String message) {
        log("INFO", message, LogCategory.GENERAL)
    }

    /**
     * Log an informational message with category
     * @param category The log category
     * @param message The message to log
     */
    void info(LogCategory category, String message) {
        log("INFO", message, category)
    }

    /**
     * Log a warning message (defaults to GENERAL category)
     * @param message The message to log
     */
    void warn(String message) {
        log("WARN", message, LogCategory.GENERAL)
    }

    /**
     * Log a warning message with category
     * @param category The log category
     * @param message The message to log
     */
    void warn(LogCategory category, String message) {
        log("WARN", message, category)
    }

    /**
     * Log an error message (defaults to GENERAL category)
     * @param message The message to log
     */
    void error(String message) {
        log("ERROR", message, LogCategory.GENERAL)
    }

    /**
     * Log an error message with category
     * @param category The log category
     * @param message The message to log
     */
    void error(LogCategory category, String message) {
        log("ERROR", message, category)
    }

    /**
     * Log an error message with an exception (defaults to GENERAL category)
     * @param message The message to log
     * @param e The exception
     */
    void error(String message, Throwable e) {
        error(LogCategory.GENERAL, message, e)
    }

    /**
     * Log an error message with an exception and category
     * @param category The log category
     * @param message The message to log
     * @param e The exception
     */
    void error(LogCategory category, String message, Throwable e) {
        StringBuilder sb = new StringBuilder(message)
        sb.append(": ").append(e.getMessage())
        
        // Add stack trace
        StringWriter sw = new StringWriter()
        PrintWriter pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        sb.append("\n").append(sw.toString())
        
        log("ERROR", sb.toString(), category)
    }

    // Track whether we're currently logging to prevent recursion
    private static final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> Boolean.FALSE)
    
    /**
     * Log a message with the specified level and category
     * @param level The log level
     * @param message The message to log
     * @param category The log category
     */
    private void log(String level, String message, LogCategory category) {
        // Prevent recursive logging
        if (isLogging.get()) {
            return
        }
        
        isLogging.set(Boolean.TRUE)
        try {
            // Format with timestamp
            String timestamp = DATE_FORMAT.format(LocalDateTime.now())
            LogEntry entry = new LogEntry(timestamp, level, message, category)

            // Print to console using original streams to avoid recursion
            PrintStream outputStream = (level == "ERROR") 
                ? getOriginalErrStream() 
                : getOriginalOutStream()
                
            if (outputStream != null) {
                outputStream.println(entry.formattedMessage)
            }
            
            // Add to buffer
            addToBuffer(entry)

            // Update UI if attached
            updateLogTextArea()
        } finally {
            isLogging.set(Boolean.FALSE)
        }
    }
    
    // References to the original output streams
    private static PrintStream originalOut
    private static PrintStream originalErr
    
    /**
     * Set the original System.out stream reference
     * Must be called before any logging interception is set up
     */
    static void setOriginalOutStream(PrintStream out) {
        originalOut = out
    }
    
    /**
     * Set the original System.err stream reference
     * Must be called before any logging interception is set up
     */
    static void setOriginalErrStream(PrintStream err) {
        originalErr = err
    }
    
    /**
     * Get the original System.out stream
     */
    private static PrintStream getOriginalOutStream() {
        return originalOut != null ? originalOut : System.out
    }
    
    /**
     * Get the original System.err stream
     */
    private static PrintStream getOriginalErrStream() {
        return originalErr != null ? originalErr : System.err
    }

    /**
     * Add an entry to the log buffer, maintaining max size
     * @param entry The log entry to add
     */
    private synchronized void addToBuffer(LogEntry entry) {
        // Add the new entry
        logBuffer.add(entry)

        // Remove oldest entries if buffer exceeds max size
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst()
        }
    }
    
    /**
     * Set filter for a specific category
     * @param category The category to filter
     * @param enabled Whether to show this category
     */
    void setFilterEnabled(LogCategory category, boolean enabled) {
        if (enabled) {
            activeFilters.add(category)
        } else {
            activeFilters.remove(category)
        }
        updateLogTextArea()
    }

    /**
     * Check if a category filter is enabled
     * @param category The category to check
     * @return true if the category is shown
     */
    boolean isFilterEnabled(LogCategory category) {
        return activeFilters.contains(category)
    }

    /**
     * Get all available categories
     * @return Array of all LogCategory values
     */
    static LogCategory[] getCategories() {
        return LogCategory.values()
    }

    /**
     * Set the text area for displaying logs
     * @param textArea The JTextArea component
     */
    void setLogTextArea(JTextArea textArea) {
        this.logTextArea = textArea
        
        // Populate with existing logs
        if (textArea != null) {
            refreshLogDisplay()
        }
    }
    
    /**
     * Update the log text area by appending the latest entry instead of rebuilding everything
     */
    private void updateLogTextArea() {
        if (logTextArea != null) {
            final JTextArea textAreaRef = logTextArea; // Capture for thread safety
            // Get the latest entry to append
            final LogEntry latestEntry = logBuffer.peekLast()
            if (latestEntry == null) return
            
            SwingUtilities.invokeLater({
                try {
                    // Skip update if text area is no longer valid
                    if (textAreaRef == null || !textAreaRef.isDisplayable()) {
                        return;
                    }
                    
                    // Only append if the entry's category is active
                    if (!activeFilters.contains(latestEntry.category)) {
                        return;
                    }
                
                    // Get current caret position
                    final int docLength = textAreaRef.getDocument().getLength()
                    final int caretPosition = textAreaRef.getCaretPosition()
                    final boolean isAtEnd = (caretPosition == docLength)
                    
                    // Append only the new entry instead of rebuilding the entire display
                    textAreaRef.append(latestEntry.formattedMessage + "\n")
                    
                    // If caret was at the end, keep it at the end
                    if (isAtEnd) {
                        textAreaRef.setCaretPosition(textAreaRef.getDocument().getLength())
                    }
                } catch (Exception e) {
                    // Use original error stream directly to avoid recursion
                    PrintStream err = getOriginalErrStream()
                    if (err != null) {
                        err.println("Error updating log display: " + e.getMessage())
                    }
                }
            })
        }
    }
    
    /**
     * Refresh the log display with filtered logs
     * @param textArea The text area to update
     */
    private void refreshLogDisplay(JTextArea textArea) {
        if (textArea != null) {
            StringBuilder sb = new StringBuilder()
            for (LogEntry entry : logBuffer) {
                // Only show entries that match active filters
                if (activeFilters.contains(entry.category)) {
                    sb.append(entry.formattedMessage).append("\n")
                }
            }
            textArea.setText(sb.toString())
        }
    }
    
    /**
     * Refresh the log display with all buffered logs
     */
    private void refreshLogDisplay() {
        refreshLogDisplay(logTextArea)
    }
    
    /**
     * Get filtered logs as a single string
     * @return String containing filtered logs
     */
    String getLogsAsString() {
        StringBuilder sb = new StringBuilder()
        for (LogEntry entry : logBuffer) {
            if (activeFilters.contains(entry.category)) {
                sb.append(entry.formattedMessage).append("\n")
            }
        }
        return sb.toString()
    }

    /**
     * Clear all logs from the buffer
     */
    void clearLogs() {
        logBuffer.clear()
        updateLogTextArea()
    }
}
