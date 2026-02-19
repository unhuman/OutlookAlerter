package com.unhuman.outlookalerter.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.time.ZonedDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.*

class CalendarEventTest {

    private static final ZoneId UTC = ZoneId.of("UTC")

    private CalendarEvent makeEvent(ZonedDateTime start, ZonedDateTime end) {
        new CalendarEvent(
            id: "test-id",
            subject: "Test Meeting",
            startTime: start,
            endTime: end,
            organizer: "Organizer",
            isOnlineMeeting: false,
            responseStatus: "accepted"
        )
    }

    // ───────── getMinutesToStart ─────────

    @Nested
    @DisplayName("getMinutesToStart()")
    class MinutesToStart {

        @Test
        @DisplayName("returns positive minutes for future event")
        void futureEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(30),
                ZonedDateTime.now(UTC).plusMinutes(60)
            )
            int minutes = event.getMinutesToStart()
            // Should be approximately 29–30 (integer truncation)
            assertTrue(minutes >= 28 && minutes <= 30,
                "Expected ~30 minutes, got ${minutes}")
        }

        @Test
        @DisplayName("returns negative minutes for past event")
        void pastEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(15),
                ZonedDateTime.now(UTC).plusMinutes(15)
            )
            int minutes = event.getMinutesToStart()
            assertTrue(minutes < 0, "Expected negative minutes, got ${minutes}")
            assertTrue(minutes >= -16 && minutes <= -14,
                "Expected ~-15 minutes, got ${minutes}")
        }

        @Test
        @DisplayName("returns approximately 0 for event starting now")
        void eventStartingNow() {
            def event = makeEvent(
                ZonedDateTime.now(UTC),
                ZonedDateTime.now(UTC).plusMinutes(30)
            )
            int minutes = event.getMinutesToStart()
            assertTrue(minutes >= -1 && minutes <= 0,
                "Expected ~0 minutes, got ${minutes}")
        }

        @Test
        @DisplayName("returns Integer.MIN_VALUE when startTime is null")
        void nullStartTime() {
            def event = makeEvent(null, ZonedDateTime.now(UTC))
            assertEquals(Integer.MIN_VALUE, event.getMinutesToStart())
        }

        @Test
        @DisplayName("handles event far in the future")
        void farFutureEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusDays(7),
                ZonedDateTime.now(UTC).plusDays(7).plusHours(1)
            )
            int minutes = event.getMinutesToStart()
            // 7 days ~= 10080 minutes
            assertTrue(minutes > 10000, "Expected >10000 minutes, got ${minutes}")
        }

        @Test
        @DisplayName("handles different timezones correctly")
        void differentTimezones() {
            ZoneId eastern = ZoneId.of("America/New_York")
            ZoneId pacific = ZoneId.of("America/Los_Angeles")

            // Same instant, different zones — should yield same minutes
            ZonedDateTime futureInstant = ZonedDateTime.now(UTC).plusMinutes(60)
            def eventEastern = makeEvent(futureInstant.withZoneSameInstant(eastern), futureInstant.withZoneSameInstant(eastern).plusHours(1))
            def eventPacific = makeEvent(futureInstant.withZoneSameInstant(pacific), futureInstant.withZoneSameInstant(pacific).plusHours(1))

            int diffEastern = eventEastern.getMinutesToStart()
            int diffPacific = eventPacific.getMinutesToStart()
            // They should be within 1 minute of each other
            assertTrue(Math.abs(diffEastern - diffPacific) <= 1,
                "Eastern=${diffEastern}, Pacific=${diffPacific} — should be equal")
        }
    }

    // ───────── isInProgress ─────────

    @Nested
    @DisplayName("isInProgress()")
    class InProgress {

        @Test
        @DisplayName("returns true for event currently in progress")
        void currentlyInProgress() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(20)
            )
            assertTrue(event.isInProgress())
        }

        @Test
        @DisplayName("returns false for future event")
        void futureEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            )
            assertFalse(event.isInProgress())
        }

        @Test
        @DisplayName("returns false for ended event")
        void endedEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(60),
                ZonedDateTime.now(UTC).minusMinutes(30)
            )
            assertFalse(event.isInProgress())
        }

        @Test
        @DisplayName("returns false when startTime is null")
        void nullStartTime() {
            def event = makeEvent(null, ZonedDateTime.now(UTC).plusMinutes(30))
            assertFalse(event.isInProgress())
        }

        @Test
        @DisplayName("returns false when endTime is null")
        void nullEndTime() {
            def event = makeEvent(ZonedDateTime.now(UTC).minusMinutes(10), null)
            assertFalse(event.isInProgress())
        }

        @Test
        @DisplayName("returns false when both times are null")
        void bothNull() {
            def event = makeEvent(null, null)
            assertFalse(event.isInProgress())
        }
    }

    // ───────── hasEnded ─────────

    @Nested
    @DisplayName("hasEnded()")
    class HasEnded {

        @Test
        @DisplayName("returns true for ended event")
        void eventEnded() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(60),
                ZonedDateTime.now(UTC).minusMinutes(5)
            )
            assertTrue(event.hasEnded())
        }

        @Test
        @DisplayName("returns false for event still in progress")
        void eventInProgress() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(20)
            )
            assertFalse(event.hasEnded())
        }

        @Test
        @DisplayName("returns false for future event")
        void futureEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            )
            assertFalse(event.hasEnded())
        }

        @Test
        @DisplayName("returns false when endTime is null")
        void nullEndTime() {
            def event = makeEvent(ZonedDateTime.now(UTC).minusMinutes(60), null)
            assertFalse(event.hasEnded())
        }
    }

    // ───────── properties ─────────

    @Nested
    @DisplayName("properties")
    class Properties {

        @Test
        @DisplayName("all fields are settable and retrievable")
        void allFields() {
            def now = ZonedDateTime.now(UTC)
            def event = new CalendarEvent(
                id: "abc-123",
                subject: "Sprint Planning",
                startTime: now,
                endTime: now.plusHours(1),
                location: "Conference Room B",
                organizer: "John Doe",
                isOnlineMeeting: true,
                onlineMeetingUrl: "https://teams.microsoft.com/meet/123",
                bodyPreview: "Discuss sprint goals",
                calendarName: "Work",
                responseStatus: "tentativelyAccepted"
            )

            assertEquals("abc-123", event.id)
            assertEquals("Sprint Planning", event.subject)
            assertEquals(now, event.startTime)
            assertEquals(now.plusHours(1), event.endTime)
            assertEquals("Conference Room B", event.location)
            assertEquals("John Doe", event.organizer)
            assertTrue(event.isOnlineMeeting)
            assertEquals("https://teams.microsoft.com/meet/123", event.onlineMeetingUrl)
            assertEquals("Discuss sprint goals", event.bodyPreview)
            assertEquals("Work", event.calendarName)
            assertEquals("tentativelyAccepted", event.responseStatus)
        }

        @Test
        @DisplayName("default values for unset fields")
        void defaults() {
            def event = new CalendarEvent()
            assertNull(event.id)
            assertNull(event.subject)
            assertNull(event.startTime)
            assertNull(event.endTime)
            assertNull(event.location)
            assertNull(event.organizer)
            assertFalse(event.isOnlineMeeting)
            assertNull(event.onlineMeetingUrl)
            assertNull(event.bodyPreview)
            assertNull(event.calendarName)
            assertNull(event.responseStatus)
        }
    }

    // ───────── toString ─────────

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("includes subject and key fields")
        void includesKeyFields() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            )
            event.calendarName = "Work"
            event.responseStatus = "accepted"

            String str = event.toString()
            assertTrue(str.contains("Test Meeting"))
            assertTrue(str.contains("test-id"))
            assertTrue(str.contains("Work"))
            assertTrue(str.contains("accepted"))
        }

        @Test
        @DisplayName("handles null calendarName with 'primary' fallback")
        void nullCalendarName() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).plusMinutes(10),
                ZonedDateTime.now(UTC).plusMinutes(40)
            )
            event.calendarName = null
            String str = event.toString()
            assertTrue(str.contains("primary"))
        }

        @Test
        @DisplayName("handles null startTime in toString without exception")
        void nullStartTimeToString() {
            def event = new CalendarEvent(
                id: "x",
                subject: "No Time",
                responseStatus: "none"
            )
            // Should not throw — toString checks startTime for zone
            String str = event.toString()
            assertNotNull(str)
            assertTrue(str.contains("No Time"))
        }
    }

    // ───────── edge cases and interactions ─────────

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("event that just ended (1 second ago)")
        void justEnded() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusMinutes(30),
                ZonedDateTime.now(UTC).minusSeconds(1)
            )
            assertTrue(event.hasEnded())
            assertFalse(event.isInProgress())
        }

        @Test
        @DisplayName("very long event spanning multiple days")
        void multiDayEvent() {
            def event = makeEvent(
                ZonedDateTime.now(UTC).minusDays(1),
                ZonedDateTime.now(UTC).plusDays(1)
            )
            assertTrue(event.isInProgress())
            assertFalse(event.hasEnded())
            assertTrue(event.getMinutesToStart() < 0)
        }

        @Test
        @DisplayName("zero-duration event at current time")
        void zeroDurationEvent() {
            def now = ZonedDateTime.now(UTC)
            def event = makeEvent(now, now)
            // Start == End == Now: technically not "after start AND before end"
            assertFalse(event.isInProgress())
        }
    }
}
