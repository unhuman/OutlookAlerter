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

    /**
     * Returns true if the most recent flash was dismissed early by the user
     * (mouse click or key press), false if it expired normally via timer.
     */
    default boolean wasUserDismissed() {
        return false;
    }

    /**
     * Marks the current alert as user-dismissed without requiring a direct
     * interaction with the flash window.  Called when the user interacts with
     * a related dialog (e.g. JoinMeetingDialog) so that post-flash audio is
     * suppressed in the same way as a direct flash-window click.
     */
    default void markUserDismissed() {
        // Default no-op; implementations may override
    }

    /**
     * Returns the screen bounds of the monitor on which the user dismissed the flash,
     * or {@code null} if the flash expired by timer or no screen information is available.
     */
    default java.awt.Rectangle getInteractionScreenBounds() {
        return null;
    }
}
