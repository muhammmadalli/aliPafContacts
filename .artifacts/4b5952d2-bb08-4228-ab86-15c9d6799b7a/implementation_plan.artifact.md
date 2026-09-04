# Fix Haptic Feedback for Buttons

The goal is to ensure haptic feedback (vibrations) occur when the user interacts with specific buttons in the app. Currently, the `VIBRATE` permission is missing, and explicit triggering in code will be added for the remaining target buttons to ensure reliability.

## Proposed Changes

### Configuration

#### [MODIFY] [AndroidManifest.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/AndroidManifest.xml)
- Add `android.permission.VIBRATE` permission.

### UI Components (Code)

#### [MODIFY] [AppInfoActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/AppInfoActivity.kt)
- Trigger haptic feedback on `btnUserGuide` click.

#### [MODIFY] [AccountsAdapter.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/AccountsAdapter.kt)
- Trigger haptic feedback on `btnSync` click.

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors.

### Manual Verification
- Deploy the app to a physical device.
- Tap on the Sync button in the main screen and the User Guide button in the App Info screen.
- Verify that haptic feedback (a brief vibration) is felt.
- Ensure "Touch feedback" is enabled in the device's system settings.
