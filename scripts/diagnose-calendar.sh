#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Running OutlookAlerter Calendar Diagnostics${NC}"

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Use Maven's target directory for the executable jar
JAR_PATH=$(ls $SCRIPT_DIR/../target/OutlookAlerter-*-jar-with-dependencies.jar 2>/dev/null | head -n 1)

# Check if jar exists, if not try to build
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${YELLOW}Executable jar not found, running build...${NC}"
    "$SCRIPT_DIR/build.sh"
    if [ $? -ne 0 ]; then
        echo -e "${RED}Build failed, cannot run diagnostics${NC}"
        exit 1
    fi
fi

# Print diagnostic header
echo -e "${YELLOW}=============================================================="
echo "Calendar Diagnostics Information"
echo "Current time: $(date)"
echo "Current timezone: $(date +%Z)"
echo "==============================================================${NC}"

# Create diagnostics directory with timestamp
DIAG_DIR="diagnostics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIAG_DIR"

# Create logging properties for detailed HTTP logging
LOGGING_PROPS="$DIAG_DIR/logging.properties"
cat > "$LOGGING_PROPS" << EOL
handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler
java.util.logging.FileHandler.pattern=$DIAG_DIR/calendar-debug.log
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.FileHandler.level=ALL
java.util.logging.ConsoleHandler.level=ALL
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
java.net.http.level=ALL
EOL

# Set Java options for diagnostics
JAVA_OPTS="-Djava.util.logging.config.file=$LOGGING_PROPS -Doutlookalerter.diagnostic.dir=$DIAG_DIR"

echo -e "${GREEN}Running diagnostic tests...${NC}"
echo -e "${BLUE}Diagnostic logs will be saved to: $DIAG_DIR${NC}"

# Run the application with diagnostic options
java $JAVA_OPTS -jar "$JAR_PATH" --debug --diagnostics "$@"

# Check result
if [ $? -eq 0 ]; then
    echo -e "\n${GREEN}Diagnostics completed successfully${NC}"
    echo -e "${BLUE}Diagnostic files are available in: $DIAG_DIR${NC}"
else
    echo -e "\n${RED}Diagnostics failed${NC}"
    echo "Check the logs in $DIAG_DIR for details"
fi
