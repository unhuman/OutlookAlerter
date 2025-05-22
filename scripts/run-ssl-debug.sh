#!/bin/bash

# This script runs the OutlookAlerter application with SSL debugging and trust-all capability
# It helps diagnose and fix SSL certificate issues in environments with proxy servers like Netskope

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="outlookalerter-ssl-debug-$TIMESTAMP.log"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
TRUST_STORE="$APP_DIR/target/truststore/truststore.jks"
TRUST_STORE_PASSWORD="changeit"

# Determine if we're running from the app bundle
if [[ -d "$APP_DIR/Contents" ]]; then
    echo "Running from app bundle, adjusting paths..."
    TRUST_STORE="$APP_DIR/Contents/Resources/truststore.jks"
fi

echo "Starting OutlookAlerter with SSL debugging enabled"
echo "Log file: $LOG_FILE"
echo "Trust store: $TRUST_STORE"

# Always check if the Netskope certificate exists
NETSKOPE_CERT="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
if [ -f "$NETSKOPE_CERT" ]; then
    echo "Found Netskope certificate: $NETSKOPE_CERT"
    
    # Import it to our truststore if needed
    if [ -f "$TRUST_STORE" ]; then
        echo "Importing Netskope certificate to existing truststore..."
        keytool -import -trustcacerts -file "$NETSKOPE_CERT" \
            -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" \
            -alias "nscacert_combined" -noprompt 2>/dev/null || echo "Certificate might already exist in truststore"
    else
        echo "Creating new truststore with Netskope certificate..."
        mkdir -p "$(dirname "$TRUST_STORE")"
        keytool -import -trustcacerts -file "$NETSKOPE_CERT" \
            -keystore "$TRUST_STORE" -storepass "$TRUST_STORE_PASSWORD" \
            -alias "nscacert_combined" -noprompt
    fi
else
    echo "Netskope certificate not found at: $NETSKOPE_CERT"
fi

# Determine if we're running the JAR or the app bundle
if [[ -d "$APP_DIR/target" && -f "$APP_DIR/pom.xml" ]]; then
    # Development mode
    MAIN_JAR=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
    if [[ -z "$MAIN_JAR" ]]; then
        echo "JAR not found. Building project..."
        mvn clean package -DskipTests
        MAIN_JAR=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
    fi
    
    # Check if trust-all mode is requested
    if [[ "$1" == "--trust-all" || "$2" == "--trust-all" ]]; then
        echo "⚠️ ENABLING TRUST-ALL MODE - ALL SSL CERTIFICATES WILL BE ACCEPTED ⚠️"
        echo "Warning: This is insecure but may help diagnose SSL certificate issues"
        
        # Run with SSL debugging and trust-all enabled
        java \
            -Djavax.net.ssl.trustStore="$TRUST_STORE" \
            -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
            -Djavax.net.debug=ssl:handshake \
            -Doutlookalerter.ssl.trustall=true \
            -jar "$MAIN_JAR" 2>&1 | tee "$LOG_FILE"
    else
        # Run with SSL debugging but standard certificate validation
        java \
            -Djavax.net.ssl.trustStore="$TRUST_STORE" \
            -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
            -Djavax.net.debug=ssl:handshake \
            -jar "$MAIN_JAR" 2>&1 | tee "$LOG_FILE"
    fi
else
    # App bundle mode - dynamically find java executable
    if [[ -d "$APP_DIR/Contents/MacOS" ]]; then
        if [[ "$1" == "--trust-all" || "$2" == "--trust-all" ]]; then
            echo "⚠️ ENABLING TRUST-ALL MODE - ALL SSL CERTIFICATES WILL BE ACCEPTED ⚠️"
            echo "Warning: This is insecure but may help diagnose SSL certificate issues"
            
            # Run .app with SSL debugging and trust-all enabled
            "$APP_DIR/Contents/MacOS/OutlookAlerter" \
                -Djavax.net.ssl.trustStore="$TRUST_STORE" \
                -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
                -Djavax.net.debug=ssl:handshake \
                -Doutlookalerter.ssl.trustall=true 2>&1 | tee "$LOG_FILE"
        else
            # Run .app with SSL debugging but standard certificate validation
            "$APP_DIR/Contents/MacOS/OutlookAlerter" \
                -Djavax.net.ssl.trustStore="$TRUST_STORE" \
                -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
                -Djavax.net.debug=ssl:handshake 2>&1 | tee "$LOG_FILE"
        fi
    else
        echo "Error: Could not locate application executable"
        exit 1
    fi
fi

echo "Application has exited. SSL debug log saved to $LOG_FILE"
echo "To analyze SSL issues, look for 'certificate_unknown' or 'PKIX' errors in the log file"
echo "If SSL issues persist, run with the --trust-all flag: ./run-ssl-debug.sh --trust-all"
