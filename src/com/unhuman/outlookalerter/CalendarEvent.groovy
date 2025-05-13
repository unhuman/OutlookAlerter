package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Represents a calendar event from Outlook
 */
@CompileStatic
class CalendarEvent {
    String id
    String subject
    ZonedDateTime startTime
    ZonedDateTime endTime
    String location
    String organizer
    boolean isOnlineMeeting
    String onlineMeetingUrl
    String bodyPreview
    
    /**
     * Calculate minutes until this event starts
     * @return Number of minutes until start time, negative if already started
     */
    int getMinutesToStart() {
        // Make sure we compare with current time in the same timezone as the event
        ZonedDateTime now = ZonedDateTime.now(startTime.getZone())
        return (int) Duration.between(now, startTime).toMinutes()
    }
    
    /**
     * Check if the event is currently in progress
     */
    boolean isInProgress() {
        // Use the same timezone as the event for the current time
        ZonedDateTime now = ZonedDateTime.now(startTime.getZone())
        return now.isAfter(startTime) && now.isBefore(endTime)
    }
    
    @Override
    String toString() {
        return "CalendarEvent{" +
                "id='" + id + '\'' +
                ", subject='" + subject + '\'' +
                ", startTime=" + startTime +
                ", timezone=" + (startTime ? startTime.getZone() : "unknown") +
                ", minutesToStart=" + getMinutesToStart() +
                ", isInProgress=" + isInProgress() +
                ", isOnlineMeeting=" + isOnlineMeeting +
                '}'
    }
}