#!/bin/bash

# This script rebuilds the OutlookAlerter app with correct SSL certificate configuration
# It ensures that both system certificates and Netskope certificates are included

echo "=== Building OutlookAlerter with fixed certificate configuration ==="

# Clean up previous build artifacts
echo "Cleaning up previous build artifacts..."
mvn clean

# Modify pom.xml to use correct truststore path
echo "Updating pom.xml to use absolute truststore path..."
sed -i '' 's|-Djavax.net.ssl.trustStore=Contents/Resources/truststore.jks|-Djavax.net.ssl.trustStore=$APP_ROOT/Contents/Resources/truststore.jks|g' pom.xml

# Build the project
echo "Building the project with Maven..."
mvn package

# Verify the app was built
if [ ! -d "target/OutlookAlerter.app" ]; then
  echo "Error: App bundle was not created!"
  exit 1
fi

# Fix the truststore in the built app
echo "Fixing certificates in the built app..."
./fix-app-certificates.sh target/OutlookAlerter.app

echo "=== Build complete! ==="
echo "You can run the app with: open target/OutlookAlerter.app"
