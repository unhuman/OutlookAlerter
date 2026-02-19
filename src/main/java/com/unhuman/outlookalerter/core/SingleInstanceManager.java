package com.unhuman.outlookalerter.core;

import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.io.File;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

/**
 * Handles single instance management for Outlook Alerter using file locks
 */
public class SingleInstanceManager {
    private static final String LOCK_FILE = "instance.lock";
    private static final String APP_DIR = ".outlookalerter";
    private FileChannel channel;
    private FileLock lock;
    private volatile boolean lockAcquired = false;

    public boolean tryAcquireLock() {
        if (lockAcquired) {
            return true;
        }
        try {
            File appDir = new File(System.getProperty("user.home"), APP_DIR);
            if (!appDir.exists()) {
                appDir.mkdirs();
            }

            File lockFile = new File(appDir, LOCK_FILE);

            channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            );

            lock = channel.tryLock();

            if (lock == null) {
                channel.close();
                channel = null;
                return false;
            }

            lockAcquired = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::releaseLock));

            return true;
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error checking for existing instance: " + e.getMessage());
            return false;
        }
    }

    public void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error releasing lock: " + e.getMessage());
        }
    }
}
