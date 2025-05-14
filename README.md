# OutlookAlerter

A Groovy application that monitors your Microsoft Outlook/Office 365 calendar and alerts you when meetings are about to start by flashing the screen.

## Features

- Connects to Microsoft Graph API to retrieve your calendar events
- Flashes the screen when meetings are about to start (within 1 minute)
- Supports authentication through Okta SSO or direct Microsoft authentication
- Handles all calendar events, including tentative meetings
- Shows meeting response status (accepted, tentative, etc.) in alerts
- Cross-platform support for screen flashing (macOS, Windows, Linux)
- Configurable polling interval and alert thresholds

## Timezone Handling

OutlookAlerter now provides improved timezone handling to ensure your calendar events are displayed accurately regardless of your location or system timezone settings.

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

OutlookAlerter includes several diagnostic tools to help troubleshoot issues with calendar events not appearing or with timezone handling:

### Basic Diagnostic Tools

1. **Debug Mode**: Run with detailed logging
   ```
   ./run-debug.sh
   ```
   
2. **Timezone Test**: Test the application's timezone handling
   ```
   ./test-timezones.sh
   ```

3. **Timezone Override**: Run with an explicit timezone
   ```
   ./run-with-timezone.sh America/New_York
   ```

### Advanced Diagnostic Tools

These tools can help diagnose missing calendar events:

1. **Calendar Events Test**: Comprehensive calendar event testing
   ```
   ./test-calendar-events.sh
   ```

2. **Missing Meetings Diagnostic**: Find meetings that might be missing
   ```
   ./diagnose-missing-meetings.sh
   ```

3. **Multi-Calendar Diagnostic**: Diagnose issues with events in multiple calendars
   ```
   ./diagnose-multi-calendar.sh
   ```

4. **Enhanced Calendar Diagnostics**: Deep analysis of calendar retrieval methods
   ```
   ./enhanced-calendar-diagnostics.sh
   ```

5. **Time Comparison Test**: Verify event timing logic is working correctly
   ```
   ./test-time-comparisons.sh
   ```

6. **Comprehensive Diagnostics**: Run all diagnostics in one go
   ```
   ./run-all-diagnostics.sh
   ```
   
7. **Debug with Diagnostics**: Run in debug mode with diagnostics
   ```
   ./run-debug.sh --diagnostics
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

## Usage

```
./run.sh [options]
```

Options:
- `--config <path>`: Path to configuration file (default: ~/.outlookalerter/config.properties)
- `--daemon`: Run in daemon mode (background)
- `--debug`: Enable debug mode with detailed timezone logging
- `--timezone <zone>`: Override the timezone for displaying events (e.g., America/New_York)
- `--help`: Show help message

## Installation

1. Clone the repository
2. Run `./build.sh` to compile the application
3. Run `./run.sh` to start the application

## Configuration

On first run, a default configuration file will be created at `~/.outlookalerter/config.properties`. You'll need to edit this file with your authentication details.

For Okta SSO authentication:
1. Set `signInUrl` to your organization's Okta SSO URL for Microsoft 365
2. Set `loginHint` to your email address (optional)
3. Set `preferredTimezone` to your desired timezone (optional)

## Event Response Status Support

OutlookAlerter now properly displays the response status of each calendar event (accepted, tentative, declined, etc.):

- All events are displayed regardless of response status, including tentative meetings
- Response status is shown in the console output for each event
- Screen flash notifications include the response status
- The application does not filter out any meetings that occur at the same time

To test response status support, run the included test script:
```
./test-tentative-meetings.sh
```

## Requirements

- Java 11 or later
- Groovy libraries (included in the lib directory)
