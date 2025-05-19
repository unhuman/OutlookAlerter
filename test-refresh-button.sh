#!/bin/bash

# This script tests the "Refresh Now" button functionality with token validation
# It validates that the token validation status is properly displayed

echo "Testing 'Refresh Now' button with token validation feedback..."

# Ensure the TOKEN_PATH variable is set to a predictable location
export TOKEN_PATH="$HOME/.outlookalerter/config.properties"

# First, make sure we have an app built
./build.sh

# Run the app with debug output enabled and redirect to a log file
java -Dgroovy.debug=true -cp "dist/OutlookAlerter.jar:lib/*" com.unhuman.outlookalerter.OutlookAlerter --gui 2>&1 | tee outlookalerter-debug.log

echo "Test complete. Check the token validation messages in the UI and log file."
