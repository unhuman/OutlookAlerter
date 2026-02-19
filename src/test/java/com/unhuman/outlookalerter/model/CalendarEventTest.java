package com.unhuman.outlookalerter.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class CalendarEventTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private CalendarEvent makeEvent(ZonedDateTime start, ZonedDateTime end) {
        CalendarEvent event = new CalendarEvent();
        event.setId("test-id");
        event.setSubject("Test Meeting");
        event.setStartTime(start);
        event.setEndTime(end);
        event.setOrganizer("Organizer");
        event.setIsOnlineMeeting(false);
        event.setResponseStatus("accepted");
        return event;
    }

    // ───────── getMinutesToStart ─────────

    @Nested
    @DisplayName("getMinutesToStart()")
    class MinutesToStart {

        @Test
        @DisplayName("returns positive minutes for future event")
        void futureEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(30),
                ZonedDateTime.now(UTC).plusMinutes(60)
            );
            int minutes = event.getMinutesToStart();
            // Should be approximately 29–30 (integer truncation)
            assertTrue(minutes >= 28 && minutes <= 30,
                "Expected ~30 minutes, got " + minutes);
        }

        @Test
        @DisplayName("returns negative minutes for past event")
        void pastEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(15),
                ZonedDateTime.now(UTC).plusMinutes(15)
            );
            int minutes = event.getMinutesToStart();
            assertTrue(minutes < 0, "Expected negative minutes, got " + minutes);
            assertTrue(minutes >= -16 && minutes <= -14,
                "Expected ~-15 minutes, got " + minutes);
        }

        @Test
        @DisplayName("returns approximately 0 for event starting now")
        void eventStartingNow() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC),
                ZonedDateTime.now(UTC).plusMinutes(30)
            );
            int minutes = event.getMinutesToStart();
            assertTrue(minutes >= -1 && minutes <= 0,
                "Expected ~0 minutes, got " + minutes);
        }

        @Test
        @DisplayName("returns Integer.MIN_VALUE when startTime is null")
        void nullStartTime() {
            CalendarEvent event = makeEvent(null, ZonedDateTime.now(UTC));
            assertEquals(Integer.MIN_VALUE, event.getMinutesToStart());
        }

        @Test
        @DisplayName("handles event far in the future")
        void farFutureEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusDays(7),
                ZonedDateTime.now(UTC).plusDays(7).plusHours(1)
            );
            int minutes = event.getMinutesToStart();
            // 7 days ~= 10080 minutes
            assertTrue(minutes > 10000, "Expected >10000 minutes, got " + minutes);
        }

        @Test
        @DisplayName("handles different timezones correctly")
        void differentTimezones() {
            ZoneId eastern = ZoneId.of("America/New_York");
            ZoneId pacific = ZoneId.of("America/Los_Angeles");

            // Same instant, different zones — should yield same minutes
            ZonedDateTime futureInstant = ZonedDateTime.now(UTC).plusMinutes(60);
            CalendarEvent eventEastern = makeEvent(
                futureInstant.withZoneSameInstant(eastern),
                futureInstant.withZoneSameInstant(eastern).plusHours(1)
            );
            CalendarEvent eventPacific = makeEvent(
                futureInstant.withZoneSameInstant(pacific),
                futureInstant.withZoneSameInstant(pacific).plusHours(1)
            );

            int diffEastern = eventEastern.getMinutesToStart();
            int diffPacific = eventPacific.getMinutesToStart();
            // They should be within 1 minute of each other
            assertTrue(Math.abs(diffEastern - diffPacific) <= 1,
                "Eastern=" + diffEastern + ", Pacific=" + diffPacific + " — should be equal");
        }
    }

    // ───────── isInProgress ─────────

    @Nested
    @DisplayName("isInProgress()")
    class InProgress {

        @Test
        @DisplayName("returns true for event currently in progress")
        void currentlyInProgress() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(20)
            );
            assertTrue(event.isInProgress());
        }

        @Test
        @DisplayName("returns false for future event")
        void futureEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            );
            assertFalse(event.isInProgress());
        }

        @Test
        @DisplayName("returns false for ended event")
        void endedEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(60),
                ZonedDateTime.now(UTC).minusMinutes(30)
            );
            assertFalse(event.isInProgress());
        }

        @Test
        @DisplayName("returns false when startTime is null")
        void nullStartTime() {
            CalendarEvent event = makeEvent(null, ZonedDateTime.now(UTC).plusMinutes(30));
            assertFalse(event.isInProgress());
        }

        @Test
        @DisplayName("returns false when endTime is null")
        void nullEndTime() {
            CalendarEvent event = makeEvent(ZonedDateTime.now(UTC).minusMinutes(10), null);
            assertFalse(event.isInProgress());
        }

        @Test
        @DisplayName("returns false when both times are null")
        void bothNull() {
            CalendarEvent event = makeEvent(null, null);
            assertFalse(event.isInProgress());
        }
    }

    // ───────── hasEnded ─────────

    @Nested
    @DisplayName("hasEnded()")
    class HasEnded {

        @Test
        @DisplayName("returns true for ended event")
        void eventEnded() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(60),
                ZonedDateTime.now(UTC).minusMinutes(5)
            );
            assertTrue(event.hasEnded());
        }

        @Test
        @DisplayName("returns false for event still in progress")
        void eventInProgress() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(20)
            );
            assertFalse(event.hasEnded());
        }

        @Test
        @DisplayName("returns false for future event")
        void futureEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            );
            assertFalse(event.hasEnded());
        }

        @Test
        @DisplayName("returns false when endTime is null")
        void nullEndTime() {
            CalendarEvent event = makeEvent(ZonedDateTime.now(UTC).minusMinutes(60), null);
            assertFalse(event.hasEnded());
        }
    }

    // ───────── properties ─────────

    @Nested
    @DisplayName("properties")
    class Properties {

        @Test
        @DisplayName("all fields are settable and retrievable")
        void allFields() {
            ZonedDateTime now = ZonedDateTime.now(UTC);
            CalendarEvent event = new CalendarEvent();
            event.setId("abc-123");
            event.setSubject("Sprint Planning");
            event.setStartTime(now);
            event.setEndTime(now.plusHours(1));
            event.setLocation("Conference Room B");
            event.setOrganizer("John Doe");
            event.setIsOnlineMeeting(true);
            event.setOnlineMeetingUrl("https://teams.microsoft.com/meet/123");
            event.setBodyPreview("Discuss sprint goals");
            event.setCalendarName("Work");
            event.setResponseStatus("tentativelyAccepted");

            assertEquals("abc-123", event.getId());
            assertEquals("Sprint Planning", event.getSubject());
            assertEquals(now, event.getStartTime());
            assertEquals(now.plusHours(1), event.getEndTime());
            assertEquals("Conference Room B", event.getLocation());
            assertEquals("John Doe", event.getOrganizer());
            assertTrue(event.getIsOnlineMeeting());
            assertEquals("https://teams.microsoft.com/meet/123", event.getOnlineMeetingUrl());
            assertEquals("Discuss sprint goals", event.getBodyPreview());
            assertEquals("Work", event.getCalendarName());
            assertEquals("tentativelyAccepted", event.getResponseStatus());
        }

        @Test
        @DisplayName("default values for unset fields")
        void defaults() {
            CalendarEvent event = new CalendarEvent();
            assertNull(event.getId());
            assertNull(event.getSubject());
            assertNull(event.getStartTime());
            assertNull(event.getEndTime());
            assertNull(event.getLocation());
            assertNull(event.getOrganizer());
            assertFalse(event.getIsOnlineMeeting());
            assertNull(event.getOnlineMeetingUrl());
            assertNull(event.getBodyPreview());
            assertNull(event.getCalendarName());
            assertNull(event.getResponseStatus());
        }
    }

    // ───────── toString ─────────

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("includes subject and key fields")
        void includesKeyFields() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            );
            event.setCalendarName("Work");
            event.setResponseStatus("accepted");

            String str = event.toString();
            assertTrue(str.contains("Test Meeting"));
            assertTrue(str.contains("test-id"));
            assertTrue(str.contains("Work"));
            assertTrue(str.contains("accepted"));
        }

        @Test
        @DisplayName("handles null calendarName with 'primary' fallback")
        void nullCalendarName() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            );
            event.setCalendarName(null);
            String str = event.toString();
            assertTrue(str.contains("primary"));
        }

        @Test
        @DisplayName("handles null startTime in toString without exception")
        void nullStartTimeToString() {
            CalendarEvent event = new CalendarEvent();
            event.setId("x");
            event.setSubject("No Time");
            event.setResponseStatus("none");
            // Should not throw — toString checks startTime for zone
            String str = event.toString();
            assertNotNull(str);
            assertTrue(str.contains("No Time"));
        }
    }

    // ───────── edge cases and interactions ─────────

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("event that just ended (1 second ago)")
        void justEnded() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(30),
                ZonedDateTime.now(UTC).minusSeconds(1)
            );
            assertTrue(event.hasEnded());
            assertFalse(event.isInProgress());
        }

        @Test
        @DisplayName("very long event spanning multiple days")
        void multiDayEvent() {
            CalendarEvent event = makeEvent(
                ZonedDateTime.now(UTC).minusDays(1),
                ZonedDateTime.now(UTC).plusDays(1)
            );
            assertTrue(event.isInProgress());
            assertFalse(event.hasEnded());
            assertTrue(event.getMinutesToStart() < 0);
        }

        @Test
        @DisplayName("zero-duration event at current time")
        void zeroDurationEvent() {
            ZonedDateTime now = ZonedDateTime.now(UTC);
            CalendarEvent event = makeEvent(now, now);
            // Start == End == Now: technically not "after start AND before end"
            assertFalse(event.isInProgress());
        }
    }

    // ───────── isOnlineMeeting alias ─────────

    @Nested
    @DisplayName("isOnlineMeeting alias")
    class OnlineMeetingAlias {

        @Test
        @DisplayName("isOnlineMeeting() delegates to getIsOnlineMeeting() when true")
        void aliasTrueCase() {
            CalendarEvent event = new CalendarEvent();
            event.setIsOnlineMeeting(true);
            assertEquals(event.getIsOnlineMeeting(), event.isOnlineMeeting());
            assertTrue(event.isOnlineMeeting());
        }

        @Test
        @DisplayName("isOnlineMeeting() delegates to getIsOnlineMeeting() when false")
        void aliasFalseCase() {
            CalendarEvent event = new CalendarEvent();
            event.setIsOnlineMeeting(false);
            assertEquals(event.getIsOnlineMeeting(), event.isOnlineMeeting());
            assertFalse(event.isOnlineMeeting());
        }
    }
}
