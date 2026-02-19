package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

/**
 * Factory to create the appropriate ScreenFlasher for the current operating system
 */
public class ScreenFlasherFactory {

    public static ScreenFlasher createScreenFlasher() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Using Mac-specific screen flasher");
            return new MacScreenFlasher();
        } else if (os.contains("win")) {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Using Windows-specific screen flasher");
            return new WindowsScreenFlasher();
        } else {
            LogManager.getInstance().info(LogCategory.ALERT_PROCESSING, "Using cross-platform screen flasher for " + os);
            return new CrossPlatformScreenFlasher();
        }
    }
}
