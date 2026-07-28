# Walkthrough - Request Unrestricted Background Usage

I have implemented the logic to request exemption from battery optimizations to ensure reliable background synchronization.

## Changes Made

### Resources

#### [strings.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/values/strings.xml)
- Added strings for the background sync request dialog (`battery_opt_title`, `battery_opt_message`, etc.).

### UI Component

#### [MainActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
- Added `checkBatteryOptimizations()` method which checks if the app is currently ignoring battery optimizations.
- If not exempted, it shows an `AlertDialog` explaining why this is needed.
- If the user clicks "Allow", it launches the system intent `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Verification Results

### Automated Tests
- Build successful.

### Manual Verification
- The app now checks for battery optimization status on startup.
- If battery optimization is enabled for the app, a dialog is presented to the user.
- Upon clicking "Allow", the system request dialog is shown.

> [!WARNING]
> Use of `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is subject to Google Play policies. Ensure that your app's core functionality (reliable background sync) justifies this request according to their guidelines.
