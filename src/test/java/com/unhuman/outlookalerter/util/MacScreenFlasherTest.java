package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.model.CalendarEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MacScreenFlasher.
 *
 * Visual flash rendering requires a real display and cannot be tested in headless mode,
 * but the serialisation infrastructure (Semaphore + completion latch) is fully exercised
 * here because it runs before the display-environment validation guard.
 */
class MacScreenFlasherTest {

    private MacScreenFlasher flasher;

    @BeforeEach
    void setUp() {
        flasher = new MacScreenFlasher();
    }

    @AfterEach
    void tearDown() {
        if (flasher != null) {
            flasher.forceCleanup();
        }
    }

    // ───────── Semaphore management ─────────

    @Nested
    @DisplayName("Semaphore management")
    class SemaphoreManagementTests {

        @Test
        @DisplayName("semaphore starts at 1 permit")
        void semaphoreInitialPermit() throws Exception {
            assertEquals(1, getFlashSemaphore().availablePermits(),
                "flashSemaphore should start with exactly 1 permit");
        }

        @Test
        @DisplayName("semaphore returns to 1 permit after a headless flashMultiple call")
        void semaphoreReleasedAfterHeadlessCall() throws Exception {
            // In headless environments MacScreenFlasher.flashMultiple() short-circuits after
            // display-environment validation, but the semaphore must still be released.
            CalendarEvent event = makeEvent("Headless Test", 1);
            flasher.flashMultiple(List.of(event));

            assertEquals(1, getFlashSemaphore().availablePermits(),
                "Semaphore must be released even when headless validation rejects the flash");
        }

        @Test
        @DisplayName("semaphore returns to 1 permit after flashMultiple with empty list (early-exit)")
        void semaphoreReleasedOnEarlyExit() throws Exception {
            // flashMultiple returns immediately for null/empty events (before semaphore acquire)
            flasher.flashMultiple(List.of());

            assertEquals(1, getFlashSemaphore().availablePermits(),
                "Empty-list early exit must not consume or lose a semaphore permit");
        }

        @Test
        @DisplayName("semaphore returns to 1 permit after forceCleanup")
        void semaphoreReleasedAfterForceCleanup() throws Exception {
            flasher.forceCleanup();

            assertEquals(1, getFlashSemaphore().availablePermits(),
                "forceCleanup must not affect the semaphore count");
        }

        @Test
        @DisplayName("semaphore is at 1 permit after N sequential headless calls")
        void semaphoreReleasedAfterRepeatedCalls() throws Exception {
            CalendarEvent event = makeEvent("Repeat", 1);
            for (int i = 0; i < 5; i++) {
                flasher.flashMultiple(List.of(event));
            }

            assertEquals(1, getFlashSemaphore().availablePermits(),
                "Permit must be exactly 1 after repeated sequential calls");
        }
    }

    // ───────── Concurrency: no deadlock ─────────

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("two concurrent flashMultiple calls both complete without deadlock")
        void concurrentCallsCompleteWithoutDeadlock() throws Exception {
            // In headless mode both calls return almost instantly; the important thing is
            // that neither hangs (which would happen if the semaphore were leaked).
            int threadCount = 2;
            ExecutorService exec = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threadCount);
            AtomicInteger completions = new AtomicInteger(0);

            CalendarEvent event = makeEvent("Concurrent Test", 1);
            for (int i = 0; i < threadCount; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();               // fire both at the same moment
                        flasher.flashMultiple(List.of(event));
                        completions.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();   // release both threads simultaneously
            boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
            exec.shutdownNow();

            assertTrue(finished, "Both concurrent flashMultiple() calls should complete within 5 s — deadlock suspected");
            assertEquals(threadCount, completions.get(), "Both calls should have run to completion");
            assertEquals(1, getFlashSemaphore().availablePermits(),
                "Exactly 1 permit must be available after both threads finish");
        }

        @Test
        @DisplayName("five concurrent flashMultiple calls all complete without deadlock")
        void manyConurrentCallsCompleteWithoutDeadlock() throws Exception {
            int threadCount = 5;
            ExecutorService exec = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threadCount);
            AtomicInteger completions = new AtomicInteger(0);

