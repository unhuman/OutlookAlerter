#!/bin/bash

# Run OutlookAlerter in GUI mode
# This script ensures proper error handling and provides user feedback

# Determine the script's directory to use relative paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Set the classpath to include the application JAR and all JARs in the lib directory
CLASSPATH="$SCRIPT_DIR/dist/OutlookAlerter.jar:$SCRIPT_DIR/lib/*"

echo "Starting OutlookAlerter in GUI mode..."

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not in the PATH."
    echo "Please install Java to run this application."
    exit 1
fi

# Check if required JAR files exist
if [ ! -f "$SCRIPT_DIR/dist/OutlookAlerter.jar" ]; then
    echo "Error: OutlookAlerter.jar not found."
    echo "Please run build.sh first to compile the application."
    exit 1
fi

for jar in "groovy.jar" "groovy-json.jar"; do
    if [ ! -f "$SCRIPT_DIR/lib/$jar" ]; then
        echo "Error: Required dependency $jar not found in lib directory."
        echo "Please ensure all dependencies are available before running."
        exit 1
    fi
done

# Run the application in GUI mode (default)
# Add Java options including setting headless mode to false to ensure GUI works
JAVA_OPTS="-Djava.awt.headless=false ${JAVA_OPTS}"

# For debugging purposes
if [[ "$*" == *"--debug"* ]]; then
    echo "Debug mode enabled - adding Java debug options"
    JAVA_OPTS="${JAVA_OPTS} -Dgroovy.debug=true -Dapple.laf.useScreenMenuBar=true"
fi

# Force AWT to initialize properly on macOS
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "Detected macOS - adding platform-specific Java options"
    
    # Export critical environment variables for macOS GUI apps
    export JAVA_AWT_HEADLESS=false
    export AWT_TOOLKIT=CToolkit
    
    # These options help ensure Swing components display properly on macOS
    JAVA_OPTS="${JAVA_OPTS} -Djava.awt.headless=false -Dapple.awt.UIElement=false"
    JAVA_OPTS="${JAVA_OPTS} -Dapple.laf.useScreenMenuBar=true -Dapple.awt.application.appearance=system"
    
    # Use native macOS look and feel to avoid cross-platform Swing issues
    JAVA_OPTS="${JAVA_OPTS} -Dswing.defaultlaf=com.apple.laf.AquaLookAndFeel"
    
    # Add additional rendering options for better macOS compatibility
    JAVA_OPTS="${JAVA_OPTS} -Dsun.java2d.opengl=false -Dsun.java2d.metal=false"
    
    echo "Using enhanced macOS-specific settings: ${JAVA_OPTS}"
    
    # Run with macOS-specific options
    java ${JAVA_OPTS} -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # Linux
    echo "Detected Linux - using platform-specific settings"
    JAVA_OPTS="${JAVA_OPTS} -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true"
    java ${JAVA_OPTS} -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
elif [[ "$OSTYPE" == "msys"* || "$OSTYPE" == "cygwin"* || "$OSTYPE" == "win"* ]]; then
    # Windows via msys, cygwin, or native
    echo "Detected Windows - using platform-specific settings"
    java ${JAVA_OPTS} -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
else
    # Other OS
    echo "Using generic platform settings"
    java ${JAVA_OPTS} -cp "$CLASSPATH" com.unhuman.outlookalerter.OutlookAlerter "$@"
fi

# Check exit code
exit_code=$?
if [ $exit_code -ne 0 ]; then
    echo "OutlookAlerter exited with an error (code: $exit_code)."
    echo "Check the output above for more details."
    exit $exit_code
fi
