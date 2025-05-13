#!/bin/bash

# Run script for OutlookAlerter
# This script ensures the proper classpath is set when running the application

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

# Run the application with the proper classpath
java -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"