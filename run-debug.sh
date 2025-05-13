#!/bin/bash

# Debug run script for OutlookAlerter
# This script runs the application with debug mode enabled

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "Running OutlookAlerter in debug mode"
echo "This will show detailed timezone information for each event"

# Run the application with the proper classpath and debug flag
java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter --debug "$@"
