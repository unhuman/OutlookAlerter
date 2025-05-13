#!/bin/bash

# Build script for OutlookAlerter

# Create build directory
mkdir -p build/classes
mkdir -p build/lib
mkdir -p dist

# Check for Groovy and its dependencies
echo "Ensuring Groovy dependencies are available..."
if [ ! -d "lib" ]; then
    mkdir -p lib
    echo "Creating lib directory for dependencies"
fi

# List of required JAR files in the lib directory
REQUIRED_JARS=("groovy.jar" "groovy-json.jar")
MISSING_JARS=false

for JAR in "${REQUIRED_JARS[@]}"; do
    if [ ! -f "lib/$JAR" ]; then
        echo "Missing dependency: lib/$JAR"
        MISSING_JARS=true
    fi
done

if $MISSING_JARS; then
    echo "Please copy the required Groovy JAR files to the lib directory."
    echo "These are typically found in your Groovy installation directory under lib/"
    echo "For example: cp \$GROOVY_HOME/lib/groovy*.jar lib/"
    exit 1
fi

echo "Compiling OutlookAlerter..."

# Compile Groovy classes
groovyc -cp "lib/*" -d build/classes src/com/unhuman/outlookalerter/*.groovy

if [ $? -ne 0 ]; then
    echo "Compilation failed."
    exit 1
fi

echo "Creating JAR file..."

# Create JAR file
jar cf dist/OutlookAlerter.jar -C build/classes .

# Copy dependencies to dist/lib
mkdir -p dist/lib
cp lib/*.jar dist/lib/

# Create executable JAR with manifest
echo "Main-Class: com.unhuman.outlookalerter.OutlookAlerter" > build/MANIFEST.MF
echo "Class-Path: lib/groovy.jar lib/groovy-json.jar" >> build/MANIFEST.MF

jar cfm dist/OutlookAlerter-executable.jar build/MANIFEST.MF -C build/classes .

echo "Build complete. Executable JAR is at dist/OutlookAlerter-executable.jar"
echo ""
echo "To run the application, use one of these commands:"
echo "java -jar dist/OutlookAlerter-executable.jar                     # If lib directory is next to the JAR"
echo "java -cp \"dist/OutlookAlerter.jar:lib/*\" com.unhuman.outlookalerter.OutlookAlerter  # Linux/Mac"
echo "java -cp \"dist/OutlookAlerter.jar;lib/*\" com.unhuman.outlookalerter.OutlookAlerter  # Windows"
echo ""
echo "Or use the included run script: ./run.sh"