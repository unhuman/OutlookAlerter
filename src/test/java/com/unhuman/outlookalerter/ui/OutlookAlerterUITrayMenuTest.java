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
import java.util.Comparator;
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
    }
}
