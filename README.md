# Outlook Alerter

A Groovy application that monitors your Microsoft Outlook/Office 365 calendar and alerts you when meetings are about to start by flashing the screen.

> **Note:** All utility, diagnostic, and test scripts have been organized into the `scripts/` directory for better project organization. Documentation has been updated to reference these new paths.

## Features

- Connects to Microsoft Graph API to retrieve your calendar events
- Configurable alert time (1-30 minutes before meetings)
- Cross-platform screen flashing alerts for upcoming meetings
- Supports authentication through Okta SSO or direct Microsoft authentication
- Comprehensive calendar event handling:
  - Shows meeting response status (accepted, tentative, declined)
  - Displays online meeting status
  - Shows meeting organizer information
  - Handles concurrent meetings
  - Includes tentative and all-day events
- Smart UI features:
  - System tray integration with minimizable window
  - Real-time calendar updates
  - Status indicators for connection and updates
  - Configurable settings via GUI dialog
  - Automatic token refresh handling
- Console mode support for command-line operation
- Robust timezone handling with configurable preferences
- Extensive diagnostic and troubleshooting tools

## Documentation

Reference documentation for users and developers is available in the `documentation` directory:

- [SSL Certificate Management](documentation/certificates.md) - Comprehensive guide to SSL certificate handling, including:
  - Netskope SSL certificate integration
  - Truststore configuration and management
  - SSL troubleshooting and diagnostic tools
  - Certificate verification and maintenance

- [Icon Creation Guide](documentation/iconCreation.md) - Instructions for creating and managing application icons:
  - Icon design specifications
  - Converting icons to different formats
  - Integrating icons into the build process
  - Troubleshooting icon display issues

## User Interface

Outlook Alerter provides a modern, user-friendly graphical interface with these key features:

### Main Window
- **Current Meetings**: Shows in-progress meetings with duration and status
- **Next Meetings**: Displays upcoming meetings within alert window
- **Later Meetings**: Lists future meetings beyond the current timeframe
- **Status Bar**: Shows connection state and last update time
- **Quick Actions**: Refresh, Settings, and Exit buttons

### Settings Dialog
- **Alert Configuration**: Set alert time (1-30 minutes before meetings)
- **Timezone Settings**: Configure preferred timezone
- **Authentication**: Set up Okta SSO or direct Microsoft authentication
- **Visual Preferences**: Configure display options

### System Tray Integration
- **Background Operation**: Runs minimized while monitoring calendar
- **Quick Menu**: Right-click for common actions
- **Status Notifications**: Meeting alerts appear as system notifications
- **Double-click Restore**: Quickly access the main window

## Running the Application

You can run Outlook Alerter in two modes, either using the JAR directly or using the provided shell scripts:

### Using Maven-built JAR

#### GUI Mode (Default)
```bash
java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar
```

#### Console Mode
```bash
java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar --console
```

### Using Shell Scripts

#### GUI Mode (Default)
```bash
./run.sh
```

#### Console Mode
```bash
./run.sh --console
```

### Features Common to Both Modes
- **GUI Mode**:
  - Full graphical interface with system tray integration
  - Visual alerts and notifications
  - Settings configurable through dialog
  - Minimizes to system tray for background operation

- **Console Mode**:
  - Text-based interface for command-line operation
  - Same alert functionality without GUI
  - Suitable for environments without display server
  - Can run as a background service

### Command Line Options
The following options work with both the JAR and shell script:
```bash
java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar [options]
# or
./run.sh [options]
```

Options:
- `--config <path>`: Custom config file location (default: ~/.outlookalerter/config.properties)
- `--console`: Run in console mode
- `--debug`: Enable detailed logging
- `--timezone <zone>`: Override timezone setting
- `--help`: Show help message

### Authentication

The new UI-based authentication process:
1. Automatically opens your browser to the sign-in page
2. Displays a user-friendly dialog for entering the authentication token
3. Validates token format before submission
4. Automatically pops up when token updates are required

## Timezone Handling

Outlook Alerter now provides improved timezone handling to ensure your calendar events are displayed accurately regardless of your location or system timezone settings.

