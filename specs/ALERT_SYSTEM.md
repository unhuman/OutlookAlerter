# OutlookAlerter — Alert System Spec

## Overview

The alert system notifies users of upcoming calendar events (and token entry requirements) through four simultaneous components: audio beeps, screen flash, banner frame overlay, and system tray notification. All four are coordinated through a single entry point.

## Entry Point: `performFullAlert()`

**Location:** `OutlookAlerterUI.groovy`  
**Signature:**
```groovy
void performFullAlert(String bannerText, String notificationTitle,
                      String notificationMessage, List<CalendarEvent> events)
```

### Callers

| Caller | Location | Context |
|---|---|---|
| `checkForEventAlerts()` | OutlookAlerterUI | Real meeting alerts (from alert scheduler) |
| Test Alerts button | OutlookAlerterUI | User-triggered test |
| `getTokensFromUser()` | OutlookClient | Token failure (first attempt only) |

### Execution Order

```
performFullAlert()
 ├─ 1. AudioBeepThread  → background thread → n beeps at 250ms intervals
 ├─ 2. onFlashReady callback registered → will fire showAlertBanner() once flash is visible
 ├─ 3. ScreenFlasherThread → background thread → flashMultiple(events)
 │     └─ invokeAndWait: create flash windows on EDT
 │     └─ invokeLater: fire onFlashReady → showAlertBanner() on EDT
 └─ 4. invokeLater: showTrayNotification() on EDT
```

**Key design constraints:**
- Beeps start immediately on a daemon thread (highest urgency)
- Flash windows are created on EDT via `invokeAndWait` (Swing thread safety)
- Banner waits for flash via `onFlashReady` callback (prevents banner-under-flash flicker)
- If `events` is null/empty, banner shows immediately without waiting for flash
- Tray notification is posted to EDT independently

---

## Component 1: Audio Beeps

- **Thread:** `AlertBeepThread` (daemon)
- **Count:** `configManager.alertBeepCount` (clamped ≥ 0)
- **Interval:** 250ms between beeps
- **API:** `Toolkit.getDefaultToolkit().beep()`
- **Interruption-safe:** catches `InterruptedException`, sets interrupt flag

## Component 2: Screen Flash

### Interface

```groovy
interface ScreenFlasher {
    void flash(CalendarEvent event)
    void flashMultiple(List<CalendarEvent> events)
}
```

### Factory

`ScreenFlasherFactory.createScreenFlasher()` returns:
- **macOS** → `MacScreenFlasher`
- **Windows** → `WindowsScreenFlasher`
- **Other** → `CrossPlatformScreenFlasher`

### MacScreenFlasher

**Configuration (from ConfigManager):**

| Property | Default | Description |
|---|---|---|
| `flashColor` | `#800000` | Background color |
| `flashTextColor` | `#ffffff` | Text color |
| `flashOpacity` | `1.0` | Window opacity |
| `flashDurationSeconds` | `5` | Display duration |

**Window creation (`flashMultiple`):**
1. Validates system state, display environment, and event content
2. Cleans up any existing flash windows (`forceCleanup()`)
3. On EDT (via `invokeAndWait`): iterates `GraphicsEnvironment.getScreenDevices()`, calls `createFlashWindowForScreen()` per screen
4. Adds frames to `activeFlashFrames` tracking list
5. Fires one-shot `onFlashReady` callback via `invokeLater`
6. Requests macOS user attention via `Taskbar.requestUserAttention(true, true)`
7. Starts cleanup timer

**Flash window properties:**
- `JFrame`, undecorated, `Type.POPUP`
- Full-screen bounds per monitor
- `alwaysOnTop = true`
- Custom `JPanel` painting: colored background + event text

### Elevation Timer (per flash window)

Ensures flash windows remain visible above other applications.

| Parameter | Value |
|---|---|
| Initial delay | 100ms |
| Repeat interval | 200ms |
| Max attempts | 5 |

**Behavior per tick:**

| Tick | Action |
|---|---|
| 1st | Toggle `alwaysOnTop` off→on, `frame.toFront()` |
| 2nd–5th (no overlays) | `frame.toFront()` |
| 2nd–5th (with overlays) | Skip flash `toFront()` (prevents flicker) |
| Every tick | Re-elevate all registered overlay windows via `overlay.toFront()` |

**Rationale:** After the first tick establishes visibility, calling `toFront()` on the flash when banner overlays are present would momentarily push the flash above the banner, causing visible flicker. Skipping flash `toFront()` when overlays exist eliminates this.

### Cooperative Overlay System

Allows banner windows to coexist above flash windows without timer conflicts.

```groovy
// Static shared state in MacScreenFlasher
private static final List<JFrame> overlayWindows = new CopyOnWriteArrayList<JFrame>()

static void registerOverlayWindows(List<JFrame> windows)
static void clearOverlayWindows()
```

- Banner calls `registerOverlayWindows()` immediately after creating banner windows
- Banner calls `clearOverlayWindows()` when banners are disposed (5s hide timer)
- Elevation timer re-elevates registered overlay windows after each tick
- `CopyOnWriteArrayList` for thread safety (elevation timer on EDT, registration from caller thread)

### onFlashReady Callback

```groovy
private static volatile Runnable onFlashReady = null
static void setOnFlashReady(Runnable callback)
```

- **Set by:** `performFullAlert()` before starting flash thread
- **Fired by:** `flashMultiple()` via `invokeLater` immediately after `invokeAndWait` completes
- **One-shot:** cleared to `null` after firing
- **Purpose:** Shows banner at exactly the right moment — after flash windows exist but before elevation timer fights begin
- **Volatile:** ensures visibility across threads (set on caller thread, read on flash thread)

---

