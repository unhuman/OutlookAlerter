#!/bin/bash

# Debug run script for OutlookAlerter
# This script runs the application with debug mode enabled

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Parse command line arguments
DIAGNOSTICS_MODE=false

for arg in "$@"; do
  case $arg in
    --diagnostics)
      DIAGNOSTICS_MODE=true
      shift # Remove --diagnostics from processing
      ;;
  esac
done

echo "Running OutlookAlerter in debug mode"
echo "This will show detailed timezone information for each event"
echo "Current system timezone: $(date +%Z)"
echo "Current date and time: $(date)"

if [ "$DIAGNOSTICS_MODE" = true ]; then
  echo ""
  echo "=== RUNNING IN COMPREHENSIVE DIAGNOSTICS MODE ==="
  echo "This will run all diagnostic tools and generate a detailed report"
  echo ""
  
  # Create a diagnostics directory
  DIAG_DIR="$SCRIPT_DIR/diagnostics-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$DIAG_DIR"
  echo "Creating diagnostics directory: $DIAG_DIR"
  
  # Run all diagnostic tools
  echo "Running test-calendar-events.sh..."
  "$SCRIPT_DIR/test-calendar-events.sh" > "$DIAG_DIR/calendar-events-test.log" 2>&1
  
  echo "Running enhanced-calendar-diagnostics.sh..."
  "$SCRIPT_DIR/enhanced-calendar-diagnostics.sh" > "$DIAG_DIR/enhanced-diagnostics.log" 2>&1
  
  echo "Running diagnose-multi-calendar.sh..."
  "$SCRIPT_DIR/diagnose-multi-calendar.sh" > "$DIAG_DIR/multi-calendar-diagnostics.log" 2>&1
  
  echo "Running test-timezones.sh..."
  "$SCRIPT_DIR/test-timezones.sh" > "$DIAG_DIR/timezone-test.log" 2>&1
  
  echo ""
  echo "Diagnostic tools execution complete. Reports saved to: $DIAG_DIR"
  echo "Now running the application with debug mode..."
  echo ""
fi

# Run the application with the proper classpath and debug flag
java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter --debug "$@"

if [ "$DIAGNOSTICS_MODE" = true ]; then
  echo ""
  echo "Application run complete. All diagnostic information has been saved to: $DIAG_DIR"
  echo "Review the logs in this directory to diagnose any issues with missing calendar events."
fi
