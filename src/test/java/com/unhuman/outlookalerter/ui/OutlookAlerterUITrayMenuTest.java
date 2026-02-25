package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.util.LogManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tray menu helper methods in OutlookAlerterUI:
 *  - getEffectiveJoinUrl(CalendarEvent)
 *  - buildMeetingTrayLabel(CalendarEvent)
 *
 * Uses sun.misc.Unsafe to allocate a bare OutlookAlerterUI instance without
 * needing an OutlookClient or real OAuth tokens, matching the pattern in
 * OutlookAlerterUIAlertTest.
 */
class OutlookAlerterUITrayMenuTest {

    private Path tempDir;
    private ConfigManager configManager;
    private OutlookAlerterUI ui;

    @BeforeEach
    void setup() throws Exception {
        LogManager.getInstance();
        tempDir = Files.createTempDirectory("outlookalerter-tray-test");
        String configPath = new File(tempDir.toFile(), "test-config.properties").getAbsolutePath();
        configManager = new ConfigManager(configPath);
        configManager.loadConfiguration();

        // Allocate instance without running the constructor (same pattern as OutlookAlerterUIAlertTest)
        sun.misc.Unsafe unsafe = getUnsafe();
        ui = (OutlookAlerterUI) unsafe.allocateInstance(OutlookAlerterUI.class);

        setField(ui, "configManager", configManager);
        setField(ui, "alertedEventIds", ConcurrentHashMap.newKeySet());
        setField(ui, "alertBannerWindows", new CopyOnWriteArrayList<>());
        setField(ui, "statusLabel", new JLabel("Test"));
        setField(ui, "lastTokenValidationTime", System.currentTimeMillis());
    }

    @AfterEach
    void teardown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    // ── reflection helpers ────────────────────────────────────────────────

    private String invokeGetEffectiveJoinUrl(CalendarEvent event) throws Exception {
        Method m = OutlookAlerterUI.class.getDeclaredMethod("getEffectiveJoinUrl", CalendarEvent.class);
        m.setAccessible(true);
        return (String) m.invoke(ui, event);
    }

    private String invokeBuildMeetingTrayLabel(CalendarEvent event) throws Exception {
        Method m = OutlookAlerterUI.class.getDeclaredMethod("buildMeetingTrayLabel", CalendarEvent.class);
        m.setAccessible(true);
        return (String) m.invoke(ui, event);
    }

    private static CalendarEvent makeEvent(ZonedDateTime start, ZonedDateTime end) {
        CalendarEvent e = new CalendarEvent();
        e.setId("test-id");
        e.setSubject("Test Meeting");
        e.setStartTime(start);
        e.setEndTime(end);
        return e;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + clazz.getName() + " hierarchy");
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    // ── getEffectiveJoinUrl ───────────────────────────────────────────────

    @Nested
    @DisplayName("getEffectiveJoinUrl()")
    class GetEffectiveJoinUrl {

