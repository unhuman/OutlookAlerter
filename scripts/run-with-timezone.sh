#!/bin/bash

# Run script for OutlookAlerter with timezone parameter
# This script ensures the proper classpath is set when running the application
# and adds a default timezone parameter

# Check if a timezone parameter was provided
if [ "$1" == "" ]; then
    echo "Usage: ./run-with-timezone.sh <timezone>"
    echo "Example: ./run-with-timezone.sh America/New_York"
    echo ""
    echo "Available timezone examples:"
    echo "  America/New_York, America/Los_Angeles, America/Chicago"
    echo "  Europe/London, Europe/Paris, Europe/Berlin"
    echo "  Asia/Tokyo, Asia/Singapore, Asia/Hong_Kong"
    echo "  Australia/Sydney, Pacific/Auckland"
    exit 1
fi

TIMEZONE="$1"
shift  # Remove the first argument (timezone)

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "Running OutlookAlerter with timezone: $TIMEZONE"

# Run the application with the proper classpath and timezone parameter
java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter --timezone "$TIMEZONE" "$@"
