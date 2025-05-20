#!/bin/zsh

# Set up classpath for required libraries
SCRIPT_DIR="${0:A:h}"  # Get absolute path of script directory
CP="${SCRIPT_DIR}/build/classes:${SCRIPT_DIR}/lib/*"

# Run the test with Groovy
groovy -cp "$CP" - << 'EOG'
import com.unhuman.outlookalerter.*
import java.time.*

// Create a test event
def testEvent = new CalendarEvent(
    id: "test-123",
    subject: "⚠️ TEST ALERT ⚠️\nThis is a screen flash test",
    start: ZonedDateTime.now(),
    end: ZonedDateTime.now().plusMinutes(30),
    isOnlineMeeting: true,
    organizer: "Test Script",
    responseStatus: "Test"
)

// Create screen flasher
def screenFlasher = ScreenFlasherFactory.createScreenFlasher()

println "Testing screen flash - your screens should flash now..."
screenFlasher.flash(testEvent)

// Keep script running while flash animation completes
Thread.sleep(5000)
println "Test complete."
EOG
