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
    String calendarName  // Added to track which calendar this event comes from
    
    /**
     * Calculate minutes until this event starts
     * @return Number of minutes until start time, negative if already started
     */
    int getMinutesToStart() {
        if (startTime == null) {
            return -999; // Error case - invalid event time
        }
        
        // Make sure we compare with current time in the same timezone as the event
        ZonedDateTime now = ZonedDateTime.now(startTime.getZone())
        
        // Convert both times to epoch millis to avoid timezone comparison issues
        long startTimeMillis = startTime.toInstant().toEpochMilli();
        long nowMillis = now.toInstant().toEpochMilli();
        
        // Calculate minutes difference
        return (int) ((startTimeMillis - nowMillis) / (1000 * 60));
    }
    
    /**
     * Check if the event is currently in progress
     * @return true if the event is currently happening, false otherwise
     */
    boolean isInProgress() {
        if (startTime == null || endTime == null) {
            return false; // Can't determine if invalid times
        }
        
        // Use the same timezone as the event for the current time
        ZonedDateTime now = ZonedDateTime.now(startTime.getZone())
        
        // Convert to instants for comparison to avoid timezone issues
        return now.toInstant().isAfter(startTime.toInstant()) && 
               now.toInstant().isBefore(endTime.toInstant())
    }
    
    /**
     * Check if this event has already ended
     * @return true if the event has already ended, false otherwise
     */
    boolean hasEnded() {
        if (endTime == null) {
            return false; // Default to not ended if we can't determine
        }
        
        ZonedDateTime now = ZonedDateTime.now(endTime.getZone())
        return now.toInstant().isAfter(endTime.toInstant());
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
                ", calendarName='" + (calendarName ?: "primary") + '\'' +
                '}'
    }
}