# SSL Certificate Management for OutlookAlerter

This document provides comprehensive information about SSL certificate management for the OutlookAlerter application, particularly for handling Netskope SSL interception.

## Overview

OutlookAlerter needs to connect to Microsoft Graph API to retrieve calendar information. When running in an environment with Netskope SSL interception, the application needs to trust Netskope's SSL certificates to establish secure connections.

## Certificate Management Process

### 1. Basic Truststore Configuration

The application uses a Java truststore file (`truststore.jks`) that contains:

- Standard Java CA certificates
- The Netskope CA certificate

### 2. Truststore Creation During Build

The Maven build process automatically creates a truststore with both system certificates and the Netskope certificate:

1. **System certificates** are exported using macOS `security` command
2. **Netskope certificate** is imported from `/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem`

This is configured in the `pom.xml` file:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-antrun-plugin</artifactId>
    <version>3.0.0</version>
    <executions>
        <!-- Initialize phase creates truststore with system certs -->
        <execution>
            <phase>initialize</phase>
            <goals>
                <goal>run</goal>
            </goals>
            <configuration>
                <target>
                    <!-- Code that exports system certificates to truststore -->
                </target>
            </configuration>
        </execution>
        <!-- Prepare package phase adds Netskope certificate -->
        <execution>
            <id>add-certificate-to-truststore</id>
            <phase>prepare-package</phase>
            <configuration>
                <target>
                    <!-- Code that imports Netskope certificate -->
                </target>
            </configuration>
            <goals>
                <goal>run</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 3. Truststore Path Configuration

For the application to find the truststore when running as a macOS app bundle, the path must be correctly configured in `Info.plist`:

```xml
<key>JVMOptions</key>
<array>
    <string>-Djavax.net.ssl.trustStore=$APP_ROOT/Contents/Resources/truststore.jks</string>
    <string>-Djavax.net.ssl.trustStorePassword=changeit</string>
    <string>-Doutlookalerter.ssl.debug=true</string>
</array>
```

The `$APP_ROOT` variable ensures the path is resolved correctly regardless of where the app is installed.

### 4. Certificate Resolution in the Application

The `SSLUtils` class in the application tries multiple methods to find and load the truststore:

1. Check system property `javax.net.ssl.trustStore`
2. Check for truststore in the classpath resources
3. Check multiple possible paths on disk
4. Fall back to creating a dynamic truststore in memory

## Troubleshooting Certificate Issues

### 1. Diagnostic Scripts

The application includes several scripts for diagnosing SSL certificate issues:

- **diagnose-ssl-issues.sh**: Comprehensive diagnostics for SSL configuration
- **fix-app-certificates.sh**: Repairs certificate issues in an existing app bundle
- **compare-certificates.sh**: Compares certificates between different truststores

### 2. Common SSL Issues

#### Path Resolution Issues

If the app can't find the truststore, check:
- The path in Info.plist
- If the truststore file exists in the expected location
- File permissions on the truststore

#### Netskope Certificate Issues

If the Netskope certificate isn't trusted:
- Verify Netskope is installed and certificate is available
- Check if the certificate was correctly imported into the truststore
- Use the fix-app-certificates.sh script to repair the app bundle

#### Debug SSL Connection Issues

Enable SSL debugging in the app:
```
./run-with-ssl-debug.sh
```

## Building with Correct Certificate Configuration

To build the application with the correct certificate configuration:

```bash
./build-with-fixed-certs.sh
```

This script:
1. Cleans previous build artifacts
2. Updates the pom.xml to use the correct truststore path
3. Builds the application with Maven
4. Runs fix-app-certificates.sh on the built app bundle

## Manual Certificate Import

If you need to manually import a certificate into the truststore:

```bash
keytool -importcert -file /path/to/certificate.pem -keystore /path/to/truststore.jks \
    -storepass changeit -alias customCertName -noprompt
```

## Verifying Certificate Contents

To view the contents of the truststore:

```bash
keytool -list -keystore /path/to/truststore.jks -storepass changeit
```

To view details of a specific certificate:

```bash
keytool -list -v -keystore /path/to/truststore.jks -storepass changeit -alias certificateAlias
```

## Maintaining Certificates

1. **Regular Updates**: Netskope certificates might be updated. The build process should automatically import the latest version.

2. **Custom Certificates**: If additional certificates need to be trusted, follow the manual import process above.

3. **Certificate Expiration**: Monitor certificate expiration dates. Use this command to check:
```bash
keytool -list -v -keystore /path/to/truststore.jks -storepass changeit | grep "Valid from"
```
