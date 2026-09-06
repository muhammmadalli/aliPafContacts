# Request Unrestricted Background Usage

The app needs to run in the background to ensure contact synchronization happens reliably. This requires being exempted from battery optimizations (Doze mode and App Standby).

## User Review Required

> [!IMPORTANT]
> Requesting `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is strictly regulated by Google Play. Since this is a sync app, it is a valid use case, but the user must be informed why this is necessary.

## Proposed Changes

### Resources

#### [MODIFY] [strings.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/values/strings.xml)
- Add strings for the battery optimization request dialog.

### UI Component

#### [MODIFY] [MainActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
- Add a check for `isIgnoringBatteryOptimizations` in `onCreate`.
- Implement a dialog to explain the need for background execution.
- Launch `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` if the user accepts.

## Verification Plan

### Manual Verification
- Deploy the app to a device/emulator.
- Verify that a dialog appears asking for background usage permission if not already granted.
- Verify that clicking "Allow" takes the user to the system dialog (or settings) to grant the permission.
- Use `adb shell dumpsys deviceidle force-idle` to test if the app is indeed exempted (it should still be able to run sync if exempted, though other restrictions might still apply).
