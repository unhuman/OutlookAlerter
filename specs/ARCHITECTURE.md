# OutlookAlerter — Architecture Spec

## Overview

OutlookAlerter is a macOS/Windows desktop application (Groovy/Java Swing) that monitors Microsoft Outlook calendar events via the Microsoft Graph API and alerts users when meetings are about to start. It runs as a system tray application with periodic polling.

## Project Metadata

| Field | Value |
|---|---|
| Group ID | `com.unhuman` |
| Artifact ID | `OutlookAlerter` |
| Version | `1.0.0-SNAPSHOT` |
| Java Target | 21 (minimum 11) |
| Language | Groovy 4.0.27 (statically compiled) |
| Build | Maven (`mvn package`) |
| Main Class | `com.unhuman.outlookalerter.OutlookAlerter` |
| macOS Bundle | jpackage → `target/OutlookAlerter.app` |
| Config File | `~/.outlookalerter/config.properties` |

## Dependencies

- **Apache Groovy 4.0.27** — `groovy`, `groovy-json`, `groovy-dateutil`
- **JNA 5.17.0** — `jna`, `jna-platform` (native OS access, Windows flash)

## Package Structure

```
com.unhuman.outlookalerter
├── OutlookAlerter              # Entry point (CLI parsing, mode selection)
├── model/
│   └── CalendarEvent           # Data model for calendar events
├── core/
│   ├── ConfigManager           # Singleton config (properties file I/O)
│   ├── OutlookClient           # Graph API client (auth, events, HTTP)
│   ├── OAuthRedirectServer     # Local HTTP server for OAuth redirect
│   ├── SSLUtils                # SSL/TLS truststore setup
│   └── SingleInstanceManager   # File lock for single-instance enforcement
├── ui/
│   ├── OutlookAlerterUI        # Main GUI (JFrame, schedulers, alert orchestration)
│   ├── OutlookAlerterConsole   # Console/headless mode
│   ├── SettingsDialog          # Settings form (JDialog)
│   ├── SimpleTokenDialog       # Token entry dialog (singleton JDialog)
│   ├── LogViewer               # Log display window (JFrame)
│   ├── IconManager             # Programmatic icon generation (normal/invalid states)
│   └── IconGenerator           # CLI tool to export icons as PNG
└── util/
    ├── ScreenFlasher           # Interface: flash(event), flashMultiple(events)
    ├── ScreenFlasherFactory    # Platform-specific factory
    ├── MacScreenFlasher        # macOS flash implementation
    ├── WindowsScreenFlasher    # Windows flash implementation (JNA + Swing fallback)
    ├── CrossPlatformScreenFlasher  # Generic Swing flash
    ├── MacSleepWakeMonitor     # Sleep/wake detection via time-jump polling
    ├── MacWindowHelper         # Native window handle (blocked on modern macOS)
    ├── LogManager              # Singleton log buffer with categories
    └── LogCategory             # Enum: DATA_FETCH, MEETING_INFO, ALERT_PROCESSING, GENERAL
```

## Startup Flow

```
main(args)
  ├── Parse CLI: --config, --console, --debug, --timezone, --help
  ├── SingleInstanceManager.tryAcquireLock()  → exit if locked
  ├── SSLUtils.initializeSSLContext()
  ├── ConfigManager.getInstance()  → loads ~/.outlookalerter/config.properties
  ├── OutlookClient(configManager, ui?)
  └── Launch mode:
      ├── GUI (default) → OutlookAlerterUI.start(showWindow=false)
      └── Console (--console) → OutlookAlerterConsole.start(daemonMode=true)
```

## Scheduling

| Scheduler | Interval | Purpose |
|---|---|---|
| `calendarScheduler` | `resyncIntervalMinutes` (default 240 min) | Calls `refreshCalendarEvents()` → Graph API fetch |
| `alertScheduler` | 60 seconds | Calls `checkAlertsFromCache()` → checks cached events for alert threshold |

- Both schedulers use `ScheduledExecutorService` with daemon threads.
- Sleep/wake detection (`MacSleepWakeMonitor`) restarts schedulers on wake.
- Alert cache refresh triggered if > 4 hours stale.

## Data Flow

```
calendarScheduler (background)
  → refreshCalendarEvents()
    → OutlookClient.getUpcomingEventsUsingCalendarView()
      → validates/refreshes token
      → HTTP GET /me/calendarView?startDateTime=...&endDateTime=...
      → parseEventResponse(json) → List<CalendarEvent>
    → updateEventsDisplay(events)  [EDT]
    → cache in lastFetchedEvents

alertScheduler (background, every 60s)
  → checkAlertsFromCache()
    → checkForEventAlerts(cachedEvents)  [EDT]
      → for each event: check minutesToStart <= alertMinutes && not already alerted
      → performFullAlert(bannerText, title, message, events)
```

## Authentication Flow

```
OutlookClient.authenticate()
  ├── Check existing token → isTokenAlreadyValid()
  ├── Try refresh token → refreshToken() via tokenEndpoint
  ├── If refresh fails → performDirectAuthentication()
  │     └── getTokensFromUser()
  │           ├── GUI mode → outlookAlerterUI.performFullAlert() + promptForTokens()
  │           └── Console mode → SimpleTokenDialog.show()
  └── validateTokenWithServer(token) → HTTP GET /me with token
```

## Threading Model

| Thread | Purpose | Swing-Safe |
|---|---|---|
| EDT (Event Dispatch Thread) | All Swing UI operations | Required |
| CalendarScheduler | Periodic API fetch | No — must use `invokeLater` for UI |
| AlertScheduler | Periodic alert check | No — dispatches to EDT |
| AlertBeepThread | Audio beep sequence | N/A (no UI) |
| ScreenFlasherThread | Flash window management | Uses `invokeAndWait` internally |

**Critical rule:** All Swing component creation/manipulation MUST happen on the EDT. `MacScreenFlasher.flashMultiple()` uses `SwingUtilities.invokeAndWait()` for window creation.

## Configuration Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `preferredTimezone` | String | System default | Display timezone |
| `alertMinutes` | int | 1 | Minutes before meeting to alert |
| `flashColor` | String | `#800000` | Flash background color (hex) |
| `flashTextColor` | String | `#ffffff` | Flash text color (hex) |
| `flashOpacity` | double | 1.0 | Flash window opacity (0.0-1.0) |
| `flashDurationSeconds` | int | 5 | How long flash stays visible |
| `resyncIntervalMinutes` | int | 240 | Calendar refresh interval |
| `alertBeepCount` | int | 5 | Number of audio beeps per alert |
| `signInUrl` | String | Graph Explorer URL | Sign-in page URL |
| `tokenEndpoint` | String | (empty) | OAuth token refresh endpoint |
| `defaultIgnoreCertValidation` | boolean | false | Skip SSL cert validation |
| `clientId`, `clientSecret`, `tenantId`, `redirectUri` | String | (empty) | OAuth app registration |
| `accessToken`, `refreshToken` | String | (empty) | Stored OAuth tokens |

## Build & Run

```bash
# Build
mvn package

# Run (GUI mode, default)
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar

# Run (console mode)
java -jar target/OutlookAlerter-1.0.0-SNAPSHOT-jar-with-dependencies.jar --console

# macOS app bundle is at: target/OutlookAlerter.app
```

## Verify build result

```bash
mvn package 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE|ERROR.*line|ERROR.*Cannot|ERROR.*type"
```
