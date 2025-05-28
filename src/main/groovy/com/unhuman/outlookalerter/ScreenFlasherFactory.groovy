package com.unhuman.outlookalerter

import groovy.transform.CompileStatic

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
            return new com.unhuman.outlookalerter.MacScreenFlasher()
        } else if (os.contains("win")) {
            // Use Windows-specific implementation if we're on Windows
            println "Using Windows-specific screen flasher"
            return new com.unhuman.outlookalerter.WindowsScreenFlasher()
        } else {
            // Use cross-platform implementation for Linux, etc.
            println "Using cross-platform screen flasher for ${os}"
            return new com.unhuman.outlookalerter.CrossPlatformScreenFlasher()
        }
    }
}