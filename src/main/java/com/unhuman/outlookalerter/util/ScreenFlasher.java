package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.model.CalendarEvent;
import java.util.List;

/**
 * Interface for components that can flash the screen to alert users
 */
public interface ScreenFlasher {
    /**
     * Flashes the screen to alert the user of an upcoming event
     * @param event The calendar event that's about to start
     */
    void flash(CalendarEvent event);

    /**
     * Flashes the screen to alert the user of multiple events in the same time window
     * @param events List of calendar events starting soon
     */
    void flashMultiple(List<CalendarEvent> events);

    /**
     * Force cleanup of all active flash windows and timers.
     * Called when the application needs to stop flashing immediately.
     */
    default void forceCleanup() {
        // Default no-op; implementations may override
    }
}
