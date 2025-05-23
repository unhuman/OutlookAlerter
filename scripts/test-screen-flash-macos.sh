#!/bin/bash

# Script to test screen flashing on macOS without requiring an actual meeting
# This will test the fix to eliminate the red square in the menu bar

echo "Starting test for macOS screen flashing (without red square in menu bar)..."

# Navigate to project directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR" || exit 1

# Compile the test class if needed
if [ ! -f target/test-classes/com/unhuman/outlookalerter/TestScreenFlash.class ]; then
    echo "Compiling test classes..."
    mvn test-compile
fi

# Run the test using Maven
echo "Running screen flash test..."
mvn exec:java -Dexec.mainClass="com.unhuman.outlookalerter.TestScreenFlash" -Dexec.classpathScope=test

# Print completion message
echo "Screen flash test completed."
echo "The screen should have flashed without showing a red square in the menu bar on macOS."
