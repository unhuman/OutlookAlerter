package com.unhuman.outlookalerter.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors macOS sleep/wake events using system uptime detection.
 */
public class MacSleepWakeMonitor {

    private static MacSleepWakeMonitor instance;
    private final AtomicLong lastCheckTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastWakeTime = new AtomicLong(System.currentTimeMillis());
    private final List<Runnable> wakeListeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService monitorExecutor;
    private volatile boolean monitoring = false;

    private static final long SLEEP_DETECTION_THRESHOLD_MS = 65000;

    private MacSleepWakeMonitor() {}

    public static synchronized MacSleepWakeMonitor getInstance() {
        if (instance == null) {
            instance = new MacSleepWakeMonitor();
        }
        return instance;
    }

    public synchronized void startMonitoring() {
        if (monitoring) {
            LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Already monitoring");
            return;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Starting sleep/wake monitoring");

        monitorExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "SleepWakeMonitor");
            t.setDaemon(true);
            return t;
        });

        monitorExecutor.scheduleAtFixedRate(
            () -> checkForWakeEvent(),
            30,
            30,
            TimeUnit.SECONDS
        );

        monitoring = true;
    }

    public synchronized void stopMonitoring() {
        if (!monitoring) {
            return;
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Stopping sleep/wake monitoring");

        if (monitorExecutor != null) {
            monitorExecutor.shutdown();
            try {
                if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorExecutor.shutdownNow();
            }
            monitorExecutor = null;
        }

        monitoring = false;
    }

    private void checkForWakeEvent() {
        try {
            long currentTime = System.currentTimeMillis();
            long lastCheck = lastCheckTime.get();
            long timeDifference = currentTime - lastCheck;

            lastCheckTime.set(currentTime);

            if (timeDifference > SLEEP_DETECTION_THRESHOLD_MS) {
                LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Wake event detected! Time jump: " +
                    (timeDifference / 1000) + " seconds");
                lastWakeTime.set(currentTime);
                notifyWakeListeners();
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "[SleepWakeMonitor] Error checking for wake event: " + e.getMessage(), e);
        }
    }

    public void addWakeListener(Runnable listener) {
        if (listener != null) {
            wakeListeners.add(listener);
        }
    }

    private void notifyWakeListeners() {
        for (Runnable listener : wakeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "[SleepWakeMonitor] Error notifying wake listener: " + e.getMessage(), e);
            }
        }
    }

    public long getTimeSinceWake() {
        return System.currentTimeMillis() - lastWakeTime.get();
    }
}
