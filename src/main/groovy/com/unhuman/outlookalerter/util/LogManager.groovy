package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentLinkedDeque
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Utility class for managing logs in the application.
 * Maintains a buffer of recent log messages and supports displaying them in a UI component.
 */
@CompileStatic
class LogManager {
    // Singleton instance
    private static LogManager instance

    // Maximum number of log lines to keep in memory
    private static final int MAX_LOG_LINES = 500
    
    // Date format for log timestamps
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    
    // Collection to store log messages (thread-safe)
    private final ConcurrentLinkedDeque<String> logBuffer = new ConcurrentLinkedDeque<>()
    
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
     * Log an informational message
     * @param message The message to log
     */
    void info(String message) {
        log("INFO", message)
    }

    /**
     * Log a warning message
     * @param message The message to log
     */
    void warn(String message) {
        log("WARN", message)
    }

    /**
     * Log an error message
     * @param message The message to log
     */
    void error(String message) {
        log("ERROR", message)
    }

    /**
     * Log an error message with an exception
     * @param message The message to log
     * @param e The exception
     */
    void error(String message, Throwable e) {
        StringBuilder sb = new StringBuilder(message)
        sb.append(": ").append(e.getMessage())
        
        // Add stack trace
        StringWriter sw = new StringWriter()
        PrintWriter pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        sb.append("\n").append(sw.toString())
        
        log("ERROR", sb.toString())
    }

    // Track whether we're currently logging to prevent recursion
    private static final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> Boolean.FALSE)
    
    /**
     * Log a message with the specified level
     * @param level The log level
     * @param message The message to log
     */
    private void log(String level, String message) {
        // Prevent recursive logging
        if (isLogging.get()) {
            return
        }
        
        isLogging.set(Boolean.TRUE)
        try {
            // Format with timestamp
            String timestamp = DATE_FORMAT.format(new Date())
            String formattedMessage = "${timestamp} [${level}] ${message}"
            
            // Print to console using original streams to avoid recursion
            PrintStream outputStream = (level == "ERROR") 
                ? getOriginalErrStream() 
                : getOriginalOutStream()
                
            if (outputStream != null) {
                outputStream.println(formattedMessage)
            }
            
            // Add to buffer
            addToBuffer(formattedMessage)
            
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
     * Add a message to the log buffer, maintaining max size
     * @param message The formatted message to add
     */
    private synchronized void addToBuffer(String message) {
        // Add the new message
        logBuffer.add(message)
        
        // Remove oldest messages if buffer exceeds max size
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst()
        }
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
     * Update the log text area with current buffer contents
     */
    private void updateLogTextArea() {
        if (logTextArea != null) {
            final JTextArea textAreaRef = logTextArea; // Capture for thread safety
            
            SwingUtilities.invokeLater({
                try {
                    // Skip update if text area is no longer valid
                    if (textAreaRef == null || !textAreaRef.isDisplayable()) {
                        return;
                    }
                
                    // Get current caret position
                    final int caretPosition = textAreaRef.getCaretPosition()
                    final boolean isAtEnd = (caretPosition == textAreaRef.getDocument().getLength())
                    
                    // Update text
                    refreshLogDisplay(textAreaRef)
                    
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
     * Refresh the log display with all buffered logs
     * @param textArea The text area to update
     */
    private void refreshLogDisplay(JTextArea textArea) {
        if (textArea != null) {
            StringBuilder sb = new StringBuilder()
            for (String line : logBuffer) {
                sb.append(line).append("\n")
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
     * Get all logs as a single string
     * @return String containing all logs
     */
    String getLogsAsString() {
        StringBuilder sb = new StringBuilder()
        for (String line : logBuffer) {
            sb.append(line).append("\n")
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
