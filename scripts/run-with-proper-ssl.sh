#!/bin/bash

# This script runs the OutlookAlerter application with proper SSL certificate handling
# It ensures the Netskope certificate is properly integrated into the Java truststore

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
TRUST_STORE="$APP_DIR/target/truststore/truststore.jks"
TRUST_STORE_PASSWORD="changeit"
LOG_FILE="outlookalerter-ssl-$(date +%Y%m%d_%H%M%S).log"

echo "Starting OutlookAlerter with SSL certificate integration"
echo "Log file: $LOG_FILE"

# Determine app environment
if [[ -d "$APP_DIR/Contents" ]]; then
    # Running from app bundle
    echo "Running from app bundle"
    TRUST_STORE="$APP_DIR/Contents/Resources/truststore.jks"
    IS_APP_BUNDLE=true
else
    # Running from development environment
    echo "Running from development environment"
    IS_APP_BUNDLE=false
fi

echo "Using truststore: $TRUST_STORE"

# Check if the Netskope certificate exists
NETSKOPE_CERT="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
if [ -f "$NETSKOPE_CERT" ]; then
    echo "Found Netskope certificate: $NETSKOPE_CERT"
    # Verify the certificate is valid
    openssl x509 -in "$NETSKOPE_CERT" -text -noout > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "Netskope certificate is valid"
    else
        echo "Warning: Netskope certificate may not be valid"
    fi
else
    echo "Netskope certificate not found at: $NETSKOPE_CERT"
    # Check alternative location
    ALT_NETSKOPE_CERT="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"
    if [ -f "$ALT_NETSKOPE_CERT" ]; then
        echo "Found alternative Netskope certificate at: $ALT_NETSKOPE_CERT"
        NETSKOPE_CERT="$ALT_NETSKOPE_CERT"
    fi
fi

# Check if we need to create or update the truststore
if [ -f "$TRUST_STORE" ]; then
    echo "Truststore exists, checking if Netskope certificate is included..."
    
    # Check if Netskope certificate is in the truststore
    if [ -f "$NETSKOPE_CERT" ]; then
        keytool -list -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" | grep -q "nscacert"
        if [ $? -eq 0 ]; then
            echo "Netskope certificate is already in the truststore"
        else
            echo "Adding Netskope certificate to existing truststore..."
            keytool -importcert -file "$NETSKOPE_CERT" -keystore "$TRUST_STORE" \
                -storepass "$TRUST_STORE_PASSWORD" -alias "nscacert" -noprompt
        fi
    fi
else
    echo "Truststore not found, creating new one..."
    
    # Create directory if it doesn't exist
    mkdir -p "$(dirname "$TRUST_STORE")"
    
    # Create a new truststore
    echo "Creating new truststore..."
    keytool -genkeypair -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" \
        -alias dummy -dname "CN=Dummy Certificate, O=OutlookAlerter" \
        -keyalg RSA -keysize 2048 -validity 365 > /dev/null
    
    # Delete the dummy key as we only want trusted certificates
    keytool -delete -alias dummy -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" > /dev/null
    
    # Import the Netskope certificate if available
    if [ -f "$NETSKOPE_CERT" ]; then
        echo "Importing Netskope certificate to new truststore..."
        keytool -importcert -file "$NETSKOPE_CERT" -keystore "$TRUST_STORE" \
            -storepass "$TRUST_STORE_PASSWORD" -alias "nscacert" -noprompt
    fi
    
    # Import Mozilla's trusted root certificates
    curl -s -o /tmp/mozilla-cacert.pem https://curl.se/ca/cacert.pem
    if [ -f "/tmp/mozilla-cacert.pem" ]; then
        echo "Importing Mozilla's trusted root certificates..."
        # Import Mozilla's CA certificates
        keytool -importcert -file "/tmp/mozilla-cacert.pem" -keystore "$TRUST_STORE" \
            -storepass "$TRUST_STORE_PASSWORD" -alias "mozilla-cacerts" -noprompt || true
        rm /tmp/mozilla-cacert.pem
    else
        echo "Could not download Mozilla's trusted root certificates"
    fi
    
    # Copy existing system certificates to the keystore
    echo "Importing system certificates..."
    SYSTEM_KEYSTORE="$JAVA_HOME/lib/security/cacerts"
    if [ -f "$SYSTEM_KEYSTORE" ]; then
        keytool -importkeystore -srckeystore "$SYSTEM_KEYSTORE" -destkeystore "$TRUST_STORE" \
            -srcstorepass changeit -deststorepass "$TRUST_STORE_PASSWORD" || true
    fi
fi

# List certificates in the truststore to verify content
echo "Certificates in the truststore:"
keytool -list -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" | grep -E "^(nscacert|mozilla-cacerts)"

# Run the application with the configured truststore
if [ "$IS_APP_BUNDLE" = true ]; then
    # For app bundle, we need to use the bundled JVM
    echo "Running app bundle with configured truststore..."
    "$APP_DIR/Contents/MacOS/OutlookAlerter" \
        -Djavax.net.ssl.trustStore="$TRUST_STORE" \
        -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
        -Doutlookalerter.ssl.debug=true 2>&1 | tee "$LOG_FILE"
else
    # For development environment, find the JAR
    MAIN_JAR=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
    if [[ -z "$MAIN_JAR" ]]; then
        echo "JAR not found. Building project..."
        cd "$APP_DIR" && mvn clean package -DskipTests
        MAIN_JAR=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
    fi
    
    echo "Running JAR with configured truststore: $MAIN_JAR"
    java \
        -Djavax.net.ssl.trustStore="$TRUST_STORE" \
        -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
        -Doutlookalerter.ssl.debug=true \
        -jar "$MAIN_JAR" 2>&1 | tee "$LOG_FILE"
fi

echo "Application has exited. Log saved to $LOG_FILE"
