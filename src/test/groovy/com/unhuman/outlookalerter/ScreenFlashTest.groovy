package com.unhuman.outlookalerter

import java.time.*
import groovy.transform.CompileStatic
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.util.ScreenFlasherFactory

/**
 * Simple test script to verify screen flashing behavior
 */
@CompileStatic
class ScreenFlashTest {
    static void testFlash() {
        println "Creating test event..."
        
        // Create a test event happening now
        ZonedDateTime now = ZonedDateTime.now()
        CalendarEvent testEvent = new CalendarEvent()
        testEvent.with {
            id = "test-flash-" + System.currentTimeMillis()
            subject = "⚠️ SCREEN FLASH TEST ⚠️\nIf you can see this, the flash system is working"
            startTime = now
            endTime = now.plusMinutes(30)
            isOnlineMeeting = true
            organizer = "Screen Flash Test"
            responseStatus = "Testing"
            calendarName = "Test"
        }

        println "Creating screen flasher..."
        ScreenFlasher screenFlasher = ScreenFlasherFactory.createScreenFlasher()
        
        println "Testing screen flash - your screens should flash now..."
        screenFlasher.flash(testEvent)
        
        // Keep script alive while flashing occurs
        println "Waiting for flash sequence to complete..."
        Thread.sleep(6000)
        println "Test complete."
    }
    
    // Main method for direct testing
    static void main(String[] args) {
        testFlash()
    }
}
