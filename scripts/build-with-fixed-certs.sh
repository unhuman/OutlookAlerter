#!/bin/bash

# This script rebuilds the OutlookAlerter app with correct SSL certificate configuration
# It fixes issues that cause the app to fail after a rebuild

echo "=== Building OutlookAlerter with fixed certificate configuration ==="

# Clean up previous build artifacts
echo "Cleaning up previous build artifacts..."
mvn clean

# Save original pom.xml
if [ ! -f "pom.xml.original" ]; then
  echo "Backing up original pom.xml..."
  cp pom.xml pom.xml.original
fi

# Create a more comprehensive fix
echo "Updating pom.xml with proper certificate handling..."
cat > pom.xml.fragment << 'EOL'

            <!-- Maven Antrun Plugin for custom truststore configuration -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>3.0.0</version>
                <executions>
                    <execution>
                        <id>create-truststore</id>
                        <phase>prepare-package</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <target>
                                <echo message="Setting up custom truststore for SSL validation..."/>
                                <property name="javax.net.ssl.trustStore" value="${project.build.directory}/truststore/truststore.jks"/>
                                <property name="javax.net.ssl.trustStorePassword" value="changeit"/>
                                <mkdir dir="${project.build.directory}/truststore"/>
                                <mkdir dir="${project.build.directory}/classes"/>
                                
                                <!-- Import system certificates -->
                                <exec executable="keytool" failonerror="false">
                                    <arg value="-importkeystore"/>
                                    <arg value="-srckeystore"/>
                                    <arg value="${env.JAVA_HOME}/lib/security/cacerts"/>
                                    <arg value="-destkeystore"/>
                                    <arg value="${project.build.directory}/truststore/truststore.jks"/>
                                    <arg value="-srcstorepass"/>
                                    <arg value="changeit"/>
                                    <arg value="-deststorepass"/>
                                    <arg value="changeit"/>
                                </exec>

                                <!-- Try to find and import the Netskope certificate -->
                                <available file="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem" 
                                           property="netskope.cert.exists"/>
                                <if>
                                    <equals arg1="${netskope.cert.exists}" arg2="true"/>
                                    <then>
                                        <echo message="Importing Netskope certificate to truststore..."/>
                                        <exec executable="keytool" failonerror="false">
                                            <arg value="-import"/>
                                            <arg value="-trustcacerts"/>
                                            <arg value="-file"/>
                                            <arg value="/Library/Application Support/Netskope/STAgent/download/nscacert_combined.pem"/>
                                            <arg value="-keystore"/>
                                            <arg value="${project.build.directory}/truststore/truststore.jks"/>
                                            <arg value="-storepass"/>
                                            <arg value="changeit"/>
                                            <arg value="-alias"/>
                                            <arg value="nscacert_combined"/>
                                            <arg value="-noprompt"/>
                                        </exec>
                                    </then>
                                    <else>
                                        <echo message="Netskope certificate not found, attempting alternate locations..."/>
                                        <available file="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem" 
                                                property="alt.netskope.cert.exists"/>
                                        <if>
                                            <equals arg1="${alt.netskope.cert.exists}" arg2="true"/>
                                            <then>
                                                <echo message="Found alternative Netskope certificate location, importing..."/>
                                                <exec executable="keytool" failonerror="false">
                                                    <arg value="-import"/>
                                                    <arg value="-trustcacerts"/>
                                                    <arg value="-file"/>
                                                    <arg value="/Applications/Netskope Client.app/Contents/Resources/nscacert.pem"/>
                                                    <arg value="-keystore"/>
                                                    <arg value="${project.build.directory}/truststore/truststore.jks"/>
                                                    <arg value="-storepass"/>
                                                    <arg value="changeit"/>
                                                    <arg value="-alias"/>
                                                    <arg value="nscacert"/>
                                                    <arg value="-noprompt"/>
                                                </exec>
                                            </then>
                                        </if>
                                    </else>
                                </if>

                                <!-- Copy truststore to classes directory for inclusion in app bundle -->
                                <copy file="${project.build.directory}/truststore/truststore.jks" 
                                      todir="${project.build.directory}/classes"/>
                                
                                <!-- Echo truststore content for verification -->
                                <echo message="Truststore content:"/>
                                <exec executable="keytool" failonerror="false">
                                    <arg value="-list"/>
                                    <arg value="-keystore"/>
                                    <arg value="${project.build.directory}/truststore/truststore.jks"/>
                                    <arg value="-storepass"/>
                                    <arg value="changeit"/>
                                </exec>
                            </target>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
EOL

# Update the pom.xml file
sed -i '' -e '/<\/build>/d' -e '/<\/plugins>/d' pom.xml
cat pom.xml.fragment >> pom.xml
echo "        </plugins>" >> pom.xml
echo "    </build>" >> pom.xml
echo "</project>" >> pom.xml

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

# Verify the Info.plist file is properly formatted
echo "Verifying Info.plist format..."
INFO_PLIST="target/OutlookAlerter.app/Contents/Info.plist"
if grep -q "trustStore.*</string>" "$INFO_PLIST"; then
    echo "Info.plist is properly formatted."
else
    echo "WARNING: Info.plist appears to have malformed XML. Fixing..."
    cp "$INFO_PLIST" "$INFO_PLIST.malformed"
    sed -i '' 's|\(-Djavax.net.ssl.trustStore=.*\)$|\1</string>|g' "$INFO_PLIST"
    echo "Fixed Info.plist. Original saved as $INFO_PLIST.malformed"
fi

# Create a run script with proper environment variables
cat > run-with-ssl.sh << EOL
#!/bin/bash

# Run OutlookAlerter with fixed SSL configuration
APP_PATH="target/OutlookAlerter.app"
TRUSTSTORE="\$APP_PATH/Contents/Resources/truststore.jks"

export JAVAX_NET_DEBUG=ssl:handshake
export OUTLOOKALERTER_SSL_DEBUG=true
export JAVAX_NET_SSL_TRUSTSTORE="\$TRUSTSTORE"
export JAVAX_NET_SSL_TRUSTSTOREPASSWORD="changeit"

echo "Running OutlookAlerter with fixed SSL configuration..."
echo "Truststore: \$TRUSTSTORE"
open "\$APP_PATH"
EOL
chmod +x run-with-ssl.sh

echo "=== Build complete! ==="
echo "You can run the app with: ./run-with-ssl.sh"
