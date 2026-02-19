package com.unhuman.outlookalerter.util;

/**
 * Log categories for filtering
 */
public enum LogCategory {
    DATA_FETCH("Data Fetch"),
    MEETING_INFO("Meeting Info"),
    ALERT_PROCESSING("Alert Processing"),
    GENERAL("General");

    private final String displayName;

    LogCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
