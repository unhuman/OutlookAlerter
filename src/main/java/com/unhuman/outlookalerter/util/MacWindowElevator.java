package com.unhuman.outlookalerter.util;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * Native macOS elevation and visibility diagnostics for alert windows
 * (flash, banner overlay, join-meeting dialog).
 *
 * <p><b>Why this exists:</b> Java's {@code setAlwaysOnTop(true)} only places a window at
 * {@code NSFloatingWindowLevel}, confined to the Space it was created on. Since macOS 14,
 * {@code activateIgnoringOtherApps:} is a request the system may refuse while the user is
 * working in another app, so a tray-only (LSUIElement) app cannot rely on activation to
 * surface its windows. The result was alerts that Java reported as {@code visible=true}
 * but that the user never saw (behind the frontmost app, or on another Space / full-screen app).
 *
 * <p>This class bypasses activation entirely: for every visible alert window it sets a
 * window level above normal app windows, the menu bar and the Dock, allows the window on
 * all Spaces and over full-screen apps, and calls {@code orderFrontRegardless} (which works
 * for inactive apps). It also reports, after the fact, whether each alert window is actually
 * on the active Space and un-occluded, so the log shows whether the user could see the alert.
 *
 * <p>Alert windows are identified by their (unique) window titles, so no JDK internals are
 * needed. All AppKit calls are dispatched to the main thread via GCD.
 */
public final class MacWindowElevator {

    /** Title of the full-screen flash windows (undecorated, so never shown to the user). */
    public static final String FLASH_WINDOW_TITLE = "⚠️ Meeting Alert ⚠️";
    /** Title of the transparent banner overlay windows (undecorated, so never shown to the user). */
    public static final String BANNER_WINDOW_TITLE = "OutlookAlerter Alert Banner";
    /** Title of the join-meeting dialogs (visible in the dialog title bar). */
    public static final String DIALOG_WINDOW_TITLE = "Meeting Alert";

    // NSWindowCollectionBehavior bits
    static final long CAN_JOIN_ALL_SPACES   = 1L << 0;
    static final long MOVE_TO_ACTIVE_SPACE  = 1L << 1;
    static final long FULL_SCREEN_PRIMARY   = 1L << 7;
    static final long FULL_SCREEN_AUXILIARY = 1L << 8;
    static final long FULL_SCREEN_NONE      = 1L << 9;

    // NSWindowOcclusionState bit: set when any part of the window is visible on screen
    static final long OCCLUSION_STATE_VISIBLE = 1L << 1;

    /**
     * Alert window kinds, ordered bottom to top. Levels start at kCGStatusWindowLevel (25),
     * which is above NSFloatingWindowLevel (3), the Dock (20) and the main menu (24), and keep
     * the required z-order: flash → banner → dialog.
     */
    enum AlertWindowKind {
        FLASH(25), BANNER(26), DIALOG(27);

        final long level;

        AlertWindowKind(long level) { this.level = level; }
    }

    /** Returns the alert window kind for an NSWindow title, or null for non-alert windows. */
    static AlertWindowKind classifyTitle(String title) {
        if (title == null) return null;
        switch (title) {
            case FLASH_WINDOW_TITLE:  return AlertWindowKind.FLASH;
            case BANNER_WINDOW_TITLE: return AlertWindowKind.BANNER;
            case DIALOG_WINDOW_TITLE: return AlertWindowKind.DIALOG;
            default: return null;
        }
    }

    /**
     * Collection behavior for an elevated alert window: joins all Spaces and may appear over
     * full-screen apps. Clears bits that are mutually exclusive with those.
     */
    static long elevatedCollectionBehavior(long current) {
        long cleared = current & ~(MOVE_TO_ACTIVE_SPACE | FULL_SCREEN_PRIMARY | FULL_SCREEN_NONE);
        return cleared | CAN_JOIN_ALL_SPACES | FULL_SCREEN_AUXILIARY;
    }

    /** True if a window on the active Space with this occlusion state is (at least partly) visible. */
    static boolean isUserVisible(boolean onActiveSpace, long occlusionState) {
        return onActiveSpace && (occlusionState & OCCLUSION_STATE_VISIBLE) != 0;
    }

    /** Builds the one-line visibility summary written to the log. */
    static String formatVisibilityReport(String context, String eventsDescription, boolean appActive,
                                         String frontmostApp, int totalWindows, int visibleWindows,
                                         String windowDetails) {
        String verdict;
        if (totalWindows == 0) {
            verdict = "NO ALERT WINDOWS ON SCREEN";
        } else if (visibleWindows == 0) {
            verdict = "ALERT NOT VISIBLE (all " + totalWindows + " window(s) occluded or on another Space)";
        } else {
            verdict = "visible (" + visibleWindows + "/" + totalWindows + " window(s) un-occluded on active Space)";
        }
        return "[AlertVisibility] " + context + ": " + verdict
            + " | appActive=" + appActive
            + " frontmostApp=" + frontmostApp
            + " | events: " + eventsDescription
            + (windowDetails == null || windowDetails.isEmpty() ? "" : " | windows: " + windowDetails);
    }

