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
            return new MacScreenFlasher()
        } else {
            // Use cross-platform implementation for Windows, Linux, etc.
            println "Using cross-platform screen flasher for ${os}"
            return new CrossPlatformScreenFlasher()
        }
    }
}