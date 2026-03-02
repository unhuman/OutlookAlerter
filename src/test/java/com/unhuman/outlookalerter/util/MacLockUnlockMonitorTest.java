package com.unhuman.outlookalerter.util;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for MacLockUnlockMonitor.
 * These tests do NOT invoke ioreg; they validate listener registration and
 * the transition-detection logic by subclassing the monitor and controlling
 * the returned lock state directly.
 */
@DisplayName("MacLockUnlockMonitor")
class MacLockUnlockMonitorTest {

    /**
     * Testable subclass that lets us inject the lock state without running ioreg.
     */
    private static class TestableLockUnlockMonitor extends MacLockUnlockMonitor {
        private volatile boolean simulatedLocked = false;

        void setSimulatedLocked(boolean locked) {
            this.simulatedLocked = locked;
        }

        @Override
        boolean isScreenLocked() {
            return simulatedLocked;
        }

        /** Expose checkForUnlockEvent for direct testing. */
        void triggerCheck() {
            // Access the package-private check by calling the protected polling method
            // via reflection — simplest without changing production visibility.
            try {
                java.lang.reflect.Method m = MacLockUnlockMonitor.class
                        .getDeclaredMethod("checkForUnlockEvent");
                m.setAccessible(true);
                m.invoke(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Set initial lastLockedState directly via reflection. */
        void setLastLockedState(boolean locked) {
            try {
                java.lang.reflect.Field f = MacLockUnlockMonitor.class
                        .getDeclaredField("lastLockedState");
                f.setAccessible(true);
                f.setBoolean(this, locked);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("unlock listener fires on locked→unlocked transition")
    void listenerFiresOnUnlock() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();
        AtomicInteger callCount = new AtomicInteger(0);
        monitor.addUnlockListener(callCount::incrementAndGet);

        monitor.setLastLockedState(true);   // simulate: was locked
        monitor.setSimulatedLocked(false);  // now unlocked
        monitor.triggerCheck();

        assertEquals(1, callCount.get(), "Listener should fire once on unlock");
    }

    @Test
    @DisplayName("unlock listener does NOT fire when still locked")
    void listenerDoesNotFireWhenStillLocked() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();
        AtomicInteger callCount = new AtomicInteger(0);
        monitor.addUnlockListener(callCount::incrementAndGet);

        monitor.setLastLockedState(true);
        monitor.setSimulatedLocked(true);
        monitor.triggerCheck();

        assertEquals(0, callCount.get());
    }

    @Test
    @DisplayName("unlock listener does NOT fire when always unlocked")
    void listenerDoesNotFireWhenAlwaysUnlocked() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();
        AtomicInteger callCount = new AtomicInteger(0);
        monitor.addUnlockListener(callCount::incrementAndGet);

        monitor.setLastLockedState(false);
        monitor.setSimulatedLocked(false);
        monitor.triggerCheck();

        assertEquals(0, callCount.get());
    }

    @Test
    @DisplayName("unlock listener does NOT fire on unlocked→locked transition")
    void listenerDoesNotFireOnLock() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();
        AtomicInteger callCount = new AtomicInteger(0);
        monitor.addUnlockListener(callCount::incrementAndGet);

        monitor.setLastLockedState(false);
        monitor.setSimulatedLocked(true);   // just locked
        monitor.triggerCheck();

        assertEquals(0, callCount.get());
    }

    @Test
    @DisplayName("multiple listeners all fire on unlock")
    void multipleListenersAllFire() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();
        AtomicInteger a = new AtomicInteger(0);
        AtomicInteger b = new AtomicInteger(0);
        monitor.addUnlockListener(a::incrementAndGet);
        monitor.addUnlockListener(b::incrementAndGet);

        monitor.setLastLockedState(true);
        monitor.setSimulatedLocked(false);
        monitor.triggerCheck();

        assertEquals(1, a.get());
        assertEquals(1, b.get());
    }

    @Test
    @DisplayName("lastLockedState is updated after each check")
    void lastLockedStateUpdates() {
        TestableLockUnlockMonitor monitor = new TestableLockUnlockMonitor();

        // Start unlocked, go locked
        monitor.setLastLockedState(false);
        monitor.setSimulatedLocked(true);
        monitor.triggerCheck();

        // Now check that state was updated: if we go back to unlocked, listener fires
        AtomicInteger callCount = new AtomicInteger(0);
        monitor.addUnlockListener(callCount::incrementAndGet);
        monitor.setSimulatedLocked(false);
        monitor.triggerCheck();
        assertEquals(1, callCount.get(), "Listener should fire because state was locked after first check");
    }
}
