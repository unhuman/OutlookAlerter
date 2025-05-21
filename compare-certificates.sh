#!/bin/bash

# Certificate comparison script for OutlookAlerter
# This script helps diagnose differences in certificate validation between 
# running as a JAR vs running as an app bundle

LOG_FILE="certificate-comparison-$(date +%Y%m%d_%H%M%S).log"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== OutlookAlerter Certificate Comparison Tool ==="
echo "This will compare certificate configurations between JAR and app bundle"
echo "Log file: $LOG_FILE"

# Find the JAR and app bundle
JAR_PATH=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
APP_BUNDLE_PATH="$APP_DIR/target/OutlookAlerter.app"

# Check if both exist
if [[ ! -f "$JAR_PATH" ]]; then
    echo "Error: JAR not found at $JAR_PATH"
    echo "Building project..."
    cd "$APP_DIR" && mvn clean package -DskipTests
    JAR_PATH=$(find "$APP_DIR/target" -name "*-jar-with-dependencies.jar" | head -1)
    if [[ ! -f "$JAR_PATH" ]]; then
        echo "Error: Could not build or find JAR file"
        exit 1
    fi
fi

if [[ ! -d "$APP_BUNDLE_PATH" ]]; then
    echo "Error: App bundle not found at $APP_BUNDLE_PATH"
    exit 1
fi

echo "Found JAR: $JAR_PATH"
echo "Found App bundle: $APP_BUNDLE_PATH"

# Ensure Netskope certificate is available
NETSKOPE_CERT="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"
if [[ ! -f "$NETSKOPE_CERT" ]]; then
    echo "Warning: Netskope certificate not found at $NETSKOPE_CERT"
    
    # Try alternative location
    ALT_NETSKOPE_CERT="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"
    if [[ -f "$ALT_NETSKOPE_CERT" ]]; then
        echo "Found alternative Netskope certificate at: $ALT_NETSKOPE_CERT"
        NETSKOPE_CERT="$ALT_NETSKOPE_CERT"
    else
        echo "Warning: Could not find Netskope certificate"
    fi
fi

# Ensure truststore exists
TRUSTSTORE="$APP_DIR/target/truststore/truststore.jks"
TRUST_STORE_PASSWORD="changeit"

if [[ ! -f "$TRUSTSTORE" ]]; then
    echo "Creating truststore..."
    mkdir -p "$(dirname "$TRUSTSTORE")"
    
    keytool -importkeystore -srckeystore "$JAVA_HOME/lib/security/cacerts" \
            -destkeystore "$TRUSTSTORE" -srcstorepass changeit -deststorepass "$TRUST_STORE_PASSWORD" || true
    
    if [[ -f "$NETSKOPE_CERT" ]]; then
        echo "Importing Netskope certificate to truststore..."
        keytool -importcert -file "$NETSKOPE_CERT" -keystore "$TRUSTSTORE" \
                -storepass "$TRUST_STORE_PASSWORD" -alias "nscacert" -noprompt || true
    fi
fi

# Verify truststore contents
echo "Truststore contents:"
keytool -list -keystore "$TRUSTSTORE" -storepass "$TRUST_STORE_PASSWORD" | grep -E "^(nscacert|netskope)" || echo "No Netskope certificate found in truststore"

# Copy the truststore to the app bundle
mkdir -p "$APP_BUNDLE_PATH/Contents/Resources"
cp "$TRUSTSTORE" "$APP_BUNDLE_PATH/Contents/Resources/"

echo "Copied truststore to app bundle"

# Function to run tests
run_test() {
    local mode=$1
    local name=$2
    local cmd=$3
    
    echo
    echo "========================================="
    echo "Testing $name"
    echo "========================================="
    echo "Command: $cmd"
    echo
    
    echo "===== RUNNING $name =====" >> "$LOG_FILE"
    echo "Command: $cmd" >> "$LOG_FILE"
    echo >> "$LOG_FILE"
    
    eval "$cmd" | tee -a "$LOG_FILE"
    echo >> "$LOG_FILE"
}

# Run the JAR with diagnostics
run_test "jar" "JAR with system truststore" "java -Doutlookalerter.ssl.debug=true -Doutlookalerter.ssl.verbose=true -jar \"$JAR_PATH\" --cert-debug"

# Run the JAR with our custom truststore
run_test "jar" "JAR with custom truststore" "java -Djavax.net.ssl.trustStore=\"$TRUSTSTORE\" -Djavax.net.ssl.trustStorePassword=\"$TRUST_STORE_PASSWORD\" -Doutlookalerter.ssl.debug=true -Doutlookalerter.ssl.verbose=true -jar \"$JAR_PATH\" --cert-debug"

# Run the app bundle with its included truststore
run_test "app" "App bundle with its truststore" "\"$APP_BUNDLE_PATH/Contents/MacOS/OutlookAlerter\" -Doutlookalerter.ssl.debug=true -Doutlookalerter.ssl.verbose=true --cert-debug"

# Run the app bundle with a truststore using absolute path
run_test "app" "App bundle with absolute path truststore" "\"$APP_BUNDLE_PATH/Contents/MacOS/OutlookAlerter\" -Djavax.net.ssl.trustStore=\"$TRUSTSTORE\" -Djavax.net.ssl.trustStorePassword=\"$TRUST_STORE_PASSWORD\" -Doutlookalerter.ssl.debug=true -Doutlookalerter.ssl.verbose=true --cert-debug"

# Print summary
echo
echo "========================================="
echo "Comparison complete!"
echo "Detailed log available at: $LOG_FILE"
echo "========================================="
echo
echo "To fix certificate issues in the app bundle:"
echo "1. Ensure the Netskope certificate is correctly imported to the truststore"
echo "2. Check that the app bundle's Info.plist has correct trustStore paths"
echo "3. Use absolute paths for the truststore in the JVMOptions section"
echo
echo "If the JAR works but the app doesn't, look at the differences in certificate paths and JVM settings"
