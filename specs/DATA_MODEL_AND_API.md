# OutlookAlerter — Data Model & API Spec

## CalendarEvent

**Location:** `com.unhuman.outlookalerter.model.CalendarEvent`

### Fields

| Field | Type | Source (Graph API) |
|---|---|---|
| `id` | `String` | `id` |
| `subject` | `String` | `subject` |
| `startTime` | `ZonedDateTime` | `start.dateTime` + `start.timeZone` |
| `endTime` | `ZonedDateTime` | `end.dateTime` + `end.timeZone` |
| `location` | `String` | `location.displayName` |
| `organizer` | `String` | `organizer.emailAddress.name` |
| `isOnlineMeeting` | `boolean` | `isOnlineMeeting` |
| `onlineMeetingUrl` | `String` | `onlineMeeting.joinUrl` (populated regardless of `isOnlineMeeting` flag) |
| `bodyPreview` | `String` | `bodyPreview` (truncated ~255 chars plain text) |
| `bodyHtml` | `String` | `body.content` (full HTML body, used for meeting URL extraction) |
| `calendarName` | `String` | Set by caller (multi-calendar) |
| `responseStatus` | `String` | `responseStatus.response` |

### Computed Properties

| Method | Returns | Logic |
|---|---|---|
| `getMinutesToStart()` | `int` | `(startTime.toInstant().toEpochMilli() - ZonedDateTime.now(startTime.zone).toInstant().toEpochMilli()) / 60000`. Returns `Integer.MIN_VALUE` if `startTime` is null. |
| `isInProgress()` | `boolean` | `now` is after `startTime` and before `endTime` (compared as instants) |
| `hasEnded()` | `boolean` | `now` is after `endTime.toInstant()` |

**Note:** `getMinutesToStart()` uses the event's own timezone for the "now" reference, ensuring correct behavior when the event timezone differs from the system timezone.

---

## ConfigManager

**Location:** `com.unhuman.outlookalerter.core.ConfigManager`  
**Pattern:** Singleton  
**Storage:** `~/.outlookalerter/config.properties` (Java Properties format)

### Constants

All property keys and default values are defined as `private static final String` constants:
- **Key constants** (`KEY_*`): `KEY_CLIENT_ID`, `KEY_CLIENT_SECRET`, `KEY_TENANT_ID`, `KEY_REDIRECT_URI`, `KEY_SIGN_IN_URL`, `KEY_TOKEN_ENDPOINT`, `KEY_LOGIN_HINT`, `KEY_PREFERRED_TIMEZONE`, `KEY_ALERT_MINUTES`, `KEY_DEFAULT_IGNORE_CERT`, `KEY_IGNORE_CERT`, `KEY_FLASH_COLOR`, `KEY_FLASH_TEXT_COLOR`, `KEY_FLASH_OPACITY`, `KEY_FLASH_DURATION`, `KEY_RESYNC_INTERVAL`, `KEY_ALERT_BEEP_COUNT`, `KEY_ALERT_BEEP_AFTER_FLASH`, `KEY_ACCESS_TOKEN`, `KEY_REFRESH_TOKEN`
- **Default constants** (`DEFAULT_*`): `DEFAULT_TENANT_ID`, `DEFAULT_REDIRECT_URI`, `DEFAULT_FLASH_COLOR`, `DEFAULT_FLASH_TEXT_COLOR`, `DEFAULT_FLASH_OPACITY`, `DEFAULT_ALERT_MINUTES`, `DEFAULT_FLASH_DURATION`, `DEFAULT_RESYNC_INTERVAL`, `DEFAULT_ALERT_BEEP_COUNT`, `DEFAULT_FALSE`

### All Properties

| Key | Type | Default | Category |
|---|---|---|---|
| `clientId` | String | `""` | OAuth |
| `clientSecret` | String | `""` | OAuth |
| `tenantId` | String | `"common"` | OAuth |
| `redirectUri` | String | `"http://localhost:8888"` | OAuth |
| `accessToken` | String | `""` | OAuth (persisted) |
| `refreshToken` | String | `""` | OAuth (persisted) |
| `tokenEndpoint` | String | `""` | OAuth |
| `loginHint` | String | `""` | OAuth |
| `signInUrl` | String | Graph Explorer URL | Auth UI |
| `preferredTimezone` | String | `""` (system default) | Display |
| `alertMinutes` | int | `1` | Alert |
| `alertBeepCount` | int | `5` | Alert |
| `flashColor` | String | `"#800000"` | Flash |
| `flashTextColor` | String | `"#ffffff"` | Flash |
| `flashOpacity` | double | `1.0` | Flash |
| `flashDurationSeconds` | int | `5` | Flash |
| `resyncIntervalMinutes` | int | `240` | Scheduler |
| `defaultIgnoreCertValidation` | boolean | `false` | SSL |
| `ignoreCertValidation` | boolean | `false` | SSL (runtime) |

### Persistence

- **Load:** `loadConfiguration()` → reads file, calls `loadPropertiesFromConfig()` to populate fields
- **Save:** `saveConfiguration()` → writes all fields to Properties, stores to file
- **Auto-create:** Creates default config file with comments if missing

### Update Methods

All `update*()` methods set the field and call `saveConfiguration()` immediately:

```java
void updateTokens(String accessToken, String refreshToken, boolean ignoreCertValidation)
void updatePreferredTimezone(String timezone)
void updateAlertMinutes(int minutes)
void updateDefaultIgnoreCertValidation(boolean ignore)
void updateIgnoreCertValidation(boolean ignore)
void updateFlashDurationSeconds(int seconds)
void updateSignInUrl(String url)
void updateFlashColor(String color)
void updateFlashTextColor(String color)
void updateFlashOpacity(double opacity)
void updateResyncIntervalMinutes(int minutes)
void updateAlertBeepCount(int count)
```

