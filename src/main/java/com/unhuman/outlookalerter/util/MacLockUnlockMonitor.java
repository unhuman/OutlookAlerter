package com.unhuman.outlookalerter.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors macOS screen lock/unlock events by polling {@code ioreg} for
 * {@code CGSSessionScreenIsLocked}.  When a locked → unlocked transition is
 * detected, all registered unlock listeners are notified.
 */
public class MacLockUnlockMonitor {

    private static volatile MacLockUnlockMonitor instance;

    private final List<Runnable> unlockListeners = new CopyOnWriteArrayList<>();
    private volatile boolean lastLockedState  = false;
    private volatile boolean monitoring       = false;
    private ScheduledExecutorService executor;

    /** Poll interval in seconds — short enough to feel responsive. */
    private static final int POLL_INTERVAL_SECONDS = 5;

    /** Package-private to allow subclassing in tests; use {@link #getInstance()} in production. */
    MacLockUnlockMonitor() {}

    public static synchronized MacLockUnlockMonitor getInstance() {
        if (instance == null) {
            instance = new MacLockUnlockMonitor();
        }
        return instance;
    }

    public synchronized void startMonitoring() {
        if (monitoring) {
            LogManager.getInstance().info(LogCategory.GENERAL, "[LockUnlockMonitor] Already monitoring");
            return;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[LockUnlockMonitor] Starting lock/unlock monitoring");

        // Seed the initial state so the first poll doesn't fire a false unlock.
        lastLockedState = isScreenLocked();

        executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "LockUnlockMonitor");
            t.setDaemon(true);
            return t;
        });

        executor.scheduleAtFixedRate(
            this::checkForUnlockEvent,
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        monitoring = true;
    }

    public synchronized void stopMonitoring() {
        if (!monitoring) {
            return;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[LockUnlockMonitor] Stopping lock/unlock monitoring");

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }

        monitoring = false;
    }

    public void addUnlockListener(Runnable listener) {
        if (listener != null) {
            unlockListeners.add(listener);
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void checkForUnlockEvent() {
        try {
            boolean locked = isScreenLocked();
            if (lastLockedState && !locked) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[LockUnlockMonitor] Screen unlock detected");
                notifyUnlockListeners();
            }
            lastLockedState = locked;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                "[LockUnlockMonitor] Error checking lock state: " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} when the macOS session screen is currently locked.
     * Uses {@code ioreg -n Root -d1} and looks for {@code CGSSessionScreenIsLocked}
     * set to {@code Yes} or {@code true}.
     */
    boolean isScreenLocked() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ioreg", "-n", "Root", "-d1"});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("CGSSessionScreenIsLocked")) {
                        // ioreg output:   "CGSSessionScreenIsLocked"=Yes
                        //             or: "CGSSessionScreenIsLocked" = Yes
                        String lower = line.toLowerCase();
                        return lower.contains("=yes") || lower.contains("= yes")
                            || lower.contains("=true") || lower.contains("= true");
                    }
                }
            } finally {
                p.destroy();
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                "[LockUnlockMonitor] isScreenLocked error: " + e.getMessage());
        }
        return false;
    }

    private void notifyUnlockListeners() {
        for (Runnable listener : unlockListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL,
                    "[LockUnlockMonitor] Error notifying unlock listener: " + e.getMessage(), e);
            }
        }
    }
}
