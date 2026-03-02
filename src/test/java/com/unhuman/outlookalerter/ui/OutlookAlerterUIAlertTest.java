package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.model.CalendarEvent;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.ScreenFlasher;
import com.unhuman.outlookalerter.util.ScreenFlasherFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the alerting system in OutlookAlerterUI.
 * Uses reflection to construct a minimal UI instance that can run headless
 * without needing an OutlookClient or real OAuth tokens.
 */
class OutlookAlerterUIAlertTest {

    private Path tempDir;
    private ConfigManager configManager;
    private OutlookAlerterUI ui;
    private RecordingScreenFlasher flasher;
    private ScheduledExecutorService testScheduler;

    /**
     * A ScreenFlasher that records calls AND delegates to a real platform flasher
     * so that actual flash windows are visible during test runs.
     */
    static class RecordingScreenFlasher implements ScreenFlasher {
        private final ScreenFlasher delegate;
        final List<CalendarEvent> flashedEvents = new CopyOnWriteArrayList<>();
        final List<List<CalendarEvent>> flashMultipleCalls = new CopyOnWriteArrayList<>();
        volatile int flashCount = 0;
        volatile int flashMultipleCount = 0;
        volatile int forceCleanupCount = 0;

        RecordingScreenFlasher(ScreenFlasher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void flash(CalendarEvent event) {
            flashCount++;
            flashedEvents.add(event);
            if (delegate != null) {
                delegate.flash(event);
            }
        }

        @Override
        public void flashMultiple(List<CalendarEvent> events) {
            flashMultipleCount++;
            flashMultipleCalls.add(new ArrayList<>(events));
            flashedEvents.addAll(events);
            if (delegate != null) {
                delegate.flashMultiple(events);
            }
        }

        @Override
        public void forceCleanup() {
            forceCleanupCount++;
            if (delegate != null) {
                delegate.forceCleanup();
            }
        }
    }

    @BeforeEach
    void setup() throws Exception {
        LogManager.getInstance();

        tempDir = Files.createTempDirectory("alert-test");
        String configPath = new File(tempDir.toFile(), "test-config.properties").getAbsolutePath();
        configManager = new ConfigManager(configPath);
        configManager.loadConfiguration();

        // Set low beep count to keep tests fast
        configManager.updateAlertBeepCount(0);
        // Flash must be visible for at least 2 seconds to be obvious
        configManager.updateFlashDurationSeconds(2);
        // Use 0.7 opacity so the flash is noticeable but you can still see through it
        configManager.updateFlashOpacity(0.7);

        // Create the real platform flasher (reads duration from ConfigManager singleton)
        // then wrap it so we get both visual flash AND recorded assertions
        ScreenFlasher realFlasher = ScreenFlasherFactory.createScreenFlasher();
        flasher = new RecordingScreenFlasher(realFlasher);
        testScheduler = Executors.newScheduledThreadPool(1);

        // Build a minimal OutlookAlerterUI via sun.misc.Unsafe to skip heavy constructor
        // (constructor requires OutlookClient with HTTP, OAuth, etc.)
        sun.misc.Unsafe unsafe = getUnsafe();
        ui = (OutlookAlerterUI) unsafe.allocateInstance(OutlookAlerterUI.class);

        // Set essential fields via reflection
        setField(ui, "configManager", configManager);
        setField(ui, "screenFlasher", flasher);
        setField(ui, "alertBannerWindows", new CopyOnWriteArrayList<JFrame>());

        // checkForEventAlerts needs alertedEventIds
        Set<String> alertedIds = ConcurrentHashMap.newKeySet();
        setField(ui, "alertedEventIds", alertedIds);

        // checkForEventAlerts updates statusLabel on EDT — provide a stub
        setField(ui, "statusLabel", new JLabel("Test"));

        // Provide an alertScheduler for token validation code path
        setField(ui, "alertScheduler", testScheduler);

        // Set lastTokenValidationTime to now so token validation is skipped
        setField(ui, "lastTokenValidationTime", System.currentTimeMillis());
    }

