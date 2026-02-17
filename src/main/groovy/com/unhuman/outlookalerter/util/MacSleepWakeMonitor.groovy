package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors macOS sleep/wake events using system uptime detection.
 * This helps prevent hanging during sleep/wake cycles.
 */
@CompileStatic
class MacSleepWakeMonitor {

    private static MacSleepWakeMonitor instance
    private final AtomicLong lastCheckTime = new AtomicLong(System.currentTimeMillis())
    private final AtomicLong lastWakeTime = new AtomicLong(System.currentTimeMillis())
    private final List<Runnable> wakeListeners = new CopyOnWriteArrayList<>()
    private ScheduledExecutorService monitorExecutor
    private volatile boolean monitoring = false

    // Threshold for detecting sleep (if time jump is more than this, assume we slept)
    private static final long SLEEP_DETECTION_THRESHOLD_MS = 65000 // 65 seconds

    private MacSleepWakeMonitor() {
        // Private constructor for singleton
    }

    static synchronized MacSleepWakeMonitor getInstance() {
        if (instance == null) {
            instance = new MacSleepWakeMonitor()
        }
        return instance
    }

    /**
     * Start monitoring for sleep/wake events
     */
    synchronized void startMonitoring() {
        if (monitoring) {
            LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Already monitoring")
            return
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Starting sleep/wake monitoring")

        monitorExecutor = Executors.newScheduledThreadPool(1, { r ->
            Thread t = new Thread(r, "SleepWakeMonitor")
            t.setDaemon(true)
            return t
        })

        // Check every 30 seconds for time jumps
        monitorExecutor.scheduleAtFixedRate(
            { checkForWakeEvent() } as Runnable,
            30,
            30,
            TimeUnit.SECONDS
        )

        monitoring = true
    }

    /**
     * Stop monitoring
     */
    synchronized void stopMonitoring() {
        if (!monitoring) {
            return
        }

        LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Stopping sleep/wake monitoring")

        if (monitorExecutor != null) {
            monitorExecutor.shutdown()
            try {
                if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorExecutor.shutdownNow()
                }
            } catch (InterruptedException e) {
                monitorExecutor.shutdownNow()
            }
            monitorExecutor = null
        }

        monitoring = false
    }

    /**
     * Check if we just woke from sleep by detecting time jumps
     */
    private void checkForWakeEvent() {
        try {
            long currentTime = System.currentTimeMillis()
            long lastCheck = lastCheckTime.get()
            long timeDifference = currentTime - lastCheck

            // Update last check time
            lastCheckTime.set(currentTime)

            // If time difference is significantly more than expected, we likely woke from sleep
            if (timeDifference > SLEEP_DETECTION_THRESHOLD_MS) {
                LogManager.getInstance().info(LogCategory.GENERAL, "[SleepWakeMonitor] Wake event detected! Time jump: " +
                    (timeDifference / 1000) + " seconds")

                // Update wake time
                lastWakeTime.set(currentTime)

                // Notify all listeners
                notifyWakeListeners()
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "[SleepWakeMonitor] Error checking for wake event: " + e.getMessage(), e)
        }
    }

    /**
     * Add a listener that will be called when a wake event is detected
     */
    void addWakeListener(Runnable listener) {
        if (listener != null) {
            wakeListeners.add(listener)
        }
    }

    /**
     * Notify all wake listeners
     */
    private void notifyWakeListeners() {
        for (Runnable listener : wakeListeners) {
            try {
                listener.run()
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "[SleepWakeMonitor] Error notifying wake listener: " + e.getMessage(), e)
            }
        }
    }

    /**
     * Get the time since last wake event
     */
    long getTimeSinceWake() {
        return System.currentTimeMillis() - lastWakeTime.get()
    }

}

