package com.unhuman.outlookalerter.util;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

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
    // Initialized to 0 so getTimeSinceWake() returns a large value at app startup.
    // Only actual system wake events update this, avoiding false "recently woke" delays at startup.
    private final AtomicLong lastWakeTime = new AtomicLong(0L);
    private final List<Runnable> wakeListeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService monitorExecutor;
    private volatile boolean monitoring = false;

    private CFNotificationCallback darwinWakeCallback;
    private Thread darwinNotifThread;
    private volatile Pointer darwinRunLoop;

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
            this::checkForWakeEvent,
            30,
            30,
            TimeUnit.SECONDS
        );

        darwinNotifThread = new Thread(() -> {
            try {
                NativeLibrary cf = NativeLibrary.getInstance("CoreFoundation");
                Pointer center = cf.getFunction("CFNotificationCenterGetDarwinNotifyCenter")
                    .invokePointer(new Object[0]);

                darwinRunLoop = cf.getFunction("CFRunLoopGetCurrent")
                    .invokePointer(new Object[0]);

                darwinWakeCallback = (c, obs, name, obj, info) -> {
                    LogManager.getInstance().info(LogCategory.GENERAL,
                        "[SleepWakeMonitor] Darwin wake notification received");
                    lastWakeTime.set(System.currentTimeMillis());
                    Thread notifyThread = new Thread(MacSleepWakeMonitor.this::notifyWakeListeners,
                        "SleepWakeNotify");
                    notifyThread.setDaemon(true);
                    notifyThread.start();
                };

                cf.getFunction("CFNotificationCenterAddObserver").invoke(new Object[]{
                    center,
                    Pointer.NULL,
                    darwinWakeCallback,
                    "com.apple.system.power.wake",
                    Pointer.NULL,
                    0
                });

                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[SleepWakeMonitor] Darwin wake notification registered");

                cf.getFunction("CFRunLoopRun").invoke(new Object[0]);
            } catch (Throwable t) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[SleepWakeMonitor] Darwin notification unavailable (" + t.getMessage()
                    + "), relying on polling fallback");
            }
        }, "SleepWakeNotifThread");
        darwinNotifThread.setDaemon(true);
        darwinNotifThread.start();

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
                Thread.currentThread().interrupt();
                monitorExecutor.shutdownNow();
            }
            monitorExecutor = null;
        }

        if (darwinRunLoop != null) {
            try {
                NativeLibrary cf = NativeLibrary.getInstance("CoreFoundation");
                cf.getFunction("CFRunLoopStop").invoke(new Object[]{ darwinRunLoop });
            } catch (Throwable ignored) {}
            darwinRunLoop = null;
        }
        if (darwinNotifThread != null) {
            darwinNotifThread.interrupt();
            darwinNotifThread = null;
        }
        darwinWakeCallback = null;

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

    interface CFNotificationCallback extends com.sun.jna.Callback {
        void invoke(Pointer center, Pointer observer, String name, Pointer object, Pointer userInfo);
    }
}