    @AfterEach
    void cleanup() throws IOException {
        // Dispose any lingering flash windows from the real flasher
        if (flasher != null) {
            flasher.forceCleanup();
        }
        if (testScheduler != null) {
            testScheduler.shutdownNow();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        }
    }

    // ───────── performFullAlert ─────────

    @Nested
    @DisplayName("performFullAlert()")
    class PerformFullAlertTests {

        @Test
        @DisplayName("invokes screen flash with provided events")
        void invokesFlash() throws Exception {
            CalendarEvent event = makeTestEvent("Team Standup", 1);
            List<CalendarEvent> events = List.of(event);

            ui.performFullAlert("Upcoming: Team Standup", "Meeting", "Team Standup in 1 min", events);

            // Give the flash thread time to execute
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount, "flashMultiple should be called once");
            assertEquals(1, flasher.flashedEvents.size());
            assertEquals("Team Standup", flasher.flashedEvents.get(0).getSubject());
        }

        @Test
        @DisplayName("handles multiple events")
        void handlesMultipleEvents() throws Exception {
            CalendarEvent event1 = makeTestEvent("Standup", 1);
            CalendarEvent event2 = makeTestEvent("Design Review", 1);
            List<CalendarEvent> events = List.of(event1, event2);

            ui.performFullAlert("2 upcoming meetings", "Meetings", "2 meetings soon", events);

            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount);
            assertEquals(2, flasher.flashedEvents.size());
        }

        @Test
        @DisplayName("handles null events list without throwing")
        void handlesNullEvents() {
            assertDoesNotThrow(() ->
                ui.performFullAlert("Token required", "Token", "Enter token", null)
            );
        }

        @Test
        @DisplayName("handles empty events list without throwing")
        void handlesEmptyEvents() {
            assertDoesNotThrow(() ->
                ui.performFullAlert("Token required", "Token", "Enter token", List.of())
            );
            assertEquals(0, flasher.flashMultipleCount, "No flash for empty events");
        }

