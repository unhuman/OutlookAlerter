package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.core.ConfigManager;
import com.unhuman.outlookalerter.model.CalendarEvent;

import java.io.File;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Visual integration test for the concurrent-flash overlap scenario.
 *
 * PURPOSE
 * -------
 * JUnit tests run headless (no display, no real windows) and always call forceCleanup()
 * in @AfterEach, so they cannot detect stuck flash windows.  This class must be run
 * manually with a real display to verify that two overlapping flashes — simulating a
 * laptop-wake alert racing with an upcoming-meeting alert — both clear cleanly.
 *
 * WHAT TO EXPECT (WITH THE FIX)
 * ------------------------------
 *  1. Flash #1 ("Wake Alert / Previously In-Progress Meeting") appears full-screen.
 *  2. After ~1s, flash #2 ("Upcoming Meeting Alert") attempts to start.
 *     Because flashMultiple() is now serialised via a Semaphore, flash #2 blocks
 *     until flash #1's cleanup timer fires and the window is disposed.
 *  3. Flash #1 clears at ~flashDuration seconds.
 *  4. Flash #2 immediately appears and clears at ~flashDuration seconds after that.
 *  5. Both "[1] COMPLETE" and "[2] COMPLETE" are printed, with no leftover windows.
 *
 * WHAT YOU WOULD HAVE SEEN (BEFORE THE FIX)
 * ------------------------------------------
 *  Flash #2 calls forceCleanup() which disposes flash #1's windows.  Flash #2 then
 *  creates its own windows and arms a cleanup timer.  Meanwhile the first forceCleanup()
 *  (from flash #2's init path) may clear activeFlashFrames via a deferred invokeLater,
 *  losing track of flash #2's newly-added frames.  Those frames stay visible forever.
 *
 * RUN
 * ---
 *   scripts/test-screen-flash.sh
 *
 * or manually:
 *
 *   JAR=target/OutlookAlerter-*-jar-with-dependencies.jar
 *   java -cp "$JAR:target/test-classes" \
 *        com.unhuman.outlookalerter.util.FlashOverlapIntegrationTest
 *
 * Use Ctrl-C to abort if a window ever gets stuck (shouldn't happen with the fix).
 */
public class FlashOverlapIntegrationTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== FlashOverlapIntegrationTest ===");
        System.out.println("This test requires a real display.  Run from a terminal on macOS.");
        System.out.println();

        // ── Config ───────────────────────────────────────────────────────────────
        String configPath = new File(Files.createTempDirectory("flash-overlap-test").toFile(),
            "test-config.properties").getAbsolutePath();
        ConfigManager config = new ConfigManager(configPath);
        config.loadConfiguration();
        config.updateFlashDurationSeconds(4);   // short so the test finishes quickly
        config.updateAlertBeepCount(0);

        MacScreenFlasher flasher = new MacScreenFlasher();

        CalendarEvent wakeEvent = makeEvent("Wake Alert / Previously In-Progress Meeting", 0);
        CalendarEvent upcomingEvent = makeEvent("Upcoming Meeting Alert", 1);

        // ── Record outcomes ───────────────────────────────────────────────────────
        AtomicReference<Throwable> flash1Error = new AtomicReference<>();
        AtomicReference<Throwable> flash2Error = new AtomicReference<>();
        CountDownLatch flash1Done = new CountDownLatch(1);
        CountDownLatch flash2Done = new CountDownLatch(1);

        // ── Thread 1: wake-alert flash ────────────────────────────────────────────
        Thread t1 = new Thread(() -> {
            try {
                System.out.println("[1] Starting wake-alert flash...");
                flasher.flashMultiple(List.of(wakeEvent));
                System.out.println("[1] COMPLETE — wake-alert flash finished cleanly");
            } catch (Throwable e) {
                flash1Error.set(e);
                System.err.println("[1] ERROR: " + e.getMessage());
            } finally {
                flash1Done.countDown();
            }
        }, "FlashThread-Wake");

        // ── Thread 2: upcoming-meeting flash (starts 1 s after t1) ───────────────
        Thread t2 = new Thread(() -> {
            try {
                // 1-second delay simulates the typical race: wake-flash starts, then
                // ~1s later the upcoming-meeting scheduler also fires.
                Thread.sleep(1000);
                System.out.println("[2] Starting upcoming-meeting flash (1s after wake flash)...");
                flasher.flashMultiple(List.of(upcomingEvent));
                System.out.println("[2] COMPLETE — upcoming-meeting flash finished cleanly");
            } catch (Throwable e) {
                flash2Error.set(e);
                System.err.println("[2] ERROR: " + e.getMessage());
            } finally {
                flash2Done.countDown();
            }
        }, "FlashThread-Upcoming");

        t1.setDaemon(false);
        t2.setDaemon(false);
        t1.start();
        t2.start();

        // Wait for both flashes to complete (generous timeout)
        int timeoutSeconds = 30;
        boolean t1Finished = flash1Done.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        boolean t2Finished = flash2Done.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("[1] Finished within timeout : " + t1Finished
            + (flash1Error.get() != null ? "  ERROR: " + flash1Error.get().getMessage() : "  OK"));
        System.out.println("[2] Finished within timeout : " + t2Finished
            + (flash2Error.get() != null ? "  ERROR: " + flash2Error.get().getMessage() : "  OK"));

        boolean passed = t1Finished && t2Finished
            && flash1Error.get() == null && flash2Error.get() == null;

        System.out.println();
        if (passed) {
            System.out.println("PASS — both flashes completed; no windows should be stuck on screen.");
        } else {
            System.out.println("FAIL — see errors above.");
            System.exit(1);
        }

        // Explicit exit to terminate any lingering Swing threads
        System.exit(0);
    }

    private static CalendarEvent makeEvent(String subject, int minutesFromNow) {
        CalendarEvent e = new CalendarEvent();
        e.setSubject(subject);
        e.setStartTime(ZonedDateTime.now().plusMinutes(minutesFromNow));
        e.setEndTime(ZonedDateTime.now().plusMinutes(minutesFromNow + 30));
        return e;
    }
}
