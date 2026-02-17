package com.unhuman.outlookalerter.core

import groovy.transform.CompileStatic
import com.unhuman.outlookalerter.util.LogManager
import com.unhuman.outlookalerter.util.LogCategory
import java.nio.channels.FileLock
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Handles single instance management for Outlook Alerter using file locks
 */
@CompileStatic
class SingleInstanceManager {
    private static final String LOCK_FILE = "instance.lock"
    private static final String APP_DIR = ".outlookalerter"
    private FileChannel channel
    private FileLock lock
    
    /**
     * Try to acquire a lock to ensure single instance.
     * @return true if lock was acquired (we're the first instance), false otherwise (another instance is running)
     */
    private volatile boolean lockAcquired = false

    boolean tryAcquireLock() {
        if (lockAcquired) {
            return true  // Already holding the lock
        }
        try {
            // First ensure the .outlookalerter directory exists
            File appDir = new File(System.getProperty("user.home"), APP_DIR)
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            
            // Create lock file in the .outlookalerter directory
            File lockFile = new File(appDir, LOCK_FILE)
            
            // Create FileChannel in READ_WRITE mode
            channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            )

            // Try to acquire an exclusive lock
            lock = channel.tryLock()
            
            if (lock == null) {
                // Another instance has the lock
                channel.close()
                channel = null
                return false
            }
            
            lockAcquired = true
            // Add shutdown hook to release it (only once since lockAcquired guard prevents re-entry)
            Runtime.runtime.addShutdownHook(new Thread({ releaseLock() } as Runnable))
            
            return true
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error checking for existing instance: ${e.message}")
            return false
        }
    }

    /**
     * Release the lock and clean up
     */
    void releaseLock() {
        try {
            if (lock != null) {
                lock.release()
                lock = null
            }
            if (channel != null) {
                channel.close()
                channel = null
            }
        } catch (Exception e) {
            LogManager.getInstance().error(LogCategory.GENERAL, "Error releasing lock: ${e.message}")
        }
    }
}
