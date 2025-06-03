package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic
import com.unhuman.outlookalerter.util.MacScreenFlasher
import com.unhuman.outlookalerter.util.WindowsScreenFlasher
import com.unhuman.outlookalerter.util.CrossPlatformScreenFlasher

/**
 * Factory to create the appropriate ScreenFlasher for the current operating system
 */
@CompileStatic
class ScreenFlasherFactory {

    /**
     * Creates a ScreenFlasher appropriate for the current operating system
     */
    static ScreenFlasher createScreenFlasher() {
        String os = System.getProperty("os.name").toLowerCase()
        
        if (os.contains("mac")) {
            // Use Mac-specific implementation if we're on a Mac
            println "Using Mac-specific screen flasher"
            return new MacScreenFlasher()
        } else if (os.contains("win")) {
            // Use Windows-specific implementation if we're on Windows
            println "Using Windows-specific screen flasher"
            return new WindowsScreenFlasher()
        } else {
            // Use cross-platform implementation for Linux, etc.
            println "Using cross-platform screen flasher for ${os}"
            return new CrossPlatformScreenFlasher()
        }
    }
}