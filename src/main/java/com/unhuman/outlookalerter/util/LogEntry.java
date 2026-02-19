package com.unhuman.outlookalerter.util;

/**
 * Represents a single log entry with category information
 */
public class LogEntry {
    private final String timestamp;
    private final String level;
    private final String message;
    private final LogCategory category;
    private final String formattedMessage;

    public LogEntry(String timestamp, String level, String message, LogCategory category) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.category = category;
        this.formattedMessage = timestamp + " [" + level + "] [" + category.getDisplayName() + "] " + message;
    }

    public String getTimestamp() { return timestamp; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
    public LogCategory getCategory() { return category; }
    public String getFormattedMessage() { return formattedMessage; }
}
