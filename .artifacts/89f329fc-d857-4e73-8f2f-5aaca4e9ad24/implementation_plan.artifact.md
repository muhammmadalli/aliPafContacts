# Implementation Plan - Random Sync Interval (30-45 Days)

The goal is to change the fixed 4-hour synchronization interval to a random interval between 30 and 45 days. This interval should be randomized every time a sync completes to ensure non-predictable background activity.

## User Review Required

> [!IMPORTANT]
> A sync interval of 30 to 45 days is extremely long for a contacts app. This means changes on the server may not appear on the phone for up to a month and a half unless a manual sync is triggered. Please confirm this is the desired behavior.

## Proposed Changes

### [Account Configuration](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountConfig.kt)

#### [MODIFY] [AccountConfig.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountConfig.kt)
- Add constants for `SYNC_MIN_DAYS` (30) and `SYNC_MAX_DAYS` (45).

### [Account Repository](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountRepository.kt)

#### [MODIFY] [AccountRepository.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/account/AccountRepository.kt)
- Implement `scheduleRandomPeriodicSync(account: Account)`:
    - Calculates a random number of seconds between 30 and 45 days.
    - Updates the periodic sync for the given account using `ContentResolver.addPeriodicSync`.
- Update `createOrUpdateAddressBook` to call `scheduleRandomPeriodicSync` instead of using the hardcoded 4-hour interval.

### [Sync Adapter](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/ContactsSyncAdapterService.kt)

#### [MODIFY] [ContactsSyncAdapterService.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/ContactsSyncAdapterService.kt)
- In `onPerformSync`, after `ContactsSyncManager(...).performSync()` completes successfully:
    - Call `AccountRepository(context).scheduleRandomPeriodicSync(account)` to randomize the next sync interval.

## Verification Plan

### Manual Verification
- I will verify the logic by temporarily reducing the days to minutes and logging the calculated intervals.
- Ensure that calling `addPeriodicSync` with a new interval correctly updates the existing sync schedule (this is standard Android behavior).
