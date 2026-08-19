# Fix App Crash on First Launch

The app crashes on first launch due to a `SecurityException` when trying to access the Contacts Provider before the user has granted the necessary permissions. The `performAutoSetup` task is triggered immediately when the accounts list is empty, which happens before the permission request dialog is even shown or handled.

## Proposed Changes

### UI Layer

#### [MODIFY] [MainActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
- Update the `viewModel.accounts` collection logic to only trigger `performAutoSetup()` if permissions are already granted.
- In the permission request result callback, trigger `performAutoSetup()` if it was deferred and the accounts list is still empty.

### Data Layer

#### [MODIFY] [AccountRepository.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountRepository.kt)
- Add a permission check in `ensureContactsAreVisible` to prevent crashes if it's called unexpectedly without permissions.
- Wrap the `ContentResolver` operations in `ensureContactsAreVisible` with a try-catch block for added safety.

## Verification Plan

### Automated Tests
- N/A (UI-based permission flow is best verified manually)

### Manual Verification
1. Uninstall the app to reset permissions.
2. Launch the app.
3. Observe that the permission dialog appears.
4. Grant the permissions.
5. Verify that the auto-setup starts correctly after permissions are granted and the app does not crash.
6. Verify that if permissions are denied, the app shows the Snackbar and doesn't crash.
