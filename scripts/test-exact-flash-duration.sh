#!/bin/zsh

# Get script directory
SCRIPT_DIR="${0:A:h}"
cd "$SCRIPT_DIR/.."

# Set the flash duration to test
TEST_DURATION=22
if [ $# -eq 1 ]; then
  TEST_DURATION=$1
fi

echo "Testing flash duration of ${TEST_DURATION} seconds..."

# Create a test script
cat > /tmp/TestExactFlashDuration.groovy << EOL
import com.unhuman.outlookalerter.core.ConfigManager
import com.unhuman.outlookalerter.model.CalendarEvent
import com.unhuman.outlookalerter.util.ScreenFlasher
import com.unhuman.outlookalerter.util.ScreenFlasherFactory
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Initialize configuration
String configPath = System.getProperty("user.home") + "/.outlookalerter/config.properties"
ConfigManager configManager = new ConfigManager(configPath)
configManager.loadConfiguration()

// Update flash duration
int duration = ${TEST_DURATION}
configManager.updateFlashDurationSeconds(duration)
println "Set flash duration to \${duration} seconds"

// Create event
ZonedDateTime now = ZonedDateTime.now()
ZonedDateTime start = now.plusMinutes(1)
ZonedDateTime end = start.plusMinutes(30)

CalendarEvent event = new CalendarEvent(
    id: "test-event",
    subject: "Flash Duration Test",
    startTime: start,
    endTime: end,
    organizer: "Test System",
    location: "Test Location",
    isOnlineMeeting: true,
    onlineMeetingUrl: "https://example.com",
    responseStatus: "accepted",
    calendarName: "Test Calendar"
)

// Create screen flasher and flash
println "Creating screen flasher..."
ScreenFlasher flasher = ScreenFlasherFactory.createScreenFlasher()

// Record start time
ZonedDateTime flashStart = ZonedDateTime.now()
println "Starting flash at \${flashStart}"

flasher.flash(event)
println "Flash initiated - should last exactly \${duration} seconds"

// Wait until we're sure the flash is complete
Thread.sleep((duration + 3) * 1000)
ZonedDateTime now2 = ZonedDateTime.now()

// Calculate actual duration
long actualSeconds = ChronoUnit.SECONDS.between(flashStart, now2) - 3
println "Approximate actual duration: \${actualSeconds} seconds"
println "Expected duration: \${duration} seconds"
println "Test completed"
EOL

# Run the test
java -cp "target/OutlookAlerter-2.0.0-jar-with-dependencies.jar" groovy.ui.GroovyMain /tmp/TestExactFlashDuration.groovy
