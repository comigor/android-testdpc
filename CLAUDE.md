# Android TestDPC / Shadow App

## Project Overview
This is a modified version of Android's TestDPC (Device Policy Controller) with anti-theft and decoy profile features. The app is called "Shadow" (package: `dev.borges.shadow`).

## Key Features
- **Device Owner Mode**: App must be set as device owner via ADB
- **Decoy Profile**: Creates a secondary user ("Guest") that acts as a decoy when wrong password is entered
- **Anti-theft**: Power button detection, theft mode lock screen, SMS commands
- **FRP (Factory Reset Protection)**: Configure which Google accounts can unlock after factory reset

## Critical Files

### Core Components
- `src/main/java/dev/borges/shadow/SettingsActivity.java` - Main settings UI, decoy profile creation, return to owner functionality
- `src/main/java/dev/borges/shadow/LockTaskSwitchActivity.java` - Handles decoy switch with screen dimming trick
- `src/main/java/com/afwsamples/testdpc/DeviceAdminReceiver.java` - Handles device admin events, password failures trigger decoy switch
- `src/main/java/com/afwsamples/testdpc/comp/DeviceOwnerService.java` - Cross-user service for switching back to owner

### AIDL Interfaces
- `src/main/aidl/com/afwsamples/testdpc/comp/IDeviceOwnerService.aidl` - Interface for cross-user communication

## User Switching Mechanism

### Switching TO Decoy (on wrong password)
1. `DeviceAdminReceiver.onPasswordFailed()` triggers `executeDecoySwitch()`
2. `executeDecoySwitch()` launches `LockTaskSwitchActivity`
3. Activity enters lock task mode, sets brightness to 0, then switches user
4. `dpm.lockNow()` is called to turn screen off, hiding the "Switching to System..." dialog
5. `DISALLOW_USER_SWITCH` restriction is added to trap user in decoy

### Switching BACK to Owner
1. User clicks "Return to Owner" in decoy's SettingsActivity
2. `returnToRealProfile()` uses `dpm.bindDeviceAdminServiceAsUser()` to connect to `DeviceOwnerService` in user 0
3. `DeviceOwnerService.switchToOwner()` marks timestamp and clears restriction
4. `dpm.switchUser(null)` switches to owner; timestamp prevents bounce-back to decoy

### Affiliation IDs
For cross-user service binding to work, both device owner (user 0) and profile owner (decoy user) must have matching affiliation IDs. This is set in `SettingsActivity.setAffiliationIds()`.

## ADB Commands

### Escape from locked decoy profile
```bash
adb shell "dumpsys activity service dev.borges.shadow/com.afwsamples.testdpc.DeviceAdminService switch-user 0"
```

### Check current user
```bash
adb shell "am get-current-user"
```

### List users
```bash
adb shell "pm list users"
```

## Build

```bash
./gradlew assembleDebug
```

APK output: `build2/outputs/apk/debug/Test DPC-debug.apk`

## Known Issues & Solutions

### "Switching to System..." popup (SOLVED)
Android shows a system dialog "Switching to System..." that cannot be suppressed. Solution: set screen brightness to 0 and call `lockNow()` immediately after initiating the switch. The screen goes dark before the dialog appears, making it invisible to the user.

### Manifest Typos (FIXED)
- Line 393 had `android.permission` instead of `android:permission` for DeviceAdminService
- Line 275 had `android.launchMode` instead of `android:launchMode` for WorkPolicyInfoActivity

### ShellCommand.java
This file has been broken by previous modifications. If compilation fails with cascade errors in this file, restore it from git:
```bash
git show HEAD:src/main/java/com/afwsamples/testdpc/ShellCommand.java > /tmp/sc.java && cp /tmp/sc.java src/main/java/com/afwsamples/testdpc/ShellCommand.java
```

## SharedPreferences Keys (shadow_prefs)
- `decoy_serial` - Serial number of the decoy user

## Return-to-Owner Protection
Uses a static variable `lastSwitchToOwnerTime` in `DeviceAdminReceiver` instead of SharedPreferences. When returning to owner, `markSwitchingToOwner()` is called, and `executeDecoySwitch()` checks this timestamp to avoid immediately switching back to decoy.

## Decoy User Name
The decoy user is named "System". The system switch dialog "Switching to System..." is hidden by the brightness trick, but the name is kept in case the screen darkening fails. If you have an existing decoy with a different name, delete and recreate it.

## Permissions Required
- `INTERACT_ACROSS_USERS` - For cross-user operations (signature-level, only works for device owner)
- `BIND_DEVICE_ADMIN` - Required on services for device admin binding
- `WRITE_SETTINGS` - For changing screen brightness during decoy switch
