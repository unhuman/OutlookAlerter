#!/bin/bash

# Debug calendar events script for OutlookAlerter
# This script runs the application with debug mode and adds extensive calendar API diagnostics

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "Running OutlookAlerter in calendar diagnostics mode"
echo "This will show detailed information about calendar event retrieval"
echo "=============================================================="
echo "Current time: $(date)"
echo "Current timezone: $(date +%Z)"
echo "=============================================================="

# Set Java logging to show more details about the HTTP requests
JAVA_OPTS="-Djava.util.logging.config.file=$SCRIPT_DIR/logging.properties"

# Create a temporary logging properties file
cat > "$SCRIPT_DIR/logging.properties" << EOL
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=ALL
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
java.net.http.level=ALL
EOL

# Run the application with the proper classpath, debug flag, and Java options
java $JAVA_OPTS -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter --debug "$@"

# Clean up
rm "$SCRIPT_DIR/logging.properties"
