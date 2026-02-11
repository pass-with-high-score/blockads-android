# Phase 1 Task 1.2 Implementation: VPN Reconnection & Reliability

## Overview
This document describes the implementation of VPN reconnection and reliability improvements for BlockAds Android (Phase 1, Task 1.2 from IMPROVEMENT_PLAN.md).

## Changes Made

### 1. Network Connectivity Monitoring

**New File: `NetworkMonitor.kt`**
- Created a dedicated network monitoring class that uses `ConnectivityManager` callbacks
- Monitors network availability and validates internet connectivity
- Provides callbacks for `onNetworkAvailable()` and `onNetworkLost()` events
- Properly handles registration and unregistration of network callbacks

**Key Features:**
- Uses `NetworkRequest.Builder` with `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`
- Provides `isNetworkAvailable()` method for manual checks
- Handles errors gracefully with proper exception logging

### 2. VPN Retry Logic with Exponential Backoff

**New File: `VpnRetryManager.kt`**
- Implements retry logic with exponential backoff strategy
- Default configuration: 5 max retries, starting at 1 second, capped at 60 seconds
- Backoff progression: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped)

**Key Features:**
- `shouldRetry()`: Check if more retries are available
- `waitForRetry()`: Suspend function that waits for backoff delay
- `reset()`: Reset retry counter after successful connection
- `getNextRetryDelay()`: Preview next delay without incrementing counter
- `getTimeSinceLastAttempt()`: Track time since last retry attempt

**Unit Tests:**
- Comprehensive test suite in `VpnRetryManagerTest.kt` (14 test cases)
- Tests cover: retry counting, exponential backoff, max delay cap, reset functionality

### 3. Enhanced AdBlockVpnService

**Modified: `AdBlockVpnService.kt`**

#### Network Monitoring Integration
- Network monitor initialized in `onCreate()`
- Monitoring starts when VPN starts, stops when VPN stops
- Auto-reconnect logic in `onNetworkAvailable()`:
  - Checks if auto-reconnect is enabled
  - Checks if VPN was previously enabled
  - Waits 2 seconds for network to stabilize before reconnecting
  - Resets retry counter before attempting reconnection

#### Retry Logic Integration
- VPN establishment extracted to separate `establishVpn()` method
- Main `startVpn()` loop attempts connection with retry logic:
  - Tries to establish VPN
  - If fails and retries available, waits with exponential backoff
  - Updates notification to show retry status
  - Resets retry counter on success
  - Logs failure after max retries exceeded

#### Improved State Management
- Added `isReconnecting` volatile flag to track reconnection state
- Prevents multiple simultaneous reconnection attempts
- All state flags properly cleaned up in `stopVpn()` and `onDestroy()`

#### Enhanced Notifications
- Dynamic notification content based on state:
  - Normal: "BlockAds is active"
  - Reconnecting: "BlockAds is reconnecting" 
  - Retrying: "Retry attempt X of Y"
- `updateNotification()` and `updateNotificationWithRetry()` methods
- Separate string resources for each notification state

#### Better onRevoke() Handling
- Now updates `vpnEnabled` preference to `false` when revoked
- Proper cleanup ensures state consistency
- Logs revocation event with warning level

### 4. Permission Updates

**Modified: `AndroidManifest.xml`**
- Added `ACCESS_NETWORK_STATE` permission (required for `ConnectivityManager`)

**Existing Permissions Verified:**
- ✓ `FOREGROUND_SERVICE_SPECIAL_USE` - Required for Android 14+
- ✓ `foregroundServiceType="specialUse"` in service declaration
- ✓ `SUPPORTS_ALWAYS_ON` metadata for always-on VPN support

### 5. String Resources

**Added Strings (English & Vietnamese):**
- `vpn_notification_reconnecting` - "BlockAds is reconnecting"
- `vpn_notification_reconnecting_text` - "Network changed, reconnecting VPN…"
- `vpn_notification_retrying` - "BlockAds is retrying"
- `vpn_notification_retry_text` - "Retry attempt %1$d of %2$d"

## Technical Details

### Network Change Detection Flow
```
Network Change → ConnectivityManager Callback → NetworkMonitor
                                                       ↓
                                          onNetworkAvailable()
                                                       ↓
                                    Check auto-reconnect enabled
                                                       ↓
                                       Check VPN was enabled
                                                       ↓
                                    Wait 2s for stabilization
                                                       ↓
                                     Reset retry counter
                                                       ↓
                                           Start VPN
```

### VPN Retry Flow
```
startVpn() → Load filters → establishVpn()
                                  ↓
                          Success? → Yes → Reset retry counter → Start packet processing
                                  ↓
                                 No
                                  ↓
                        More retries? → No → Stop service
                                  ↓
                                Yes
                                  ↓
                    Update notification with retry count
                                  ↓
                    Wait with exponential backoff
                                  ↓
                          Retry establishVpn()
```

### Exponential Backoff Strategy
- Formula: `delay = min(initialDelayMs * 2^(retryCount - 1), maxDelayMs)`
- Example progression (1s initial, 60s max):
  - Retry 1: 1 second
  - Retry 2: 2 seconds
  - Retry 3: 4 seconds
  - Retry 4: 8 seconds
  - Retry 5: 16 seconds
  - Retry 6+: 60 seconds (capped)

## Testing Strategy

### Unit Tests
- ✅ VpnRetryManager fully tested with 14 test cases
- ✅ Coverage includes: retry counting, backoff calculation, max delay cap, reset behavior

### Integration Testing Required (Manual)
1. **Network Switching**
   - Test Wi-Fi → Mobile Data transition
   - Test Mobile Data → Wi-Fi transition
   - Test loss and regain of network
   - Verify auto-reconnect works within ~2 seconds of network stabilization

2. **VPN Revocation**
   - Manually revoke VPN permission in system settings
   - Verify service stops gracefully
   - Verify `vpnEnabled` preference is set to false
   - Verify no crashes or ANRs

3. **Retry Logic**
   - Simulate VPN establishment failure (e.g., by modifying VPN builder)
   - Verify exponential backoff delays are correct
   - Verify notification updates with retry count
   - Verify service stops after max retries

4. **Android 14+ Compatibility**
   - Test on Android 14+ device
   - Verify foreground service works correctly
   - Verify always-on VPN hint is respected
   - Check for any permission warnings

## Configuration

Users can control reconnection behavior via existing settings:
- **Auto-reconnect** (default: enabled) - Controls boot receiver and network change reconnection
- No new UI changes required for this task

## Impact

### Reliability Improvements
- ✅ VPN automatically reconnects when network changes
- ✅ Graceful handling of VPN revocation
- ✅ Retry logic prevents permanent failure from transient issues
- ✅ Better user feedback during connection issues

### User Experience
- ✅ No manual intervention needed after network changes
- ✅ Clear notification states (active/reconnecting/retrying)
- ✅ Service stops cleanly after max retries instead of hanging

### Code Quality
- ✅ Separated concerns (NetworkMonitor, VpnRetryManager)
- ✅ Unit tests for retry logic
- ✅ Better error handling and logging
- ✅ Consistent state management

## Future Enhancements (Not in Scope)
- User-configurable retry settings (max attempts, backoff strategy)
- Persistent notification showing reconnection attempts
- Analytics/telemetry for connection reliability metrics
- Smart retry based on network type (more aggressive on Wi-Fi)

## References
- [Android VpnService Documentation](https://developer.android.com/reference/android/net/VpnService)
- [ConnectivityManager Network Callbacks](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- [Android 14 Foreground Service Changes](https://developer.android.com/about/versions/14/changes/fgs-types-required)
