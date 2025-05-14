#!/bin/bash

# Run script for OutlookAlerter
# This script ensures the proper classpath is set when running the application

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Check if running in console mode
if [[ "$*" == *"--console"* ]]; then
    echo "Running OutlookAlerter in console mode..."
else
    echo "Running OutlookAlerter in GUI mode..."
    # Add -Dapple.awt.UIElement=true for macOS to avoid dock icon when running in background
    if [[ "$OSTYPE" == "darwin"* ]]; then
        java -Dapple.awt.UIElement=true -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
    else
        java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
    fi
    exit $?
fi

# Run in console mode
java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter --console "$@"