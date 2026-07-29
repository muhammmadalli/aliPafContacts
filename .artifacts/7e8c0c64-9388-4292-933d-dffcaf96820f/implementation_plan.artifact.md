# Hardcode Login and Address Book Setup

The user wants to skip the manual entry of URL, username, password, and the manual selection of an address book by hardcoding these values into the app.

## Proposed Changes

### [AccountConfig](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountConfig.kt)
Add constants for the hardcoded credentials and address book URL.
- `HARDCODED_BASE_URL`
- `HARDCODED_USERNAME`
- `HARDCODED_PASSWORD`
- `HARDCODED_ADDRESSBOOK_URL`
- `HARDCODED_DISPLAY_NAME`

### [MainActivity](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
Modify `onCreate` to check if any accounts exist. If not, and if hardcoded credentials are provided, automatically trigger the account creation logic.

### [AccountRepository](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountRepository.kt)
No changes needed, but it will be used by `MainActivity` to perform the setup.

## Implementation Steps

1.  **Define Constants**: Add the server URL, username, password, and address book URL to `AccountConfig.kt`.
2.  **Auto-Setup Logic**: In `MainActivity.kt`, check `viewModel.accounts` (or `accountRepository.getMainAccounts()`). If it's empty, call a new function `performHardcodedSetup()`.
3.  **Perform Setup**: The `performHardcodedSetup()` function will:
    - Create the main account using `accountRepository.createMainAccount`.
    - Create the address book account using `accountRepository.createOrUpdateAddressBook`.
    - Trigger an initial sync using `accountRepository.syncNowDirect`.

## User Review Required

> [!IMPORTANT]
> Hardcoding passwords in source code is a security risk. This should only be done for internal/testing builds or if the user is aware of the implications.

## Open Questions

- Should we still allow the user to add other accounts manually? (The proposed plan still allows this via the FAB).
- Do you have the specific Address Book URL? It's usually a long path like `https://server.com/remote.php/dav/addressbooks/users/username/contacts/`.

## Verification Plan

### Manual Verification
- Clear app data to simulate a fresh install.
- Launch the app.
- Verify that the account is created automatically and sync starts without any user input.