            CalendarEvent event = makeEvent("Storm Test", 2);
            for (int i = 0; i < threadCount; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        flasher.flashMultiple(List.of(event));
                        completions.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
            exec.shutdownNow();

            assertTrue(finished, "All " + threadCount + " flashMultiple() calls should complete within 10 s");
            assertEquals(threadCount, completions.get());
            assertEquals(1, getFlashSemaphore().availablePermits(),
                "Semaphore must be back at 1 permit after all threads finish");
        }
    }

    // ───────── Completion latch ─────────

    @Nested
    @DisplayName("Completion latch")
    class CompletionLatchTests {

        @Test
        @DisplayName("activeFlashLatch is null before and after a headless call")
        void latchNullAfterHeadlessCall() throws Exception {
            assertNull(getActiveFlashLatch(), "Latch should be null before any call");

            flasher.flashMultiple(List.of(makeEvent("Latch Test", 1)));

            assertNull(getActiveFlashLatch(),
                "Latch should be cleared to null by the finally block after flashMultiple returns");
        }

        @Test
        @DisplayName("forceCleanup signals the latch and clears it atomically")
        void forceCleanupSignalsLatch() throws Exception {
            // Manually inject a latch as if flashMultiple() had set it up
            CountDownLatch injected = new CountDownLatch(1);
            setActiveFlashLatch(injected);

            flasher.forceCleanup();

            // After forceCleanup the latch should have been counted down to 0
            assertEquals(0, injected.getCount(),
                "forceCleanup must signal (countDown) the activeFlashLatch");

            // And the field must be cleared so the next flash starts clean
            assertNull(getActiveFlashLatch(),
                "activeFlashLatch must be null after forceCleanup to prevent double-signal");
        }

        @Test
        @DisplayName("forceCleanup is idempotent when activeFlashLatch is null")
        void forceCleanupIdempotentWithNullLatch() {
            assertNull(getActiveFlashLatch());
            assertDoesNotThrow(() -> flasher.forceCleanup(),
                "forceCleanup must not throw when activeFlashLatch is null");
        }

        @Test
        @DisplayName("forceCleanup can be called repeatedly without double-signalling")
        void forceCleanupRepeatedDoesNotDoubleSignal() throws Exception {
            CountDownLatch injected = new CountDownLatch(1);
            setActiveFlashLatch(injected);

            // First call signals the latch AND clears the field
            flasher.forceCleanup();
            assertEquals(0, injected.getCount(), "Latch should be signalled once");
            assertNull(getActiveFlashLatch(), "Field cleared after first forceCleanup");

            // Second call finds null latch — must be a no-op, not throw
            assertDoesNotThrow(() -> flasher.forceCleanup(),
                "Second forceCleanup with null latch must be a no-op");
        }
    }

    // ───────── Helpers ─────────

    private static CalendarEvent makeEvent(String subject, int minutesFromNow) {
        CalendarEvent e = new CalendarEvent();
        e.setSubject(subject);
        e.setStartTime(ZonedDateTime.now().plusMinutes(minutesFromNow));
        e.setEndTime(ZonedDateTime.now().plusMinutes(minutesFromNow + 30));
        return e;
    }

    // ───────── User-dismissal flag ─────────

    @Nested
    @DisplayName("User-dismissal flag")
    class UserDismissalTests {

        @Test
        @DisplayName("wasUserDismissed() returns false before any flash")
        void falseBeforeAnyFlash() {
            assertFalse(flasher.wasUserDismissed(),
                "wasUserDismissed() should be false on a fresh instance");
        }

        @Test
        @DisplayName("wasUserDismissed() returns false after timer-based headless completion")
        void falseAfterTimerBasedCompletion() throws Exception {
            // Headless: flashMultiple() returns without showing windows (display validation fails),
            // so the flag should remain false — no user actually dismissed anything.
            CalendarEvent event = makeEvent("Timer Dismiss Test", 1);
            flasher.flashMultiple(List.of(event));
            assertFalse(flasher.wasUserDismissed(),
                "wasUserDismissed() should be false when flash expired via timer (no user action)");
        }

        @Test
        @DisplayName("wasUserDismissed() resets to false at the start of each new flashMultiple() call")
        void resetsAtStartOfFlashMultiple() throws Exception {
            // Manually set the flag to true as if a previous dismiss happened
            Field f = MacScreenFlasher.class.getDeclaredField("userDismissed");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) f.get(flasher)).set(true);

            // The next flashMultiple() should reset it to false before proceeding
            CalendarEvent event = makeEvent("Reset Test", 1);
            flasher.flashMultiple(List.of(event));

            assertFalse(flasher.wasUserDismissed(),
                "wasUserDismissed() should be reset to false at the start of each flashMultiple() call");
        }
    }

    private Semaphore getFlashSemaphore() throws Exception {
        Field f = MacScreenFlasher.class.getDeclaredField("flashSemaphore");
        f.setAccessible(true);
        return (Semaphore) f.get(flasher);
    }

    private CountDownLatch getActiveFlashLatch() {
        try {
            Field f = MacScreenFlasher.class.getDeclaredField("activeFlashLatch");
            f.setAccessible(true);
            return (CountDownLatch) f.get(flasher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setActiveFlashLatch(CountDownLatch latch) throws Exception {
        Field f = MacScreenFlasher.class.getDeclaredField("activeFlashLatch");
        f.setAccessible(true);
        f.set(flasher, latch);
    }
}
