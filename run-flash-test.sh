#!/bin/zsh

# Get script directory
SCRIPT_DIR="${0:A:h}"

# Ensure build directories exist
mkdir -p "$SCRIPT_DIR/build/classes"

# Compile the test and required classes
echo "Compiling test class..."
groovyc -cp "$SCRIPT_DIR/lib/*" \
  -d "$SCRIPT_DIR/build/classes" \
  src/com/unhuman/outlookalerter/CalendarEvent.groovy \
  src/com/unhuman/outlookalerter/ScreenFlasher.groovy \
  src/com/unhuman/outlookalerter/ScreenFlasherFactory.groovy \
  src/com/unhuman/outlookalerter/MacScreenFlasher.groovy \
  src/com/unhuman/outlookalerter/WindowsScreenFlasher.groovy \
  src/com/unhuman/outlookalerter/CrossPlatformScreenFlasher.groovy \
  TestScreenFlash.groovy

if [ $? -eq 0 ]; then
    echo "Compilation successful. Running test..."
    # Run the test
    java -cp "$SCRIPT_DIR/build/classes:$SCRIPT_DIR/lib/*" com.unhuman.outlookalerter.TestScreenFlash
else
    echo "Compilation failed!"
    exit 1
fi
