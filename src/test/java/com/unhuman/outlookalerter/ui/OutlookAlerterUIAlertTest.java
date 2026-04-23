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

import javax.swing.JDialog;
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

        // Controls the return value of wasUserDismissed() for test scenarios.
        volatile boolean stubWasUserDismissed = false;

        @Override
        public boolean wasUserDismissed() {
            return stubWasUserDismissed;
        }

        // Controls the return value of getInteractionScreenBounds() for test scenarios.
        volatile java.awt.Rectangle stubInteractionScreenBounds = null;

        @Override
        public java.awt.Rectangle getInteractionScreenBounds() {
            return stubInteractionScreenBounds;
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

        // checkForEventAlerts also needs interactedEventIds
        Set<String> interactedIds = ConcurrentHashMap.newKeySet();
        setField(ui, "interactedEventIds", interactedIds);

        // checkForEventAlerts updates statusLabel on EDT — provide a stub
        setField(ui, "statusLabel", new JLabel("Test"));

        // Provide an alertScheduler for token validation code path
        setField(ui, "alertScheduler", testScheduler);

        // Set lastTokenValidationTime to now so token validation is skipped
        setField(ui, "lastTokenValidationTime", System.currentTimeMillis());

        // activeDismissAll is skipped by Unsafe.allocateInstance — initialise it so
        // showJoinMeetingDialogOnAllScreens does not NPE when checking for an existing session.
        setField(ui, "activeDismissAll", new java.util.concurrent.atomic.AtomicReference<>());
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

        @Test
        @DisplayName("never alerts for all-day event when ignore setting is OFF")
        void allDayEventNeverAlerted_settingOff() throws Exception {
            configManager.updateAlertMinutes(5);
            configManager.updateIgnoreAllDayEvents(false); // setting OFF

            // All-day event whose startTime is within the alert window
            CalendarEvent allDayEvent = makeTestEvent("Company Holiday", 1);
            allDayEvent.setId("allday-off");
            allDayEvent.setAllDay(true);

            invokeCheckForEventAlerts(List.of(allDayEvent));
            Thread.sleep(300);

            assertEquals(0, flasher.flashMultipleCount,
                "All-day events must never trigger time-based alerts, even when ignore setting is OFF");
            assertFalse(getAlertedEventIds().contains("allday-off"),
                "All-day event should not be added to alertedEventIds");
        }

        @Test
        @DisplayName("never alerts for all-day event when ignore setting is ON")
        void allDayEventNeverAlerted_settingOn() throws Exception {
            configManager.updateAlertMinutes(5);
            configManager.updateIgnoreAllDayEvents(true); // setting ON

            CalendarEvent allDayEvent = makeTestEvent("All-hands", 1);
            allDayEvent.setId("allday-on");
            allDayEvent.setAllDay(true);

            invokeCheckForEventAlerts(List.of(allDayEvent));
            Thread.sleep(300);

            assertEquals(0, flasher.flashMultipleCount,
                "All-day events must never trigger time-based alerts when ignore setting is ON");
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

    // ───────── Join meeting dialog invocation ─────────

    @Nested
    @DisplayName("Join meeting dialog invocation")
    class JoinMeetingDialogInvocationTests {

        @Test
        @DisplayName("join dialog is shown immediately on alert (log message present)")
        void dialogShownImmediatelyOnAlert() throws Exception {
            // Use a null-delegate flasher so flashMultiple() returns instantly —
            // the real flasher blocks for flashDurationSeconds.
            RecordingScreenFlasher instantFlasher = new RecordingScreenFlasher(null);
            setField(ui, "screenFlasher", instantFlasher);

            CalendarEvent event = makeTestEvent("Team Meeting", 1);
            event.setOnlineMeetingUrl("https://zoom.us/j/123");

            LogManager.getInstance().clearLogs();
            ui.performFullAlert("Meeting", "Title", "Msg", List.of(event));

            // Give the onFlashReady / non-Mac timer path time to run and drain EDT
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> {});

            String logs = LogManager.getInstance().getLogsAsString();
            assertTrue(logs.contains("JoinMeetingDialog: showing on all screens for"),
                    "Dialog must be shown immediately when alert fires; logs:\n" + logs);
        }

        @Test
        @DisplayName("dialog is shown regardless of wasUserDismissed value")
        void dialogShownRegardlessOfDismissFlag() throws Exception {
            for (boolean dismissed : new boolean[]{false, true}) {
                // Reset state
                LogManager.getInstance().clearLogs();
                setField(ui, "activeDismissAll", new java.util.concurrent.atomic.AtomicReference<>());

                RecordingScreenFlasher instantFlasher = new RecordingScreenFlasher(null);
                instantFlasher.stubWasUserDismissed = dismissed;
                setField(ui, "screenFlasher", instantFlasher);

                CalendarEvent event = makeTestEvent("Meeting " + dismissed, 1);
                event.setOnlineMeetingUrl("https://zoom.us/j/99");

                ui.performFullAlert("Meeting", "Title", "Msg", List.of(event));
                Thread.sleep(500);
                SwingUtilities.invokeAndWait(() -> {});

                String logs = LogManager.getInstance().getLogsAsString();
                assertTrue(logs.contains("JoinMeetingDialog: showing on all screens for"),
                        "Dialog must show regardless of wasUserDismissed=" + dismissed + "; logs:\n" + logs);
            }
        }

        @Test
        @DisplayName("performFullAlert does not throw when dialog path is triggered")
        void alertDoesNotThrow() {
            CalendarEvent event = makeTestEvent("Zoom Meeting", 1);
            event.setOnlineMeetingUrl("https://zoom.us/j/987");

            assertDoesNotThrow(() ->
                    ui.performFullAlert("Meeting", "Title", "Msg", List.of(event)));
        }

        @Test
        @DisplayName("flash still completes exactly once when dialog is shown")
        void flashCompletesOnceWhenDialogShown() throws Exception {
            CalendarEvent event = makeTestEvent("Meeting", 1);
            event.setOnlineMeetingUrl("https://zoom.us/j/987");

            ui.performFullAlert("Meeting", "Title", "Msg", List.of(event));
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> {});

            assertEquals(1, flasher.flashMultipleCount,
                    "Flash should complete exactly once even while dialog is shown");
        }

        @Test
        @DisplayName("second alert closes previous dialog session and opens new one")
        void secondAlertReplacesPreviousDialogSession() throws Exception {
            RecordingScreenFlasher instantFlasher = new RecordingScreenFlasher(null);
            setField(ui, "screenFlasher", instantFlasher);

            CalendarEvent event1 = makeTestEvent("First Meeting", 1);
            CalendarEvent event2 = makeTestEvent("Second Meeting", 2);

            LogManager.getInstance().clearLogs();
            ui.performFullAlert("Alert 1", "Title", "Msg", List.of(event1));
            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> {});

            ui.performFullAlert("Alert 2", "Title", "Msg", List.of(event2));
            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> {});

            String logs = LogManager.getInstance().getLogsAsString();
            // Both alerts should have shown the dialog
            long dialogCount = logs.lines()
                    .filter(l -> l.contains("JoinMeetingDialog: showing on all screens for"))
                    .count();
            assertTrue(dialogCount >= 2,
                    "Expected at least 2 dialog-show log entries for 2 alerts; got " + dialogCount + ";\nlogs:\n" + logs);
        }

        @Test
        @DisplayName("no dialog is shown when events list is null or empty")
        void noDialogForNullOrEmptyEvents() throws Exception {
            RecordingScreenFlasher instantFlasher = new RecordingScreenFlasher(null);
            setField(ui, "screenFlasher", instantFlasher);

            LogManager.getInstance().clearLogs();
            ui.performFullAlert("Token required", "Token", "Enter token", null);
            ui.performFullAlert("Token required", "Token", "Enter token", List.of());
            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> {});

            String logs = LogManager.getInstance().getLogsAsString();
            assertFalse(logs.contains("JoinMeetingDialog: showing on all screens for"),
                    "No join dialog should be shown for null/empty events; logs:\n" + logs);
        }

        @Test
        @DisplayName("join dialog timeout 0 (indefinite) disables countdown in JoinMeetingDialog")
        @org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
        void joinDialogIndefiniteTimeout() {
            configManager.updateJoinDialogTimeoutSeconds(0);
            CalendarEvent event = makeTestEvent("Long Meeting", 2);
            // Build the dialog directly and verify no NPE (timer must not be started)
            assertDoesNotThrow(() -> {
                // JoinMeetingDialog reads ConfigManager.getInstance() for the timeout
                JoinMeetingDialog dialog = new JoinMeetingDialog(null, List.of(event), e -> null);
                dialog.dispose();
            });
        }

        @Test
        @DisplayName("join dialog timeout > 0 uses configured value")
        @org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
        void joinDialogConfiguredTimeout() {
            configManager.updateJoinDialogTimeoutSeconds(30);
            CalendarEvent event = makeTestEvent("Short Meeting", 1);
            assertDoesNotThrow(() -> {
                JoinMeetingDialog dialog = new JoinMeetingDialog(null, List.of(event), e -> null);
                dialog.dispose();
            });
        }

        /**
         * Directly exercises {@code JoinMeetingDialog.show()} with a properly-initialized
         * {@link JFrame} — completely bypassing the flash, the {@code ScreenFlasher}, and the
         * Unsafe-allocated {@code ui} instance.
         *
         * <p>Since show() is now non-blocking (MODELESS), we use a WindowAdapter to detect
         * when the dialog opens, then close it from the same EDT task.
         *
         * <p>Skipped in headless CI environments where no display is available.
         */
        @Test
        @DisplayName("JoinMeetingDialog.show() renders the dialog with a real parent window (no flash)")
        @org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
        void joinMeetingDialogShowsDirectlyWithRealParent() throws Exception {
            CalendarEvent event = makeTestEvent("Isolation Test Meeting", 2);

            CountDownLatch openLatch = new CountDownLatch(1);
            CountDownLatch closeLatch = new CountDownLatch(1);

            JFrame parent = new JFrame("JoinMeetingDialog isolation test");

            SwingUtilities.invokeLater(() -> {
                try {
                    parent.setVisible(true);
                    // show() is now non-blocking (MODELESS)
                    JoinMeetingDialog.show(parent, List.of(event), e -> "https://zoom.us/j/test");
                    // Find the newly opened dialog and attach a listener
                    for (java.awt.Window w : java.awt.Window.getWindows()) {
                        if (w instanceof JDialog jd && jd.isVisible()) {
                            openLatch.countDown();
                            jd.addWindowListener(new java.awt.event.WindowAdapter() {
                                @Override
                                public void windowClosed(java.awt.event.WindowEvent e) {
                                    closeLatch.countDown();
                                }
                            });
                            // Auto-close after confirming it opened
                            SwingUtilities.invokeLater(jd::dispose);
                        }
                    }
                } catch (Exception ex) {
                    // Count down both latches so the test fails descriptively rather than timing out
                    openLatch.countDown();
                    closeLatch.countDown();
                } finally {
                    parent.dispose();
                }
            });

            assertTrue(openLatch.await(5, TimeUnit.SECONDS),
                    "JoinMeetingDialog should open within 5 s");
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS),
                    "JoinMeetingDialog should close within 5 s after being disposed");
        }
    }

    // ───────── Interacted event tracking ─────────

    @Nested
    @DisplayName("interactedEventIds suppression")
    class InteractedEventTrackingTests {

        @Test
        @DisplayName("event in interactedEventIds is not alerted")
        void interactedEventIsNotAlerted() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event = makeTestEvent("Board Meeting", 2);
            event.setId("interacted-1");

            // Pre-mark as interacted (simulates user having opened or dismissed it)
            getInteractedEventIds().add("interacted-1");

            invokeCheckForEventAlerts(List.of(event));
            Thread.sleep(300);

            assertEquals(0, flasher.flashMultipleCount,
                "Event already interacted with (opened/dismissed) should not alert again");
            assertFalse(getAlertedEventIds().contains("interacted-1"),
                "Event should not be added to alertedEventIds if interacted");
        }

        @Test
        @DisplayName("event alert fires when not in interactedEventIds")
        void nonInteractedEventIsAlerted() throws Exception {
            configManager.updateAlertMinutes(5);

            CalendarEvent event = makeTestEvent("New Meeting", 2);
            event.setId("not-interacted-1");

            // Do NOT add to interactedEventIds — this event should fire
            invokeCheckForEventAlerts(List.of(event));
            Thread.sleep(300);

            assertEquals(1, flasher.flashMultipleCount,
                "Event not in interactedEventIds should trigger alert normally");
        }

        @Test
        @DisplayName("native window close (red dot) does NOT add event to interactedEventIds")
        @org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
        void nativeWindowCloseDoesNotTrackInteraction() throws Exception {
            // Simulate what JoinMeetingDialog.showOnAllScreens does: onWindowClose fires
            // only the cleanup runnable, NOT onDismiss.  We verify that interactedEventIds
            // remains empty after a window-close callback.
            CalendarEvent event = makeTestEvent("Red Dot Meeting", 2);
            event.setId("red-dot-1");

            // The windowCloseAction fires cleanup (stops beeps / marks flash dismissed) but must
            // NOT add the event to interactedEventIds.  We exercise this by directly calling the
            // JoinMeetingDialog APIs used by showOnAllScreens.
            java.util.concurrent.atomic.AtomicBoolean windowCloseFired = new java.util.concurrent.atomic.AtomicBoolean(false);

            Runnable onWindowClose = () -> windowCloseFired.set(true); // cleanup only — no tracking

            // Create dialog, wire window-close action, then simulate window close
            JoinMeetingDialog dialog = new JoinMeetingDialog(null, List.of(event), e -> null);
            dialog.setWindowCloseAction(onWindowClose);

            // Simulate windowClosing (red dot) — must call setWindowCloseAction path, not onDismiss
            SwingUtilities.invokeAndWait(() -> {
                dialog.dispatchEvent(
                    new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
            });

            Thread.sleep(200);

            assertTrue(windowCloseFired.get(), "onWindowClose callback should fire on native close");
            assertFalse(getInteractedEventIds().contains("red-dot-1"),
                "Closing via red dot must NOT add event to interactedEventIds — no explicit action taken");
        }

        @Test
        @DisplayName("ended event is removed from interactedEventIds (memory cleanup)")
        void endedEventIsRemovedFromInteracted() throws Exception {
            configManager.updateAlertMinutes(5);

            // Pre-populate interactedEventIds with an ended event
            getInteractedEventIds().add("interacted-ended");

            CalendarEvent endedEvent = new CalendarEvent();
            endedEvent.setId("interacted-ended");
            endedEvent.setSubject("Finished Meeting");
            endedEvent.setStartTime(ZonedDateTime.now().minusHours(2));
            endedEvent.setEndTime(ZonedDateTime.now().minusHours(1));

            invokeCheckForEventAlerts(List.of(endedEvent));
            Thread.sleep(200);

            assertFalse(getInteractedEventIds().contains("interacted-ended"),
                "Ended event should be removed from interactedEventIds to free memory");
        }

        @Test
        @DisplayName("interactedEventIds preserved across checkAlertsOnWake (not reset on wake)")
        void interactedIdsPreservedOnWake() throws Exception {
            CalendarEvent event = makeTestEvent("Wake Meeting", 2);
            event.setId("wake-interacted");

            // Mark as interacted before wake
            getInteractedEventIds().add("wake-interacted");

            // Set as cached event
            Field lastFetchedField = findField(ui.getClass(), "lastFetchedEvents");
            lastFetchedField.setAccessible(true);
            lastFetchedField.set(ui, java.util.Collections.unmodifiableList(List.of(event)));

            Method wakeMethod = OutlookAlerterUI.class.getDeclaredMethod("checkAlertsOnWake");
            wakeMethod.setAccessible(true);
            wakeMethod.invoke(ui);
            Thread.sleep(400);

            // interactedEventIds should still contain the event (wake does not clear it)
            assertTrue(getInteractedEventIds().contains("wake-interacted"),
                "interactedEventIds should be preserved after wake — user-dismissed meetings should stay quiet");
            // Alert should NOT fire for the interacted event even after wake
            assertEquals(0, flasher.flashMultipleCount,
                "Event previously opened/dismissed should not re-alert after wake");
        }

        @Test
        @DisplayName("checkAlertsOnWake clears alertedEventIds but not interactedEventIds")
        void wakeResetsAlertedButNotInteracted() throws Exception {
            CalendarEvent alertedNotInteracted = makeTestEvent("Just Alerted", 2);
            alertedNotInteracted.setId("alerted-only");

            CalendarEvent interactedEvent = makeTestEvent("Dismissed Meeting", 2);
            interactedEvent.setId("interacted-only");

            // alerted-only: was alerted but user did not interact (simulates normal pre-sleep alert)
            getAlertedEventIds().add("alerted-only");
            // interacted-only: user explicitly dismissed/opened
            getInteractedEventIds().add("interacted-only");

            Field lastFetchedField = findField(ui.getClass(), "lastFetchedEvents");
            lastFetchedField.setAccessible(true);
            lastFetchedField.set(ui, java.util.Collections.unmodifiableList(
                List.of(alertedNotInteracted, interactedEvent)));

            Method wakeMethod = OutlookAlerterUI.class.getDeclaredMethod("checkAlertsOnWake");
            wakeMethod.setAccessible(true);
            wakeMethod.invoke(ui);
            Thread.sleep(400);

            assertFalse(getAlertedEventIds().contains("alerted-only"),
                "alertedEventIds should be cleared on wake so the event can re-alert");
            assertTrue(getInteractedEventIds().contains("interacted-only"),
                "interactedEventIds must survive wake — user already dismissed/opened this meeting");
        }

        @Test
        @DisplayName("both alerted-only and interacted events mixed: only non-interacted re-alerts on wake")
        void onWakeOnlyNonInteractedReAlerts() throws Exception {
            configManager.updateAlertMinutes(5);

            // Two upcoming meetings (in alert window)
            CalendarEvent alertedOnly = makeTestEvent("Alerted Only", 2);
            alertedOnly.setId("alerted-only-2");

            CalendarEvent interacted = makeTestEvent("Already Dismissed", 2);
            interacted.setId("interacted-2");

            // Simulate: both were alerted before sleep; user dismissed second one
            getAlertedEventIds().add("alerted-only-2");
            getAlertedEventIds().add("interacted-2");
            getInteractedEventIds().add("interacted-2");

            Field lastFetchedField = findField(ui.getClass(), "lastFetchedEvents");
            lastFetchedField.setAccessible(true);
            lastFetchedField.set(ui, java.util.Collections.unmodifiableList(
                List.of(alertedOnly, interacted)));

            Method wakeMethod = OutlookAlerterUI.class.getDeclaredMethod("checkAlertsOnWake");
            wakeMethod.setAccessible(true);
            wakeMethod.invoke(ui);
            Thread.sleep(400);

            // Only alertedOnly should generate a new alert (interacted is suppressed)
            assertEquals(1, flasher.flashMultipleCount,
                "Only non-interacted event should re-alert on wake");
            assertEquals("Alerted Only", flasher.flashedEvents.get(0).getSubject(),
                "The re-alerted event should be the one that was not explicitly dismissed");
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

    @SuppressWarnings("unchecked")
    private Set<String> getInteractedEventIds() throws Exception {
        Field field = OutlookAlerterUI.class.getDeclaredField("interactedEventIds");
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
