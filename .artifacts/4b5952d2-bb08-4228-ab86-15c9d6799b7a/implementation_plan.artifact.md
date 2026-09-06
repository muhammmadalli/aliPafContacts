# Robust Haptic Feedback Implementation

The goal is to ensure vibrations occur on button clicks by using a more robust method that ignores global system settings and fallbacks to the `Vibrator` service if the standard view-based haptics fail.

## User Review Required

> [!IMPORTANT]
> This implementation will use `HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING`, which means the app will vibrate even if the user has disabled "Touch feedback" in their system settings.

## Proposed Changes

### UI Utilities

#### [NEW] [ViewExtensions.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/util/ViewExtensions.kt)
- Create a shared extension function `View.performRobustHapticFeedback()` that uses `performHapticFeedback` with the `FLAG_IGNORE_GLOBAL_SETTING` flag and falls back to the `Vibrator` service for a 50ms pulse.

### UI Components (Code)

#### [MODIFY] [AppInfoActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/AppInfoActivity.kt)
- Import `ali.paf.contacts.util.performRobustHapticFeedback`.
- Use `it.performRobustHapticFeedback()` in `btnUserGuide` click listener.

#### [MODIFY] [AccountsAdapter.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/AccountsAdapter.kt)
- Import `ali.paf.contacts.util.performRobustHapticFeedback`.
- Use `it.performRobustHapticFeedback()` in `btnSync` click listener.

## Verification Plan

### Automated Tests
- Build the project to ensure no compilation errors.

### Manual Verification
- Deploy to a physical device.
- Tap the **Sync** button and the **User Guide** button.
- Verify that a distinct vibration pulse is felt.
