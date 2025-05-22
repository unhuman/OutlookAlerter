#!/bin/bash

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Change to the app directory if running from elsewhere
cd "$SCRIPT_DIR" || exit 1

# Define paths
APP_DIR="$SCRIPT_DIR"
RESOURCES_DIR="$APP_DIR/Contents/Resources"
TRUSTSTORE="$RESOURCES_DIR/truststore.jks"
JAR_FILE="$RESOURCES_DIR/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

echo "Starting OutlookAlerter with custom SSL configuration"
echo "App directory: $APP_DIR"
echo "Resources directory: $RESOURCES_DIR"
echo "Truststore path: $TRUSTSTORE"

# Verify truststore exists
if [ -f "$TRUSTSTORE" ]; then
    echo "Found truststore at: $TRUSTSTORE"
    ls -la "$TRUSTSTORE"
else
    echo "WARNING: Truststore not found at $TRUSTSTORE"
    # Try to find it elsewhere
    ALTERNATE_TRUSTSTORE=$(find "$APP_DIR" -name "truststore.jks" -print | head -1)
    if [ -n "$ALTERNATE_TRUSTSTORE" ]; then
        echo "Found alternate truststore at: $ALTERNATE_TRUSTSTORE"
        TRUSTSTORE="$ALTERNATE_TRUSTSTORE"
    fi
fi

# Check truststore file
if [ -f "$TRUSTSTORE" ]; then
    echo "Truststore details:"
    keytool -list -keystore "$TRUSTSTORE" -storepass changeit
fi

# Run the application with explicit SSL configuration
java \
  -Djavax.net.ssl.trustStore="$TRUSTSTORE" \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Djavax.net.debug=ssl:handshake \
  -Djava.security.egd=file:/dev/urandom \
  -jar "$JAR_FILE"
