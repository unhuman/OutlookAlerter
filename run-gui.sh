#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Starting OutlookAlerter in GUI mode${NC}"

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Use Maven's target directory for the executable jar
JAR_PATH="$SCRIPT_DIR/target/OutlookAlerter-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java is not installed or not in the PATH.${NC}"
    echo "Please install Java to run this application."
    exit 1
fi

# Check if jar exists, if not try to build
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${YELLOW}Executable jar not found, running build...${NC}"
    "$SCRIPT_DIR/build.sh"
    if [ $? -ne 0 ]; then
        echo -e "${RED}Build failed, cannot run application${NC}"
        exit 1
    fi
fi

# Set base Java options
JAVA_OPTS="-Djava.awt.headless=false"

# For debugging purposes
if [[ "$*" == *"--debug"* ]]; then
    echo -e "${YELLOW}Debug mode enabled - adding Java debug options${NC}"
    JAVA_OPTS="${JAVA_OPTS} -Dgroovy.debug=true -Dapple.laf.useScreenMenuBar=true"
fi

# Handle different operating systems
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo -e "${YELLOW}Detected macOS - using platform-specific settings${NC}"
    
    # Export critical environment variables for macOS GUI apps
    export JAVA_AWT_HEADLESS=false
    export AWT_TOOLKIT=CToolkit
    
    # These options help ensure Swing components display properly on macOS
    JAVA_OPTS="${JAVA_OPTS} -Dapple.awt.UIElement=false"
    JAVA_OPTS="${JAVA_OPTS} -Dapple.laf.useScreenMenuBar=true"
    JAVA_OPTS="${JAVA_OPTS} -Dapple.awt.application.appearance=system"
    JAVA_OPTS="${JAVA_OPTS} -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel"
    JAVA_OPTS="${JAVA_OPTS} -Dsun.java2d.opengl=false -Dsun.java2d.metal=false"
    
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo -e "${YELLOW}Detected Linux - using platform-specific settings${NC}"
    JAVA_OPTS="${JAVA_OPTS} -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true"
elif [[ "$OSTYPE" == "msys"* || "$OSTYPE" == "cygwin"* || "$OSTYPE" == "win"* ]]; then
    echo -e "${YELLOW}Detected Windows - using platform-specific settings${NC}"
else
    echo -e "${YELLOW}Using generic platform settings${NC}"
fi

# Run the application with selected options
echo -e "${GREEN}Running OutlookAlerter...${NC}"
java ${JAVA_OPTS} -jar "$JAR_PATH" --gui "$@"

# Check exit code
exit_code=$?
if [ $exit_code -ne 0 ]; then
    echo -e "${RED}OutlookAlerter exited with an error (code: $exit_code).${NC}"
    echo "Check the output above for more details."
    exit $exit_code
else
    echo -e "${GREEN}OutlookAlerter exited successfully.${NC}"
fi