---

## Microsoft Graph API

### Endpoints Used

| Endpoint | Method | Purpose |
|---|---|---|
| `/me/calendarView` | GET | Primary — fetch events in date range |
| `/me/calendar/events` | GET | Alternate — events with OData filter |
| `/me/calendars` | GET | List available calendars |
| `/me/calendars/{id}/events` | GET | Events from specific calendar |
| `/me` | GET | Token validation (user profile) |
| Token endpoint (configurable) | POST | OAuth token refresh |

### CalendarView Request

```
GET /me/calendarView
  ?startDateTime={now ISO}
  &endDateTime={endOfTomorrow ISO}
  &$select=id,subject,organizer,start,end,location,isOnlineMeeting,onlineMeeting,bodyPreview
  &$orderby=start/dateTime asc
  &$top=50

Headers:
  Authorization: Bearer {accessToken}
  Accept: application/json
  Prefer: outlook.timezone="{preferredTimezone}"
```

**Time range:** From now to end of tomorrow (fetches ~48 hours of events)

### Response Parsing (`parseEventResponse`)

```json
{
  "value": [
    {
      "id": "AAMk...",
      "subject": "Team Standup",
      "organizer": { "emailAddress": { "name": "John Doe" } },
      "start": { "dateTime": "2024-01-15T14:00:00.0000000", "timeZone": "Pacific Standard Time" },
      "end": { "dateTime": "2024-01-15T14:30:00.0000000", "timeZone": "Pacific Standard Time" },
      "location": { "displayName": "Conference Room A" },
      "isOnlineMeeting": true,
      "onlineMeeting": { "joinUrl": "https://teams.microsoft.com/..." },
      "bodyPreview": "Agenda: ...",
      "responseStatus": { "response": "accepted" }
    }
  ]
}
```

### DateTime Parsing

Graph API returns Windows timezone names (e.g., "Pacific Standard Time") which are mapped to IANA timezone IDs (e.g., "America/Los_Angeles") for `ZonedDateTime` construction.

### Authentication Flow

```
authenticate()
  ├─ 1. isTokenAlreadyValid()    → token exists + not expired? → done
  ├─ 2. refreshToken()           → POST to tokenEndpoint with refresh_token
  │     └─ on 401/400 → fall through
  └─ 3. performDirectAuthentication()
        └─ getTokensFromUser()   → UI prompt or SimpleTokenDialog
        └─ validateTokenWithServer() → GET /me
        └─ configManager.updateTokens()
```

### Retry Logic (`executeRequestWithRetry`)

- On HTTP 401 or 403: calls `handleUnauthorizedResponse()` which attempts token refresh
- Retries the request once with the new token
- If retry also fails, throws exception

### Token Validation

```
validateTokenWithServer(token)
  → GET /me with Authorization: Bearer {token}
  → HTTP 200 → valid
  → else → invalid
```

Validation is throttled: skips server check if last validation was < 15 minutes ago (`SERVER_VALIDATION_INTERVAL_MS`).

---

## Logging

### LogManager

**Location:** `com.unhuman.outlookalerter.util.LogManager`  
**Pattern:** Singleton with in-memory buffer

### Constants

Log level strings are defined as `private static final String` constants:
- `LEVEL_INFO`, `LEVEL_WARN`, `LEVEL_ERROR`

### LogCategory Enum

| Value | Description |
|---|---|
| `DATA_FETCH` | API requests, responses, JSON parsing |
| `MEETING_INFO` | Event details, time calculations |
| `ALERT_PROCESSING` | Alert decisions, component status |
| `GENERAL` | Startup, config, shutdown, misc |

### Methods

```java
void info(LogCategory category, String message)
void error(LogCategory category, String message)
void warn(LogCategory category, String message)
void setLogTextArea(JTextArea area)     // connects to LogViewer
String getLogsAsString()                // for save-to-file
```

### stdout/stderr Interception

`OutlookAlerterUI` replaces `System.out` and `System.err` with custom `PrintStream` wrappers that:
1. Buffer output line-by-line
2. Forward to `LogManager.getInstance().info()` / `.error()` (category: `GENERAL`)
3. Also write to original stream

---

## Platform Utilities

### MacSleepWakeMonitor

**Location:** `com.unhuman.outlookalerter.util.MacSleepWakeMonitor`  
**Pattern:** Singleton  
**Detection:** Polls system time every 30 seconds; if gap > 65 seconds → wake event

```java
static synchronized MacSleepWakeMonitor getInstance()
void startMonitoring()
void addWakeListener(Runnable listener)   // called on wake
```

**On wake:** Triggers `OutlookAlerterUI.restartSchedulers()` + `refreshCalendarEvents()`.

### SingleInstanceManager

**Location:** `com.unhuman.outlookalerter.core.SingleInstanceManager`  
**Mechanism:** File lock on `~/.outlookalerter/outlookalerter.lock`

```java
boolean tryAcquireLock()   // returns false if already locked
void releaseLock()
```

### SSLUtils

**Location:** `com.unhuman.outlookalerter.core.SSLUtils`

```java
static void initializeSSLContext()
static SSLContext createPermissiveSSLContext()   // for ignoreCertValidation
```

### OAuthRedirectServer

**Location:** `com.unhuman.outlookalerter.core.OAuthRedirectServer`

Lightweight HTTP server on `localhost:8888` that listens for OAuth redirect callbacks and extracts the authorization code.
