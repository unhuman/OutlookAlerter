#!/bin/zsh

# Setup Maven directory structure
echo "Setting up Maven directory structure..."

# Create Maven directory structure
mkdir -p src/main/groovy/com/unhuman/outlookalerter
mkdir -p src/main/resources
mkdir -p src/test/groovy
mkdir -p src/test/resources

# Move source files to Maven structure
echo "Moving source files to Maven structure..."
mv src/com/unhuman/outlookalerter/*.groovy src/main/groovy/com/unhuman/outlookalerter/

# Copy any resources (like config files, etc)
if [ -d "resources" ]; then
    cp -r resources/* src/main/resources/
fi

# Clean up old directories
rm -rf src/com

echo "Maven directory structure setup complete"
echo "You can now build with: mvn clean package"
echo "The executable JAR will be in target/OutlookAlerter-*-jar-with-dependencies.jar"