        @Test
        @DisplayName("returns onlineMeetingUrl when present (tier 1)")
        void tier1OnlineUrl() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setOnlineMeetingUrl("https://teams.microsoft.com/l/meetup/abc");
            assertEquals("https://teams.microsoft.com/l/meetup/abc", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("returns location when it is a URL and no onlineMeetingUrl (tier 2)")
        void tier2LocationUrl() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setLocation("https://zoom.us/j/123456?pwd=abc");
            assertNull(event.getOnlineMeetingUrl());
            assertEquals("https://zoom.us/j/123456?pwd=abc", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("skips location when it is not a URL")
        void locationNotUrl() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setLocation("Conference Room 4B");
            assertNull(invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("extracts Zoom href from body HTML (tier 3)")
        void tier3BodyHtmlZoomHref() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setBodyHtml("<html><a href=\"https://zoom.us/j/987?pwd=xyz\">Click to Join</a></html>");
            assertEquals("https://zoom.us/j/987?pwd=xyz", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("extracts Teams href from body HTML (tier 3)")
        void tier3BodyHtmlTeamsHref() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setBodyHtml("<html><a href=\"https://teams.microsoft.com/l/meetup/join123\">Join</a></html>");
            assertEquals("https://teams.microsoft.com/l/meetup/join123", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("falls back to first href in body HTML when no known-meeting URL (tier 3 fallback)")
        void tier3BodyHtmlGenericHref() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setBodyHtml("<html><a href=\"https://example.com/join\">Join</a></html>");
            assertEquals("https://example.com/join", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("extracts bare URL from bodyPreview when no other source (tier 4)")
        void tier4BodyPreview() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setBodyPreview("Join the meeting at https://zoom.us/j/555 today");
            assertEquals("https://zoom.us/j/555", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("returns null when no URL found in any source")
        void noUrl() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setLocation("Room 12");
            event.setBodyPreview("Discuss quarterly results");
            assertNull(invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("onlineMeetingUrl takes priority over location and body")
        void tier1WinsPriority() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setOnlineMeetingUrl("https://teams.microsoft.com/l/meetup/primary");
            event.setLocation("https://zoom.us/j/secondary");
            event.setBodyHtml("<html><a href=\"https://zoom.us/j/tertiary\">Join</a></html>");
            assertEquals("https://teams.microsoft.com/l/meetup/primary", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("prefers known-meeting href over generic href in body HTML")
        void tier3PrefersKnownMeetingHref() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setBodyHtml("<html>"
                    + "<a href=\"https://example.com/generic\">Some link</a>"
                    + "<a href=\"https://zoom.us/j/999?pwd=abc\">Join Zoom</a>"
                    + "</html>");
            assertEquals("https://zoom.us/j/999?pwd=abc", invokeGetEffectiveJoinUrl(event));
        }

        @Test
        @DisplayName("extracts only URL portion from mixed location (URL  +  room names after semicolon)")
        void tier2LocationUrlWithRooms() throws Exception {
            CalendarEvent event = makeEvent(ZonedDateTime.now(), ZonedDateTime.now().plusHours(1));
            event.setLocation(
                    "https://cvent.zoom.us/j/96298127794?pwd=abc&from=addon; ~ 8F1 Colosseum (12-ZR)~ 6F2 Pink Floyd (7-ZR)");
            String url = invokeGetEffectiveJoinUrl(event);
            assertEquals("https://cvent.zoom.us/j/96298127794?pwd=abc&from=addon", url);
        }
    }

    // ── buildMeetingTrayLabel ─────────────────────────────────────────────

    @Nested
    @DisplayName("buildMeetingTrayLabel()")
    class BuildMeetingTrayLabel {

        @Test
        @DisplayName("shows 'now' for in-progress meetings")
        void inProgressLabel() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(5),
                    ZonedDateTime.now().plusMinutes(25));
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.endsWith("(now)"), "Expected label to end with (now), got: " + label);
            assertTrue(label.contains("Test Meeting"));
        }

        @Test
        @DisplayName("shows 'in Xm' for upcoming meetings")
        void upcomingLabel() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().plusMinutes(7),
                    ZonedDateTime.now().plusMinutes(37));
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.matches(".+\\(in \\d+m\\)"), "Expected 'in Xm' pattern, got: " + label);
        }

        @Test
        @DisplayName("truncates long subject to 35 chars with ellipsis")
        void longSubjectTruncated() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setSubject("A Very Long Meeting Subject That Should Be Truncated By The Label Builder");
            String label = invokeBuildMeetingTrayLabel(event);
            // Subject part is before " (now)" — extract it
            String subjectPart = label.substring(0, label.lastIndexOf(" ("));
            assertTrue(subjectPart.endsWith("\u2026"), "Expected ellipsis at end of truncated subject");
            assertTrue(subjectPart.length() <= 35, "Subject part should be <=35 chars, was: " + subjectPart.length());
        }

        @Test
        @DisplayName("uses 'Meeting' as fallback when subject is null")
        void nullSubjectFallback() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setSubject(null);
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.startsWith("Meeting"), "Expected 'Meeting' fallback, got: " + label);
        }

