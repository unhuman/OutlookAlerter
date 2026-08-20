package com.unhuman.outlookalerter.util;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Workaround for JDK-8357418: CVDisplayLink callback fires against invalid
 * JVM Metal rendering state in the first ~2-3 seconds after macOS wake-from-sleep,
 * causing EXC_BAD_ACCESS (KERN_INVALID_ADDRESS) → SIGABRT.
 *
 * On wake, this guard hides all registered Swing windows for GUARD_DURATION_MS,
 * preventing Java2D from issuing Metal draw calls through the stale CVDisplayLink.
 * After the guard period the windows are restored to their pre-wake visibility state.
 *
 * This is effective even on JDK 25, which contains only the retry-based partial fix
 * (JDK-8312198) for the rendering-stops case; the crash-on-callback race is still
 * present until JDK-8357418 (replace CVDisplayLink with CAMetalDisplayLink) is fixed.
 */
public class MacJava2DWakeGuard {

    static final int GUARD_DURATION_MS = 3000;

    private final List<Window> guardedWindows = new CopyOnWriteArrayList<>();
    private volatile boolean guardActive = false;

    public void registerWindow(Window window) {
        if (window != null) {
            guardedWindows.add(window);
        }
    }

    /**
     * Activates the wake guard. Safe to call from any thread.
     * Dispatches all Swing work to the EDT.
     */
    public void activateGuard() {
        SwingUtilities.invokeLater(() -> {
            if (guardActive) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[Java2DWakeGuard] Guard already active, resetting timer");
            }
            guardActive = true;

            List<Window> visibleWindows = new ArrayList<>();
            for (Window w : guardedWindows) {
                if (w.isVisible()) {
                    visibleWindows.add(w);
                    w.setVisible(false);
                }
            }

            LogManager.getInstance().info(LogCategory.GENERAL,
                "[Java2DWakeGuard] Hiding " + visibleWindows.size()
                + " window(s) for " + GUARD_DURATION_MS + "ms to prevent CVDisplayLink crash");

            Timer restoreTimer = new Timer(GUARD_DURATION_MS, evt -> {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[Java2DWakeGuard] Guard period elapsed — restoring "
                    + visibleWindows.size() + " window(s)");
                for (Window w : visibleWindows) {
                    w.setVisible(true);
                }
                guardActive = false;
            });
            restoreTimer.setRepeats(false);
            restoreTimer.start();
        });
    }

    public boolean isGuardActive() {
        return guardActive;
    }
}
