# OutlookAlerter — Architecture Spec

## Overview

OutlookAlerter is a macOS/Windows desktop application (Java Swing) that monitors Microsoft Outlook calendar events via the Microsoft Graph API and alerts users when meetings are about to start. It runs as a system tray application with periodic polling.

## Project Metadata

| Field | Value |
|---|---|
| Group ID | `com.unhuman` |
| Artifact ID | `OutlookAlerter` |
| Version | `1.0.0-SNAPSHOT` |
| Java Target | 21 (minimum 11) |
| Language | Java 21 |
| Build | Maven (`mvn package`) |
| Main Class | `com.unhuman.outlookalerter.OutlookAlerter` |
| macOS Bundle | jpackage → `target/OutlookAlerter.app` |
| Config File | `~/.outlookalerter/config.properties` |

## Dependencies

- **org.json 20240303** — JSON parsing
- **JNA 5.17.0** — `jna`, `jna-platform` (native OS access, Windows flash)
- **JUnit Jupiter 5.11.4** — unit test framework (test scope)

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
- All scheduled tasks are wrapped with `safeRunScheduledTask()` to catch and log exceptions, preventing silent scheduler death from unhandled `RuntimeException`.

## Reliability & Recovery

The application is designed for long-running, unattended operation (days/weeks). Multiple independent recovery mechanisms ensure it survives network failures, laptop sleep/wake cycles, and transient errors:

### HTTP Timeouts
All Graph API requests use a 30-second read timeout (`REQUEST_TIMEOUT`). This prevents the HTTP client from hanging indefinitely on stale TCP connections after network changes or sleep/wake.

### Stale-Fetch Detection
`refreshCalendarEvents()` records `fetchStartTimeMs` at entry. If a new refresh is requested while an old one appears stuck (>`FETCH_TIMEOUT_MS` = 45s), the stale `fetchInProgress` flag is forcibly cleared and the new fetch proceeds. The flag is always reset in a `finally` block.

### Alert-Scheduler Watchdog
`checkAlertsFromCache()` (runs every 60s) checks whether `lastFetchedEvents` is older than `MAX_CACHE_AGE_MS` (4 hours). If so, it triggers a fresh fetch, acting as a backup if the calendar scheduler has silently died.

### Exception-Safe Scheduling
All `ScheduledExecutorService` tasks are wrapped with `safeRunScheduledTask(name, closure)` which catches any `Throwable`, logs it, and returns normally — preventing `ScheduledExecutorService` from cancelling future executions after a single failure.

### Non-Blocking UI
- Token validation errors use tray notifications instead of modal `JOptionPane` dialogs, preventing EDT blocking.
- `hasValidToken()` checks during tray icon setup run asynchronously off the EDT.
- Authentication prompts during direct auth use tray notifications instead of blocking dialogs.

### Sleep/Wake Recovery
`MacSleepWakeMonitor` detects sleep via time-jump polling (threshold: 65s). On wake, registered listeners restart both schedulers and trigger an immediate calendar refresh.

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

**Non-blocking rule:** No modal `JOptionPane` dialogs are used on the EDT from background threads. Token validation errors and authentication prompts use non-blocking tray notifications to prevent UI freezes.

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

## Testing

| Framework | Version | Notes |
|---|---|---|
| JUnit Jupiter | 5.11.4 | Test runtime |
| maven-surefire-plugin | 3.5.2 | `-Djava.awt.headless=true` |

### Test Structure

```
src/test/java/com/unhuman/outlookalerter/
├── model/
│   └── CalendarEventTest          # 24 tests — time math, state, properties, edge cases
├── core/
│   ├── ConfigManagerTest           # 24 tests — singleton, load/save, all update methods
│   ├── OutlookClientTest           # 11 tests — exception types, constants, constructors
│   └── SingleInstanceManagerTest   #  6 tests — file lock acquire/release/exclusive
├── ui/
│   └── IconManagerTest             # 12 tests — icon generation, caching, valid/invalid
└── util/
    ├── HtmlUtilTest                # 11 tests — HTML escaping, null, XSS, unicode
    ├── LogManagerTest              # 22 tests — singleton, levels, buffer, filtering
    ├── ScreenFlasherFactoryTest    #  5 tests — platform factory, interface contract
    └── MacSleepWakeMonitorTest     #  9 tests — singleton, lifecycle, listeners
```

Total: **124 tests** — all pure Java JUnit 5 unit tests, no mocking frameworks required.

Tests call Java source classes directly. Private fields (e.g., `OutlookClient.GRAPH_ENDPOINT`) are accessed via reflection where necessary.

### Running Tests

```bash
# Tests run automatically as part of the build
mvn package

# Tests only
mvn test
```

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