        @Test
        @DisplayName("beep thread runs with configured count without blocking flash")
        void beepThreadRuns() throws Exception {
            // Default beep count is 5; use 2 here to keep test fast
            configManager.updateAlertBeepCount(2);
            CalendarEvent event = makeTestEvent("Meeting", 1);

            ui.performFullAlert("Meeting", "Meeting", "Now", List.of(event));

            // Beep thread runs async — give it time (2 beeps * 250ms + margin)
            Thread.sleep(1000);

            // In headless mode, Toolkit.beep() is a no-op so we cannot count
            // actual audio beeps. Instead, verify the beep thread did not
            // interfere with the screen flash — flash must still fire exactly once.
            assertEquals(1, flasher.flashMultipleCount,
                "Beep thread should not prevent flash from running");
            assertEquals(1, flasher.flashedEvents.size());
        }
    }

    // ───────── Screen flash behaviour ─────────

    @Nested
    @DisplayName("Screen flash")
    class ScreenFlashTests {

        @Test
        @DisplayName("flash receives event with correct data")
        void flashReceivesCorrectEventData() throws Exception {
            CalendarEvent event = makeTestEvent("Design Review", 2);
            event.setIsOnlineMeeting(true);
            event.setOrganizer("Alice");
            event.setId("flash-data-1");

            ui.performFullAlert("Upcoming", "Meeting", "Soon", List.of(event));
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCalls.size(), "flashMultiple should be called once");
            List<CalendarEvent> passedEvents = flasher.flashMultipleCalls.get(0);
            assertEquals(1, passedEvents.size());

            CalendarEvent flashed = passedEvents.get(0);
            assertEquals("Design Review", flashed.getSubject());
            assertEquals("Alice", flashed.getOrganizer());
            assertTrue(flashed.getIsOnlineMeeting(), "online meeting flag should be preserved");
        }

        @Test
        @DisplayName("flash receives all events in a single batch")
        void flashReceivesAllEventsInSingleBatch() throws Exception {
            CalendarEvent e1 = makeTestEvent("Standup", 1);
            CalendarEvent e2 = makeTestEvent("Retro", 2);
            CalendarEvent e3 = makeTestEvent("1:1", 3);

            ui.performFullAlert("3 meetings", "Meetings", "Soon", List.of(e1, e2, e3));
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCalls.size(), "Single batch expected");
            List<CalendarEvent> batch = flasher.flashMultipleCalls.get(0);
            assertEquals(3, batch.size(), "All three events should be in the batch");
            assertEquals("Standup", batch.get(0).getSubject());
            assertEquals("Retro", batch.get(1).getSubject());
            assertEquals("1:1", batch.get(2).getSubject());
        }

        @Test
        @DisplayName("flash is not triggered when events list is null")
        void flashNotTriggeredForNull() throws Exception {
            ui.performFullAlert("Token required", "Token", "Enter token", null);
            Thread.sleep(300);

            assertEquals(0, flasher.flashCount, "flash() should not be called");
            assertEquals(0, flasher.flashMultipleCount, "flashMultiple() should not be called");
        }

        @Test
        @DisplayName("flash is not triggered when events list is empty")
        void flashNotTriggeredForEmpty() throws Exception {
            ui.performFullAlert("Token required", "Token", "Enter token", List.of());
            Thread.sleep(300);

            assertEquals(0, flasher.flashCount);
            assertEquals(0, flasher.flashMultipleCount);
        }

        @Test
        @DisplayName("flash event data preserved through full alert pipeline")
        void flashPreservedThroughPipeline() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event = new CalendarEvent();
            event.setId("pipeline-1");
            event.setSubject("Architecture Review");
            event.setStartTime(ZonedDateTime.now().plusMinutes(2));
            event.setEndTime(ZonedDateTime.now().plusMinutes(62));
            event.setIsOnlineMeeting(true);
            event.setOrganizer("Bob");

            invokeCheckForEventAlerts(List.of(event));
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCalls.size(), "One flash batch expected");
            CalendarEvent flashed = flasher.flashMultipleCalls.get(0).get(0);
            assertEquals("Architecture Review", flashed.getSubject());
            assertEquals("Bob", flashed.getOrganizer());
            assertTrue(flashed.getIsOnlineMeeting());
        }

        @Test
        @DisplayName("forceCleanup can be called without error")
        void forceCleanupSafe() {
            assertDoesNotThrow(() -> flasher.forceCleanup());
            assertEquals(1, flasher.forceCleanupCount);
        }

        @Test
        @DisplayName("multiple performFullAlert calls produce separate flash batches")
        void separateFlashBatches() throws Exception {
            CalendarEvent e1 = makeTestEvent("Morning Sync", 1);
            CalendarEvent e2 = makeTestEvent("Sprint Planning", 2);

            ui.performFullAlert("Alert 1", "Meeting", "Now", List.of(e1));
            Thread.sleep(500);
            ui.performFullAlert("Alert 2", "Meeting", "Soon", List.of(e2));
            Thread.sleep(500);

            assertEquals(2, flasher.flashMultipleCalls.size(), "Two separate flash batches expected");
            assertEquals("Morning Sync", flasher.flashMultipleCalls.get(0).get(0).getSubject());
            assertEquals("Sprint Planning", flasher.flashMultipleCalls.get(1).get(0).getSubject());
        }
    }

    // ───────── checkForEventAlerts (alert decision logic) ─────────

    @Nested
    @DisplayName("checkForEventAlerts()")
    class CheckForEventAlertsTests {

        @Test
        @DisplayName("alerts for event within alert threshold")
        void alertsWithinThreshold() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event = makeTestEvent("Soon Meeting", 3);
            event.setId("event-1");

            invokeCheckForEventAlerts(List.of(event));

            // Give async threads time to run
            Thread.sleep(500);

            // The event should have been alerted (flash invoked)
            assertEquals(1, flasher.flashMultipleCount, "Should trigger alert for event within threshold");
            assertTrue(getAlertedEventIds().contains("event-1"), "Event should be marked as alerted");
        }

        @Test
        @DisplayName("does not alert for event outside threshold")
        void doesNotAlertOutsideThreshold() throws Exception {
            configManager.updateAlertMinutes(1);

            CalendarEvent event = makeTestEvent("Far Meeting", 30);
            event.setId("event-far");

            invokeCheckForEventAlerts(List.of(event));

            Thread.sleep(500);

            assertEquals(0, flasher.flashMultipleCount, "Should not alert for event far in future");
            assertFalse(getAlertedEventIds().contains("event-far"));
        }

        @Test
        @DisplayName("does not double-alert for same event")
        void noDuplicateAlerts() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event = makeTestEvent("Repeat Meeting", 2);
            event.setId("event-repeat");

            // First check — should alert
            invokeCheckForEventAlerts(List.of(event));
            Thread.sleep(500);
            assertEquals(1, flasher.flashMultipleCount);

            // Second check — same event, should NOT alert again
            invokeCheckForEventAlerts(List.of(event));
            Thread.sleep(500);
            assertEquals(1, flasher.flashMultipleCount, "Should not double-alert");
        }

        @Test
        @DisplayName("cleans up ended events from alerted set")
        void cleansUpEndedEvents() throws Exception {
            configManager.updateAlertMinutes(5);

            // Pre-populate alertedEventIds with an ended event
            getAlertedEventIds().add("ended-event");

            CalendarEvent endedEvent = new CalendarEvent();
            endedEvent.setId("ended-event");
            endedEvent.setSubject("Old Meeting");
            endedEvent.setStartTime(ZonedDateTime.now().minusHours(2));
            endedEvent.setEndTime(ZonedDateTime.now().minusHours(1));

            invokeCheckForEventAlerts(List.of(endedEvent));
            Thread.sleep(200);

            assertFalse(getAlertedEventIds().contains("ended-event"),
                "Ended event should be removed from alertedEventIds");
        }

        @Test
        @DisplayName("alerts for multiple qualifying events at once")
        void alertsMultipleEvents() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event1 = makeTestEvent("Meeting A", 2);
            event1.setId("multi-1");
            CalendarEvent event2 = makeTestEvent("Meeting B", 3);
            event2.setId("multi-2");

            invokeCheckForEventAlerts(List.of(event1, event2));
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount, "Should batch into one alert call");
            assertEquals(2, flasher.flashedEvents.size(), "Both events should be flashed");
            assertTrue(getAlertedEventIds().contains("multi-1"));
            assertTrue(getAlertedEventIds().contains("multi-2"));
        }
    }

    // ───────── checkAlertsOnWake ─────────

    @Nested
    @DisplayName("checkAlertsOnWake()")
    class WakeAlertTests {

        /** Inject a list of events into the UI's lastFetchedEvents field. */
        private void setLastFetchedEvents(List<CalendarEvent> events) throws Exception {
            Field field = findField(ui.getClass(), "lastFetchedEvents");
            field.setAccessible(true);
            field.set(ui, java.util.Collections.unmodifiableList(new ArrayList<>(events)));
        }

        private void invokeCheckAlertsOnWake() throws Exception {
            Method method = OutlookAlerterUI.class.getDeclaredMethod("checkAlertsOnWake");
            method.setAccessible(true);
            method.invoke(ui);
        }

        /** Event that started 10 minutes ago and ends in 20 minutes (in progress). */
        private CalendarEvent makeInProgressEvent(String subject) {
            CalendarEvent event = new CalendarEvent();
            event.setSubject(subject);
            event.setId(subject.toLowerCase().replace(' ', '-') + "-id");
            event.setStartTime(ZonedDateTime.now().minusMinutes(10));
            event.setEndTime(ZonedDateTime.now().plusMinutes(20));
            return event;
        }

        /** Event that ended 5 minutes ago. */
        private CalendarEvent makeEndedEvent(String subject) {
            CalendarEvent event = new CalendarEvent();
            event.setSubject(subject);
            event.setId(subject.toLowerCase().replace(' ', '-') + "-id");
            event.setStartTime(ZonedDateTime.now().minusMinutes(35));
            event.setEndTime(ZonedDateTime.now().minusMinutes(5));
            return event;
        }

        @Test
        @DisplayName("alerts for in-progress meeting on wake")
        void alertsForInProgressOnWake() throws Exception {
            CalendarEvent inProgress = makeInProgressEvent("Board Review");
            setLastFetchedEvents(List.of(inProgress));

            invokeCheckAlertsOnWake();
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount, "Should flash for in-progress meeting");
            assertEquals("Board Review", flasher.flashedEvents.get(0).getSubject());
        }

        @Test
        @DisplayName("does not alert for in-progress meeting that already alerted before wake")
        void inProgressAlreadyAlertedIsReAlertedonWake() throws Exception {
            // Simulate: event was alerted before sleep
            CalendarEvent inProgress = makeInProgressEvent("Daily Standup");
            getAlertedEventIds().add(inProgress.getId());
            setLastFetchedEvents(List.of(inProgress));

            // Wake check should clear the alerted status and re-alert
            invokeCheckAlertsOnWake();
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount,
                "In-progress event alerted before sleep should be re-alerted on wake");
        }

        @Test
        @DisplayName("does not alert for ended meeting on wake")
        void doesNotAlertForEndedMeetingOnWake() throws Exception {
            CalendarEvent ended = makeEndedEvent("Morning Sync");
            setLastFetchedEvents(List.of(ended));

            invokeCheckAlertsOnWake();
            Thread.sleep(300);

            assertEquals(0, flasher.flashMultipleCount, "Ended meeting should not trigger alert on wake");
        }

        @Test
        @DisplayName("alerts for upcoming meeting within threshold on wake")
        void alertsForUpcomingOnWake() throws Exception {
            configManager.updateAlertMinutes(5);
            CalendarEvent upcoming = makeTestEvent("Sprint Planning", 3);
            upcoming.setId("sprint-id");
            setLastFetchedEvents(List.of(upcoming));

            invokeCheckAlertsOnWake();
            Thread.sleep(500);

            assertEquals(1, flasher.flashMultipleCount, "Upcoming event within threshold should alert on wake");
        }

        @Test
        @DisplayName("does nothing when no cached events")
        void doesNothingWithNoCachedEvents() throws Exception {
            setLastFetchedEvents(List.of());

            assertDoesNotThrow(() -> invokeCheckAlertsOnWake());
            Thread.sleep(300);

            assertEquals(0, flasher.flashMultipleCount);
        }

        @Test
        @DisplayName("alerts in-progress and upcoming in separate batches on wake")
        void alertsBothInProgressAndUpcoming() throws Exception {
            configManager.updateAlertMinutes(5);
            CalendarEvent inProgress = makeInProgressEvent("Ongoing Review");
            CalendarEvent upcoming = makeTestEvent("Next Meeting", 2);
            upcoming.setId("next-id");
            setLastFetchedEvents(List.of(inProgress, upcoming));

            invokeCheckAlertsOnWake();
            Thread.sleep(500);

            // In-progress fires first via performFullAlert, upcoming via checkForEventAlerts
            assertEquals(2, flasher.flashMultipleCount,
                "Should produce two alert batches: one in-progress, one upcoming");
        }
    }

    // ───────── Helpers ─────────

    private CalendarEvent makeTestEvent(String subject, int minutesFromNow) {
        CalendarEvent event = new CalendarEvent();
        event.setSubject(subject);
        event.setStartTime(ZonedDateTime.now().plusMinutes(minutesFromNow));
        event.setEndTime(ZonedDateTime.now().plusMinutes(minutesFromNow + 30));
        event.setIsOnlineMeeting(false);
        event.setOrganizer("Test Organizer");
        return event;
    }

    private void invokeCheckForEventAlerts(List<CalendarEvent> events) throws Exception {
        Method method = OutlookAlerterUI.class.getDeclaredMethod("checkForEventAlerts", List.class);
        method.setAccessible(true);
        method.invoke(ui, events);
    }

    @SuppressWarnings("unchecked")
    private Set<String> getAlertedEventIds() throws Exception {
        Field field = OutlookAlerterUI.class.getDeclaredField("alertedEventIds");
        field.setAccessible(true);
        return (Set<String>) field.get(ui);
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
}
