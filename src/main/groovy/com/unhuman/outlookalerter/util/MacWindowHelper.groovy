package com.unhuman.outlookalerter.util

import com.sun.jna.Platform
import groovy.transform.CompileStatic
import javax.swing.JFrame

/**
 * Helper class for macOS window management.
 *
 * NOTE: Native window handle access is blocked by Java's module system on Java 9+.
 * This class documents the issue and provides a simple interface that returns 0,
 * triggering the fallback approach in MacScreenFlasher which uses standard Swing methods.
 */
@CompileStatic
class MacWindowHelper {

    /**
     * Get the native window handle (NSWindow pointer) from a JFrame.
     *
     * On Java 9+ with macOS, native window handle access is blocked by the module system:
     * - JNA methods (getWindowPointer, getComponentPointer) return 0 or fail
     * - Reflection (AWTAccessor, peer fields) blocked by module encapsulation
     *
     * WORKAROUND: Standard Swing methods (setAlwaysOnTop + toFront + requestFocus) work
     * reliably without needing native window handles.
     *
     * This method always returns 0, triggering the fallback approach in MacScreenFlasher.
     *
     * @param frame The JFrame (not used)
     * @return Always returns 0 on macOS
     */
    static long getWindowHandle(JFrame frame) {
        if (!Platform.isMac()) return 0

        // Native window handle access is blocked by Java module system
        // Using standard Swing approach instead (works reliably)
        return 0
    }

}
