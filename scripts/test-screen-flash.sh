#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}Testing Screen Flash Functionality${NC}"

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# First make sure the project is built
echo -e "${YELLOW}Building project...${NC}"
"$SCRIPT_DIR/build.sh"

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

echo -e "${YELLOW}Running screen flash test...${NC}"

# Clean and build first
mvn clean install

if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

# Run the test class directly using java command with proper classpath
JAR_NAME="OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
JAR_PATH="target/$JAR_NAME"
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}Could not find $JAR_PATH. Build may have failed or jar name may be incorrect.${NC}"
    exit 1
fi
java -cp "$JAR_PATH:target/test-classes" \
     com.unhuman.outlookalerter.ScreenFlashTest

# Capture exit code
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}Screen flash test completed successfully${NC}"
else
    echo -e "${RED}Screen flash test failed with exit code: $EXIT_CODE${NC}"
fi