### Timezone Configuration Options

1. **Config File**: Set your preferred timezone in the config file (`~/.outlookalerter/config.properties`):
   ```
   preferredTimezone=America/New_York
   ```

2. **Command Line**: Override the timezone setting via command line:
   ```
   ./run.sh --timezone America/New_York
   ```

3. **Convenience Script**: Use the provided convenience script:
   ```
   ./run-with-timezone.sh America/New_York
   ```

### Common Timezone IDs

- **North America**: `America/New_York`, `America/Chicago`, `America/Denver`, `America/Los_Angeles`
- **Europe**: `Europe/London`, `Europe/Paris`, `Europe/Berlin`, `Europe/Moscow`
- **Asia**: `Asia/Tokyo`, `Asia/Singapore`, `Asia/Hong_Kong`, `Asia/Dubai`
- **Australia/Pacific**: `Australia/Sydney`, `Pacific/Auckland`

### Debugging Timezone Issues

If you encounter timezone-related issues, you can run the application with the `--debug` flag to see detailed timezone information:

```
./run.sh --debug
```

This will show:
- Your system's current timezone
- The timezone of each event from the calendar
- Detailed time comparisons used for determining upcoming events

## Diagnostic Tools

Outlook Alerter includes several diagnostic tools to help troubleshoot issues with calendar events not appearing or with timezone handling:

### Basic Diagnostic Tools

1. **Debug Mode**: Run with detailed logging
   ```
   ./scripts/run-debug.sh
   ```
   
2. **Timezone Test**: Test the application's timezone handling
   ```
   ./scripts/test-timezones.sh
   ```

3. **Timezone Override**: Run with an explicit timezone
   ```
   ./scripts/run-with-timezone.sh America/New_York
   ```

### Advanced Diagnostic Tools

These tools can help diagnose missing calendar events:

1. **Calendar Events Test**: Comprehensive calendar event testing
   ```
   ./scripts/test-calendar-events.sh
   ```

2. **Missing Meetings Diagnostic**: Find meetings that might be missing
   ```
   ./scripts/diagnose-missing-meetings.sh
   ```

3. **Multi-Calendar Diagnostic**: Diagnose issues with events in multiple calendars
   ```
   ./scripts/diagnose-multi-calendar.sh
   ```

4. **Enhanced Calendar Diagnostics**: Deep analysis of calendar retrieval methods
   ```
   ./scripts/enhanced-calendar-diagnostics.sh
   ```

5. **Time Comparison Test**: Verify event timing logic is working correctly
   ```
   ./scripts/test-time-comparisons.sh
   ```

6. **Comprehensive Diagnostics**: Run all diagnostics in one go
   ```
   ./scripts/run-all-diagnostics.sh
   ```
   
7. **Debug with Diagnostics**: Run in debug mode with diagnostics
   ```
   ./scripts/run-debug.sh --diagnostics
   ```

### Understanding Diagnostic Results

The diagnostic tools help identify why meetings might be missing by:

1. Testing different calendar retrieval methods:
   - Standard events endpoint
   - Calendar view endpoint
   - Multi-calendar retrieval

2. Comparing events between these methods to find discrepancies

3. Testing timezone handling to ensure events display at correct times

4. Creating detailed reports with recommendations on which retrieval methods to use

## Troubleshooting

### Diagnostic Tools
OutlookAlerter includes several built-in diagnostic tools:

1. **Debug Mode**: Enable detailed logging
   ```bash
   ./run.sh --debug
   ```

2. **Timezone Diagnostics**: Test timezone configuration
   ```bash
   ./scripts/test-timezones.sh
   ```

3. **Calendar Event Tests**: Validate event retrieval
   ```bash
   ./scripts/test-calendar-events.sh
   ```

4. **Full Diagnostics**: Run all tests
   ```bash
   ./scripts/run-all-diagnostics.sh
   ```

### Common Issues

> Note: All dialog windows and system tray notifications will show "Outlook Alerter" as the application name.

1. **Authentication Problems**
   - Verify Okta SSO URL in settings
   - Check token expiration
   - Run with --debug for detailed auth logs

