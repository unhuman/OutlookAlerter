package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

/**
 * Interface for components that can flash the screen to alert users
 */
@CompileStatic
interface ScreenFlasher {
    /**
     * Flashes the screen to alert the user of an upcoming event
     * @param event The calendar event that's about to start
     */
    void flash(CalendarEvent event)

    /**
     * Flashes the screen to alert the user of multiple events in the same time window
     * @param events List of calendar events starting soon
     */
    void flashMultiple(List<CalendarEvent> events)
}