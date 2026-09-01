# Walkthrough - Fix First Launch Crash

I have fixed the `SecurityException` crash that occurred on the first launch of the app. The root cause was the automatic account setup process attempting to access the Android Contacts Provider before the user had granted the necessary `READ_CONTACTS` and `WRITE_CONTACTS` permissions.

## Changes Made

### UI Layer

#### [MainActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
- **Deferred Auto-Setup**: Modified the logic that monitors the account list. It now checks if permissions are granted before calling `viewModel.performAutoSetup()`.
- **Permission Result Handling**: Added logic to the permission request callback to trigger `viewModel.refresh()` and `viewModel.performAutoSetup()` immediately after the user grants permissions.

### Data Layer

#### [AccountRepository.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountRepository.kt)
- **Runtime Permission Check**: Added a explicit check for `READ_CONTACTS` and `WRITE_CONTACTS` permissions within `ensureContactsAreVisible()`.
- **Error Handling**: Wrapped the `ContentResolver` update/insert operations in a `try-catch` block to handle `SecurityException` (and other generic exceptions) gracefully, preventing an app crash even if permissions are missing at the moment of execution.

## Verification Results

### Manual Verification Required
1. **Uninstall/Clear Data**: Uninstall the app or clear its storage to reset all permissions and accounts.
2. **Launch App**: Open the app for the "first time".
3. **Grant Permissions**: The permission dialog should appear. Tap "Allow".
4. **Auto-Setup**: Verify that the account configuration begins automatically *after* you grant the permissions.
5. **No Crash**: The app should no longer crash immediately upon launch.
