package com.unhuman.outlookalerter.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages launchd plist for automatic crash recovery on macOS.
 */
public class LaunchdManager {

    private static final String PLIST_NAME = "com.unhuman.outlookalerter.plist";
    private static final String LAUNCH_AGENTS_DIR = System.getProperty("user.home") + "/Library/LaunchAgents";
    private static final String PLIST_PATH = LAUNCH_AGENTS_DIR + "/" + PLIST_NAME;

    /**
     * Check if launchd crash protection is currently enabled.
     */
    public static boolean isEnabled() {
        File plist = new File(PLIST_PATH);
        return plist.exists();
    }

    /**
     * Enable launchd crash protection.
     * Copies plist from app resources and loads it into launchd.
     */
    public static boolean enable() {
        try {
            // Check if already enabled
            if (isEnabled()) {
                return true;
            }

            // Try to load via launchctl (assumes plist is already at the target path)
            Process p = Runtime.getRuntime().exec(new String[]{
                "launchctl", "load", PLIST_PATH
            });
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[LaunchdManager] Crash protection enabled");
                return true;
            } else {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[LaunchdManager] Failed to enable crash protection (exit code: " + exitCode + ")");
                return false;
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                "[LaunchdManager] Error enabling crash protection: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Disable launchd crash protection.
     * Unloads plist from launchd.
     */
    public static boolean disable() {
        try {
            if (!isEnabled()) {
                return true;
            }

            Process p = Runtime.getRuntime().exec(new String[]{
                "launchctl", "unload", PLIST_PATH
            });
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[LaunchdManager] Crash protection disabled");
                return true;
            } else {
                LogManager.getInstance().info(LogCategory.GENERAL,
                    "[LaunchdManager] Failed to disable crash protection (exit code: " + exitCode + ")");
                return false;
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL,
                "[LaunchdManager] Error disabling crash protection: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the path to the plist file.
     */
    public static String getPlistPath() {
        return PLIST_PATH;
    }
}
