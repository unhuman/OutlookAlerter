#!/bin/zsh

# Get script directory and navigate to project root
SCRIPT_DIR="${0:A:h}"
cd "$SCRIPT_DIR/.."

# Set the test duration (default to 5 seconds if not specified)
TEST_DURATION=${1:-5}
echo "Testing flash duration of ${TEST_DURATION} seconds..."

# Create a simple Groovy script for testing
cat > /tmp/SimpleMacFlashTest.groovy << EOL
import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.util.MacScreenFlasher
import com.unhuman.outlookalerter.model.CalendarEvent
import java.time.ZonedDateTime

// Create config manager
ConfigManager configManager = new ConfigManager(System.getProperty("user.home") + "/.outlookalerter/config.properties")
configManager.loadConfiguration()

// Update flash duration setting
configManager.updateFlashDurationSeconds(${TEST_DURATION})
println "Set flash duration to ${TEST_DURATION} seconds"

// Create a test event
ZonedDateTime now = ZonedDateTime.now()
ZonedDateTime start = now.plusMinutes(1)
CalendarEvent event = new CalendarEvent(
    id: "test-event",
    subject: "Test Flash Duration: ${TEST_DURATION} seconds",
    startTime: start,
    endTime: start.plusMinutes(30),
    organizer: "Test",
    location: "Test Location",
    isOnlineMeeting: true,
    onlineMeetingUrl: "https://example.com",
    responseStatus: "accepted",
    calendarName: "Test Calendar"
)

// Create and use Mac screen flasher directly
println "Creating MacScreenFlasher..."
def flasher = new MacScreenFlasher()
println "Flashing screen..."
flasher.flash(event)
println "Flash started, should last exactly ${TEST_DURATION} seconds"

// Keep script running to observe full flash
println "Waiting for flash to complete..."
Thread.sleep((${TEST_DURATION} + 3) * 1000)
println "Test completed"
EOL

# Run the test
java -cp "target/OutlookAlerter-2.0.0-jar-with-dependencies.jar" groovy.ui.GroovyMain /tmp/SimpleMacFlashTest.groovy
