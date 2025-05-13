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
}