2. **Missing Calendar Events**
   - Confirm timezone settings
   - Check calendar permissions
   - Run event diagnostics

3. **Alert Issues**
   - Verify system notification settings
   - Check alert minutes configuration
   - Test screen flash functionality

4. **System Tray Problems**
   - Some systems have limited tray support
   - Application remains functional in window mode
   - Check system tray application permissions

## Support

### Log Files
- Application logs: `~/.outlookalerter/outlookalerter.log`
- Debug logs: `~/.outlookalerter/outlookalerter-debug.log`
- Diagnostic reports: `~/.outlookalerter/diagnostics/`

### Getting Help
- Check the troubleshooting section first
- Run diagnostic tools for detailed reports
- Review debug logs for error messages
- File detailed bug reports with diagnostic output

## Usage

You can run Outlook Alerter directly using Java with the Maven-built JAR:

```bash
# Run with default options
java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar

# Run with specific options
java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar [options]
```

Or using the convenience shell script (which uses the same JAR internally):
```bash
./run.sh [options]
```

Available options:
- `--config <path>`: Path to configuration file (default: ~/.outlookalerter/config.properties)
- `--daemon`: Run in daemon mode (background)
- `--debug`: Enable debug mode with detailed timezone logging
- `--timezone <zone>`: Override the timezone for displaying events (e.g., America/New_York)
- `--help`: Show help message

Note: All diagnostic and test scripts (`scripts/test-*.sh`, `scripts/diagnose-*.sh`) also support running with the Maven-built JAR.

## Installation

There are two ways to build and run Outlook Alerter:

### Using Maven (Recommended)
1. Clone the repository
2. Build the application with Maven:
   ```bash
   mvn clean package
   ```
3. Run the generated JAR:
   ```bash
   java -jar target/outlookalerter-1.0-SNAPSHOT-jar-with-dependencies.jar
   ```

### Using Shell Scripts (Alternative)
1. Clone the repository
2. Run `./scripts/build.sh` to compile the application (uses Maven internally)
3. Run `./run.sh` to start the application

## Configuration

The application stores its configuration in `~/.outlookalerter/config.properties`. This file is created automatically on first run with default values.

### Core Settings
```properties
# Authentication
signInUrl=https://your-company.okta.com/home/office365/...  # Your Okta SSO URL
loginHint=your.name@company.com                             # Your email (optional)

# Application Settings
preferredTimezone=America/New_York                          # Your preferred timezone
alertMinutes=1                                              # Minutes before meeting to alert (1-30)

# Advanced Settings (usually auto-configured)
clientId=                                                   # OAuth client ID
clientSecret=                                               # OAuth client secret
tenantId=common                                            # Azure AD tenant
redirectUri=http://localhost:8888/redirect                 # OAuth redirect URI
```

### Settings Management
- Most settings can be changed through the Settings dialog in GUI mode
- Direct file editing is required only for advanced configuration
- Changes take effect immediately after saving

## Event Response Status Support

Outlook Alerter now properly displays the response status of each calendar event (accepted, tentative, declined, etc.):

- All events are displayed regardless of response status, including tentative meetings
- Response status is shown in the console output for each event
- Screen flash notifications include the response status
- The application does not filter out any meetings that occur at the same time

To test response status support, run the included test script:
```
./scripts/test-tentative-meetings.sh
```

## Requirements

- Java 11 or later
- Maven 3.6 or later for building
- Properly configured SSL certificates (especially in environments using Netskope for SSL interception)
- See [SSL Certificate Management](documentation/certificates.md) for certificate requirements

## Build System

Outlook Alerter uses Maven for dependency management and building. The project's key dependencies are managed through the `pom.xml` file:

### Core Dependencies
- Groovy 3.0.9 (core, json, and dateutil modules)
- JNA 5.13.0 for native system access
- Maven 3.6+ for building

### SSL Certificate Handling
The build process includes automatic SSL certificate management:
- System certificates are imported into a custom truststore
- Netskope certificates are detected and imported if available
- Certificate configuration is integrated into the app bundle

For details on certificate management during the build process, see the [SSL Certificate Management](documentation/certificates.md) documentation.
