# Quick Reference: macOS Sleep/Wake Fixes

## What Was Fixed

✅ **Application freezing after Mac wakes from sleep**
✅ **Screen flash windows getting stuck and not clearing**
✅ **Dialogs not appearing or showing blank after wake**
✅ **Menu unresponsive after wake**

## How It Works

### 1. Sleep/Wake Detection
- New `MacSleepWakeMonitor` detects when Mac wakes by monitoring time jumps
- Automatically triggers recovery actions when wake is detected

### 2. Automatic Recovery
When Mac wakes up, the application now:
- Cleans up any stuck flash windows
- Restarts all background schedulers
- Refreshes calendar data
- Resets system state tracking

### 3. EDT Health Monitoring
- Watchdog thread monitors Event Dispatch Thread (EDT) health
- Attempts emergency cleanup if EDT becomes unresponsive
- Prevents complete application freeze

### 4. Resilient Schedulers
- All background threads are now daemon threads
- Faster timeouts for scheduler shutdown (3s instead of 5s)
- Better error handling during shutdown/restart

## Key Log Messages to Watch For

**Normal Operation:**
```
[SleepWakeMonitor] Starting sleep/wake monitoring
```

**Wake Event Detected:**
```
[SleepWakeMonitor] Wake event detected! Time jump: 125 seconds
[MacScreenFlasher] Wake event detected, updating wake time
[OutlookAlerterUI] Wake event detected - restarting schedulers
[OutlookAlerterUI] Schedulers restarted successfully
```

**Emergency Recovery:**
```
[EDT WATCHDOG] CRITICAL: EDT unresponsive for 12 seconds - attempting recovery
[EDT WATCHDOG] Attempting emergency cleanup
[MacScreenFlasher] Cleaning up 2 windows that may have been stuck during sleep
```

## Testing

### Quick Test
1. Start application
2. Close Mac lid for 2-3 minutes
3. Open lid
4. Click menu items - should respond immediately
5. Trigger a test alert - should flash and clear properly

### Thorough Test
1. Start application in the morning
2. Let Mac sleep overnight
3. Wake up Mac next day
4. Verify:
   - Menu responsive
   - Settings dialog opens properly
   - Meeting alerts still work
   - No "Not Responding" in Activity Monitor

## Troubleshooting

### If application still hangs:
1. Check logs for wake event detection
2. Look for EDT watchdog messages
3. Try manually restarting the application
4. Report issue with log excerpt

### If flash windows still get stuck:
1. Check for EDT watchdog emergency cleanup messages
2. Verify flash duration setting (should be 5-7 seconds)
3. Check if wake event was detected in logs

## Configuration

No configuration changes needed - the fixes work automatically on macOS.

## Files Changed
- ✨ **NEW:** `MacSleepWakeMonitor.groovy` - Sleep/wake detection
- 📝 **Modified:** `MacScreenFlasher.groovy` - Improved cleanup and wake handling
- 📝 **Modified:** `OutlookAlerterUI.groovy` - Scheduler resilience and wake integration
- 📝 **Documentation:** `SLEEP_WAKE_FIX_SUMMARY.md` - Detailed implementation notes

## Build Info
- Built: November 17, 2025
- Status: ✅ SUCCESS
- JAR: `target/OutlookAlerter-2.0.0-jar-with-dependencies.jar`

