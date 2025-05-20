package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
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
    boolean tryAcquireLock() {
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
                return false
            }
            
            // We got the lock - add shutdown hook to release it
            Runtime.runtime.addShutdownHook(new Thread({ releaseLock() } as Runnable))
            
            return true
        } catch (Exception e) {
            println "Error checking for existing instance: ${e.message}"
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
            println "Error releasing lock: ${e.message}"
        }
    }
}