        @Test
        @DisplayName("short subject is not truncated")
        void shortSubjectNotTruncated() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setSubject("Standup");
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.startsWith("Standup ("), "Expected unmodified subject, got: " + label);
        }

        @Test
        @DisplayName("appends physical location after time hint")
        void appendsPhysicalLocation() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation("Conference Room 4B");
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.contains("@ Conference Room 4B"),
                    "Expected location appended, got: " + label);
            assertTrue(label.contains("(now)"), "Expected time hint present, got: " + label);
        }

        @Test
        @DisplayName("does not append location when it is a URL (used as join link)")
        void doesNotAppendUrlLocation() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation("https://zoom.us/j/123456789");
            String label = invokeBuildMeetingTrayLabel(event);
            assertFalse(label.contains("@ "),
                    "Expected URL location not appended, got: " + label);
        }

        @Test
        @DisplayName("does not append location when location is null")
        void doesNotAppendNullLocation() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            // location defaults to null
            String label = invokeBuildMeetingTrayLabel(event);
            assertFalse(label.contains("@ "),
                    "Expected no location suffix when null, got: " + label);
        }

        @Test
        @DisplayName("shows physical room names from mixed URL+room location")
        void mixedLocationShowsRooms() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation(
                    "https://cvent.zoom.us/j/96298127794?pwd=abc&from=addon; ~ 8F1 Colosseum (12-ZR)~ 6F2 Pink Floyd (7-ZR)");
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.contains("@ "), "Expected location suffix, got: " + label);
            assertTrue(label.contains("8F1 Colosseum (12-ZR)"), "Expected first room, got: " + label);
            assertTrue(label.contains("6F2 Pink Floyd (7-ZR)"), "Expected second room, got: " + label);
        }

        @Test
        @DisplayName("de-duplicates repeated room names")
        void deduplicatesRooms() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            // Room A appears twice, Room B once
            event.setLocation("~ Room A~ Room B~ Room A");
            String label = invokeBuildMeetingTrayLabel(event);
            // "Room A" must appear only once
            int first = label.indexOf("Room A");
            int second = label.indexOf("Room A", first + 1);
            assertEquals(-1, second, "Room A should appear only once, got: " + label);
            assertTrue(label.contains("Room B"), "Expected Room B present, got: " + label);
        }

        @Test
        @DisplayName("only shows accepted rooms when attendee data is present")
        void marksRoomAcceptance() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation("~ Room Accept~ Room Decline~ Room Pending");
            Map<String, String> attendees = new LinkedHashMap<>();
            attendees.put("Room Accept", "accepted");
            attendees.put("Room Decline", "declined");
            attendees.put("Room Pending", "notResponded");
            event.setResourceAttendees(attendees);
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.contains("Room Accept"), "Expected accepted room shown, got: " + label);
            assertFalse(label.contains("Room Decline"), "Expected declined room excluded, got: " + label);
            assertFalse(label.contains("Room Pending"), "Expected non-responded room excluded, got: " + label);
        }

        @Test
        @DisplayName("shows all rooms when no attendee data is available")
        void showsAllRoomsWithNoAttendeeData() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation("~ Room A~ Room B");
            // no resourceAttendees set — defaults to empty map
            String label = invokeBuildMeetingTrayLabel(event);
            assertTrue(label.contains("Room A"), "Expected Room A shown, got: " + label);
            assertTrue(label.contains("Room B"), "Expected Room B shown, got: " + label);
        }

        @Test
        @DisplayName("returns null (no location suffix) when all rooms are declined/not responded")
        void returnsNullWhenNoAcceptedRooms() throws Exception {
            CalendarEvent event = makeEvent(
                    ZonedDateTime.now().minusMinutes(1),
                    ZonedDateTime.now().plusMinutes(59));
            event.setLocation("~ Room Decline~ Room Pending");
            Map<String, String> attendees = new LinkedHashMap<>();
            attendees.put("Room Decline", "declined");
            attendees.put("Room Pending", "notResponded");
            event.setResourceAttendees(attendees);
            String label = invokeBuildMeetingTrayLabel(event);
            assertFalse(label.contains("@ "), "Expected no location suffix when no accepted rooms, got: " + label);
        }
    }

    // ── getNextMeetingTimeLabel ────────────────────────────────────────

    @Nested
    @DisplayName("getNextMeetingTimeLabel() — next meeting label")
    class GetNextMeetingTimeLabel {

        private String invokeGetNextMeetingTimeLabel(List<CalendarEvent> events) throws Exception {
            Method m = OutlookAlerterUI.class.getDeclaredMethod("getNextMeetingTimeLabel", List.class);
            m.setAccessible(true);
            return (String) m.invoke(ui, events);
        }

        @Test
        @DisplayName("returns 'Next Meeting at' label for a future meeting")
        void returnLabelForFutureMeeting() throws Exception {
            ZonedDateTime future = ZonedDateTime.now().plusMinutes(90);
            CalendarEvent event = makeEvent(future, future.plusMinutes(30));
            event.setSubject("Quarterly Review");

            String label = invokeGetNextMeetingTimeLabel(Arrays.asList(event));

            assertNotNull(label);
            String expectedTime = future.format(DateTimeFormatter.ofPattern("h:mm a"));
            assertEquals("Next Meeting at " + expectedTime, label);
        }

        @Test
        @DisplayName("returns null when events list is null")
        void returnsNullForNullEvents() throws Exception {
            assertNull(invokeGetNextMeetingTimeLabel(null));
        }

        @Test
        @DisplayName("returns null when all events have ended")
        void returnsNullWhenAllEventsEnded() throws Exception {
            ZonedDateTime past = ZonedDateTime.now().minusHours(2);
            CalendarEvent event = makeEvent(past, past.plusMinutes(30));

            assertNull(invokeGetNextMeetingTimeLabel(Arrays.asList(event)));
        }

        @Test
        @DisplayName("returns null when list is empty")
        void returnsNullForEmptyList() throws Exception {
            assertNull(invokeGetNextMeetingTimeLabel(Arrays.asList()));
        }

        @Test
        @DisplayName("returns the earliest future meeting when multiple future meetings exist")
        void returnsEarliestOfMultipleFutureMeetings() throws Exception {
            ZonedDateTime first  = ZonedDateTime.now().plusMinutes(60);
            ZonedDateTime second = ZonedDateTime.now().plusMinutes(120);
            CalendarEvent e1 = makeEvent(first,  first.plusMinutes(30));
            CalendarEvent e2 = makeEvent(second, second.plusMinutes(30));

            // Pass in reverse order to confirm sorting
            String label = invokeGetNextMeetingTimeLabel(Arrays.asList(e2, e1));

            assertNotNull(label);
            String expectedTime = first.format(DateTimeFormatter.ofPattern("h:mm a"));
            assertEquals("Next Meeting at " + expectedTime, label);
        }

        @Test
        @DisplayName("skips ended events and returns label for the earliest future event")
        void skipsEndedEvents() throws Exception {
            ZonedDateTime past   = ZonedDateTime.now().minusHours(2);
            ZonedDateTime future = ZonedDateTime.now().plusMinutes(45);
            CalendarEvent ended  = makeEvent(past,   past.plusMinutes(30));
            CalendarEvent coming = makeEvent(future, future.plusMinutes(30));

            String label = invokeGetNextMeetingTimeLabel(Arrays.asList(ended, coming));

            assertNotNull(label);
            String expectedTime = future.format(DateTimeFormatter.ofPattern("h:mm a"));
            assertEquals("Next Meeting at " + expectedTime, label);
        }
    }
}
