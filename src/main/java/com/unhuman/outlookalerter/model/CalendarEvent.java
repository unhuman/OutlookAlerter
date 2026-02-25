package com.unhuman.outlookalerter.model;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a calendar event from Outlook
 */
public class CalendarEvent {
    private String id;
    private String subject;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private String location;
    private String organizer;
    private boolean isOnlineMeeting;
    private String onlineMeetingUrl;
    private String bodyPreview;
    private String bodyHtml;
    private String calendarName;
    private String responseStatus;
    private boolean cancelledByOrganizer;
    /**
     * Room/resource attendees: display name -> response status ("accepted", "declined",
     * "tentativelyAccepted", "notResponded", "none"). Populated from Graph API attendees
     * where type == "resource". Never null — defaults to an empty map.
     */
    private Map<String, String> resourceAttendees = new LinkedHashMap<>();

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public ZonedDateTime getStartTime() { return startTime; }
    public void setStartTime(ZonedDateTime startTime) { this.startTime = startTime; }

    public ZonedDateTime getEndTime() { return endTime; }
    public void setEndTime(ZonedDateTime endTime) { this.endTime = endTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getOrganizer() { return organizer; }
    public void setOrganizer(String organizer) { this.organizer = organizer; }

    public boolean getIsOnlineMeeting() { return isOnlineMeeting; }
    public void setIsOnlineMeeting(boolean isOnlineMeeting) { this.isOnlineMeeting = isOnlineMeeting; }
    // Convenience alias
    public boolean isOnlineMeeting() { return getIsOnlineMeeting(); }

    public String getOnlineMeetingUrl() { return onlineMeetingUrl; }
    public void setOnlineMeetingUrl(String onlineMeetingUrl) { this.onlineMeetingUrl = onlineMeetingUrl; }

    public String getBodyPreview() { return bodyPreview; }
    public void setBodyPreview(String bodyPreview) { this.bodyPreview = bodyPreview; }

    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }

    public String getCalendarName() { return calendarName; }
    public void setCalendarName(String calendarName) { this.calendarName = calendarName; }

    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }

    public boolean getCancelledByOrganizer() { return cancelledByOrganizer; }
    public void setCancelledByOrganizer(boolean cancelledByOrganizer) { this.cancelledByOrganizer = cancelledByOrganizer; }

    /** Returns an unmodifiable view of the resource-attendee map. */
    public Map<String, String> getResourceAttendees() { return Collections.unmodifiableMap(resourceAttendees); }
    public void setResourceAttendees(Map<String, String> resourceAttendees) {
        this.resourceAttendees = resourceAttendees != null ? new LinkedHashMap<>(resourceAttendees) : new LinkedHashMap<>();
    }

    /**
     * Returns true if this event has been cancelled — either via the Graph API
     * isCancelled flag, or by a subject prefix of "Cancelled:" / "Canceled:".
     */
    public boolean isCancelled() {
        if (cancelledByOrganizer) return true;
        if (subject == null) return false;
        String lower = subject.toLowerCase();
        return lower.startsWith("cancelled:") || lower.startsWith("canceled:");
    }

    /**
     * Calculate minutes until this event starts
     * @return Number of minutes until start time, negative if already started
     */
    public int getMinutesToStart() {
        if (startTime == null) {
            return Integer.MIN_VALUE;
        }

        ZonedDateTime now = ZonedDateTime.now(startTime.getZone());
        long startTimeMillis = startTime.toInstant().toEpochMilli();
        long nowMillis = now.toInstant().toEpochMilli();
        return (int) ((startTimeMillis - nowMillis) / (1000 * 60));
    }

    /**
     * Check if the event is currently in progress
     */
    public boolean isInProgress() {
        if (startTime == null || endTime == null) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(startTime.getZone());
        return now.toInstant().isAfter(startTime.toInstant()) &&
               now.toInstant().isBefore(endTime.toInstant());
    }

    /**
     * Check if this event has already ended
     */
    public boolean hasEnded() {
        if (endTime == null) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.now(endTime.getZone());
        return now.toInstant().isAfter(endTime.toInstant());
    }

    @Override
    public String toString() {
        return "CalendarEvent{" +
                "id='" + id + '\'' +
                ", subject='" + subject + '\'' +
                ", startTime=" + startTime +
                ", timezone=" + (startTime != null ? startTime.getZone() : "unknown") +
                ", minutesToStart=" + getMinutesToStart() +
                ", isInProgress=" + isInProgress() +
                ", isOnlineMeeting=" + isOnlineMeeting +
                ", calendarName='" + (calendarName != null ? calendarName : "primary") + '\'' +
                ", responseStatus='" + responseStatus + '\'' +
                '}';
    }
}