## Component 3: Alert Banner

**Location:** `OutlookAlerterUI.showAlertBanner(String message)`  
**Tracking field:** `private List<JFrame> alertBannerWindows = []`

### Window Structure

One transparent `JFrame` per screen, painting only a red border frame:

```
┌───────────────── borderThickness ─────────────────┐
│              ▓▓▓ BANNER TEXT ▓▓▓                  │  ← top bar (red, with white text)
├─────┬───────────────────────────────────────┬─────┤
│     │                                       │     │
│  ◀──│    transparent center (click-through) │──▶  │  ← left/right strips
│     │                                       │     │
├─────┴───────────────────────────────────────┴─────┤
│                                                   │  ← bottom strip
└───────────────────────────────────────────────────┘
```

### Window Properties

| Property | Value |
|---|---|
| Type | `JFrame`, undecorated, `Type.POPUP` |
| Background | `Color(0, 0, 0, 0)` — fully transparent |
| Content pane | `JPanel` with `opaque=false`, custom `paintComponent` |
| Always on top | `true` |
| Border color | `Color(220, 0, 0)` — bright red |
| Text color | `Color.WHITE` |
| Text font | system default, Bold, 18pt |

### Menu Bar Offset

```groovy
Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(screen.getDefaultConfiguration())
int frameY = bounds.y + screenInsets.top      // below menu bar
int frameH = bounds.height - screenInsets.top  // reduced height
```

On macOS, `screenInsets.top` is the menu bar height (typically 25px). The banner frame starts below the menu bar so text is not clipped.

### Border Thickness Calculation

```groovy
JLabel sizeRef = new JLabel("X")
sizeRef.setFont(sizeRef.getFont().deriveFont(Font.BOLD, 18f))
sizeRef.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20))
int borderThickness = sizeRef.getPreferredSize().height + 10
```

Dynamic — adapts to font metrics. Typically ~45px.

### Lifecycle

1. Dispose existing banners (`alertBannerWindows.each { it.dispose() }`)
2. Create one `JFrame` per screen with custom painting
3. Register with `MacScreenFlasher.registerOverlayWindows()`
4. Start auto-hide `Timer(5000)`:
   - `MacScreenFlasher.clearOverlayWindows()`
   - Dispose all banner windows
   - Clear `alertBannerWindows` list

**Auto-hide duration:** 5000ms (hardcoded)

---

## Component 4: Tray Notification

```groovy
showTrayNotification(notificationTitle, notificationMessage, TrayIcon.MessageType.INFO)
```

- Posted via `invokeLater` (EDT required for Swing tray)
- Independent of flash/banner timing
- Standard `java.awt.TrayIcon.displayMessage()`

---

## Alert Decision Logic

**Location:** `checkForEventAlerts(List<CalendarEvent> events)`  
**Called by:** `checkAlertsFromCache()` (alert scheduler, every 60s)

### Per-Event Evaluation

```
for each event:
  ├─ Skip if event.hasEnded() → also remove from alertedEventIds
  ├─ minutesToStart = event.getMinutesToStart() + 1  (account for current time)
  ├─ Skip if alertedEventIds.contains(event.id)
  └─ Alert if: minutesToStart <= configManager.alertMinutes AND minutesToStart >= -1
```

### Aggregation

- Events passing the filter are collected into `eventsToAlert`
- All are marked in `alertedEventIds` **before** `performFullAlert()` (prevents duplicate alerts if a component fails)
- Single event → specific text: `"Upcoming meeting: <subject>"`
- Multiple events → aggregated: `"N upcoming meetings starting soon"`

---

## Token Failure Alert

**Location:** `OutlookClient.getTokensFromUser()`

On the first attempt (of up to 3), creates a synthetic `CalendarEvent`:
```groovy
CalendarEvent tokenEvent = new CalendarEvent(
    subject: "⚠️ TOKEN ENTRY REQUIRED ⚠️",
    startTime: ZonedDateTime.now(),
    endTime: ZonedDateTime.now().plusMinutes(1),
    isOnlineMeeting: false,
    organizer: "Token Entry",
    responseStatus: "Flash",
    calendarName: "Token Entry")
```

Then calls:
```groovy
outlookAlerterUI.performFullAlert(
    "⚠️ Token Entry Required - Please enter your OAuth tokens",
    "Token Entry Required",
    "Please enter your OAuth tokens in the dialog that will appear.",
    [tokenEvent])
```

**Console-mode fallback:** If `outlookAlerterUI` is null, fires flash only via `ScreenFlasherFactory.createScreenFlasher().flash(tokenEvent)`.

---

## Threading Summary

```
                    ┌──────────────────────────────────┐
                    │      performFullAlert()           │
                    │      (caller thread)              │
                    └──┬──────┬──────────┬─────────────┘
                       │      │          │
            ┌──────────▼┐  ┌──▼────────┐ │
            │ BeepThread │  │ FlashThread│ │
            │ (daemon)   │  │ (daemon)  │ │
            └────────────┘  └──┬────────┘ │
                               │          │
                    ┌──────────▼──────┐   │
                    │  invokeAndWait  │   │
                    │  (create flash  │   │
                    │   windows)      │   │
                    └──────┬──────────┘   │
                           │              │
                    ┌──────▼──────┐  ┌────▼───────────┐
                    │ invokeLater │  │  invokeLater   │
                    │ onFlashReady│  │  trayNotify    │
                    │→ showBanner │  │                │
                    └─────────────┘  └────────────────┘
                           │
                    ┌──────▼──────────────┐
                    │   EDT               │
                    │ • banner windows    │
                    │ • registerOverlays  │
                    │ • elevation timer   │
                    │ • hide timer (5s)   │
                    └─────────────────────┘
```