    // ───────── Native plumbing ─────────

    private interface MainQueueTask extends Callback {
        void invoke(Pointer context);
    }

    private static final Map<Long, Runnable> PENDING_TASKS = new ConcurrentHashMap<>();
    private static final AtomicLong TASK_IDS = new AtomicLong();

    // Held in a static field: a JNA callback must stay strongly reachable while GCD may invoke it.
    private static final MainQueueTask TRAMPOLINE = context -> {
        Runnable task = PENDING_TASKS.remove(Pointer.nativeValue(context));
        if (task != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                    "MacWindowElevator: main-thread task failed: " + t.getMessage());
            }
        }
    };

    private static final class Objc {
        final Function getClass;
        final Function registerName;
        final Function msgSend;
        final Function dispatchAsync;
        final Function dispatchAfter;
        final Function dispatchTime;
        final Pointer mainQueue;

        Objc() {
            NativeLibrary objc = NativeLibrary.getInstance("objc");
            NativeLibrary system = NativeLibrary.getInstance("System");
            getClass = objc.getFunction("objc_getClass");
            registerName = objc.getFunction("sel_registerName");
            msgSend = objc.getFunction("objc_msgSend");
            dispatchAsync = system.getFunction("dispatch_async_f");
            dispatchAfter = system.getFunction("dispatch_after_f");
            dispatchTime = system.getFunction("dispatch_time");
            // dispatch_get_main_queue() is a macro for &_dispatch_main_q
            mainQueue = system.getGlobalVariableAddress("_dispatch_main_q");
        }

        Pointer cls(String name) { return getClass.invokePointer(new Object[]{name}); }

        private Object[] argv(Pointer receiver, String selector, Object... args) {
            Object[] all = new Object[args.length + 2];
            all[0] = receiver;
            all[1] = registerName.invokePointer(new Object[]{selector});
            System.arraycopy(args, 0, all, 2, args.length);
            return all;
        }

        Pointer ptr(Pointer receiver, String selector, Object... args) {
            return msgSend.invokePointer(argv(receiver, selector, args));
        }

        long lng(Pointer receiver, String selector, Object... args) {
            return msgSend.invokeLong(argv(receiver, selector, args));
        }

        boolean bool(Pointer receiver, String selector) {
            // BOOL is returned in the low byte; upper bits are unspecified on arm64
            return (msgSend.invokeInt(argv(receiver, selector)) & 0xFF) != 0;
        }

        void call(Pointer receiver, String selector, Object... args) {
            msgSend.invokeVoid(argv(receiver, selector, args));
        }

        String string(Pointer nsString) {
            if (nsString == null) return null;
            Pointer utf8 = ptr(nsString, "UTF8String");
            return utf8 != null ? utf8.getString(0, "UTF-8") : null;
        }
    }

    private static volatile Objc objc;
    private static volatile boolean initAttempted = false;

    private MacWindowElevator() {}

    private static Objc objc() {
        if (!initAttempted) {
            synchronized (MacWindowElevator.class) {
                if (!initAttempted) {
                    try {
                        if (System.getProperty("os.name", "").toLowerCase().contains("mac")
                                && !GraphicsEnvironment.isHeadless()) {
                            objc = new Objc();
                        }
                    } catch (Throwable t) {
                        LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING,
                            "MacWindowElevator: native elevation unavailable: " + t.getMessage());
                    } finally {
                        initAttempted = true;
                    }
                }
            }
        }
        return objc;
    }

    /** True when native elevation can be used (macOS, non-headless, native symbols resolved). */
    public static boolean isAvailable() {
        return objc() != null;
    }

    private static void runOnMain(Objc o, Runnable task, long delayMs) {
        long id = TASK_IDS.incrementAndGet();
        PENDING_TASKS.put(id, task);
        Pointer context = new Pointer(id);
        try {
            if (delayMs <= 0) {
                o.dispatchAsync.invokeVoid(new Object[]{o.mainQueue, context, TRAMPOLINE});
            } else {
                long when = o.dispatchTime.invokeLong(new Object[]{0L, delayMs * 1_000_000L}); // DISPATCH_TIME_NOW + delay
                o.dispatchAfter.invokeVoid(new Object[]{when, o.mainQueue, context, TRAMPOLINE});
            }
        } catch (Throwable t) {
            PENDING_TASKS.remove(id);
            LogManager.getInstance().error(LogCategory.ALERT_PROCESSING,
                "MacWindowElevator: could not dispatch to main thread: " + t.getMessage());
        }
    }

    private static final class AlertWindow {
        final Pointer window;
        final AlertWindowKind kind;

        AlertWindow(Pointer window, AlertWindowKind kind) {
            this.window = window;
            this.kind = kind;
        }
    }

    /** Visible alert windows of this app, bottom (flash) to top (dialog). Main thread only. */
    private static List<AlertWindow> findVisibleAlertWindows(Objc o) {
        List<AlertWindow> result = new ArrayList<>();
        Pointer app = o.ptr(o.cls("NSApplication"), "sharedApplication");
        Pointer windows = o.ptr(app, "windows");
        long count = o.lng(windows, "count");
        for (long i = 0; i < count; i++) {
            Pointer window = o.ptr(windows, "objectAtIndex:", i);
            if (window == null) continue;
            AlertWindowKind kind = classifyTitle(o.string(o.ptr(window, "title")));
            if (kind != null && o.bool(window, "isVisible")) {
                result.add(new AlertWindow(window, kind));
            }
        }
        result.sort(Comparator.comparing(w -> w.kind));
        return result;
    }

    /**
     * Raises every visible alert window above all normal app windows, on all Spaces and over
     * full-screen apps, without requiring app activation. Safe to call from any thread and
     * repeatedly (e.g. from the flash elevation timer); no-op when unavailable.
     *
     * @param logResult whether to log a summary (pass false for periodic re-assertion)
     */
    public static void elevateAlertWindows(boolean logResult) {
        Objc o = objc();
        if (o == null) return;
        runOnMain(o, () -> {
            int[] counts = new int[AlertWindowKind.values().length];
            for (AlertWindow w : findVisibleAlertWindows(o)) {
                o.call(w.window, "setLevel:", w.kind.level);
                long behavior = o.lng(w.window, "collectionBehavior");
                o.call(w.window, "setCollectionBehavior:", elevatedCollectionBehavior(behavior));
                o.call(w.window, "orderFrontRegardless");
                counts[w.kind.ordinal()]++;
            }
            if (logResult) {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING,
                    "MacWindowElevator: elevated alert windows (flash=" + counts[0] + " banner=" + counts[1]
                    + " dialog=" + counts[2] + ") to status level, all Spaces, over full-screen apps");
            }
        }, 0);
    }

    /**
     * Test support: returns the native level and collection behavior of every visible alert
     * window of the given kind, as {level, collectionBehavior} pairs. Blocks up to 5 s waiting
     * for the main thread. Returns an empty list when unavailable.
     */
    static List<long[]> queryAlertWindowState(AlertWindowKind kind) throws InterruptedException {
        Objc o = objc();
        List<long[]> result = new java.util.concurrent.CopyOnWriteArrayList<>();
        if (o == null) return result;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        runOnMain(o, () -> {
            try {
                for (AlertWindow w : findVisibleAlertWindows(o)) {
                    if (w.kind == kind) {
                        result.add(new long[]{o.lng(w.window, "level"), o.lng(w.window, "collectionBehavior")});
                    }
                }
            } finally {
                done.countDown();
            }
        }, 0);
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        return result;
    }

    /**
     * After {@code delayMs}, logs whether the alert windows are actually visible to the user
     * (on the active Space and not fully occluded), plus whether the app became active and
     * which app was frontmost. Logs a WARN when no alert window is visible.
     *
     * @param context           short label, e.g. "flash" or "join dialog J3"
     * @param eventsDescription the events being alerted (see CalendarEvent.describeForLog)
     */
    public static void reportAlertWindowVisibility(String context, String eventsDescription, long delayMs) {
        Objc o = objc();
        if (o == null) return;
        runOnMain(o, () -> {
            Pointer app = o.ptr(o.cls("NSApplication"), "sharedApplication");
            boolean appActive = o.bool(app, "isActive");
            Pointer workspace = o.ptr(o.cls("NSWorkspace"), "sharedWorkspace");
            Pointer front = o.ptr(workspace, "frontmostApplication");
            String frontName = front != null ? o.string(o.ptr(front, "localizedName")) : null;

            List<AlertWindow> windows = findVisibleAlertWindows(o);
            int visible = 0;
            StringBuilder details = new StringBuilder();
            for (AlertWindow w : windows) {
                boolean onActiveSpace = o.bool(w.window, "isOnActiveSpace");
                long occlusion = o.lng(w.window, "occlusionState");
                boolean userVisible = isUserVisible(onActiveSpace, occlusion);
                if (userVisible) visible++;
                if (details.length() > 0) details.append("; ");
                details.append(w.kind.name().toLowerCase())
                    .append("(level=").append(o.lng(w.window, "level"))
                    .append(" activeSpace=").append(onActiveSpace)
                    .append(" unoccluded=").append((occlusion & OCCLUSION_STATE_VISIBLE) != 0)
                    .append(')');
            }

            String report = formatVisibilityReport(context, eventsDescription, appActive,
                frontName != null ? frontName : "unknown", windows.size(), visible, details.toString());
            if (windows.isEmpty() || visible == 0) {
                LogManager.getInstance().warn(LogCategory.ALERT_PROCESSING, report);
            } else {
                LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, report);
            }
        }, delayMs);
    }
}
