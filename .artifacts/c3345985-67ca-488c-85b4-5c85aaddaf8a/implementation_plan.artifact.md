# Implementation Plan - Fix Sync Back of Locally Deleted Contacts

The user reported that contacts deleted locally from the phone do not sync back from the server, even though only server-to-client sync is desired. Investigation reveals that the sync logic doesn't correctly handle locally deleted or modified contacts when trying to enforce the server state.

## User Review Required

> [!IMPORTANT]
> This change will cause any local modifications (edits or deletions) to be overwritten by the server state on the next sync. This aligns with the "server-to-client only" sync model requested by the user.

## Proposed Changes

### [Contacts Sync Component](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/)

#### [MODIFY] [ContactsSyncManager.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/ContactsSyncManager.kt)

1.  **Refactor `getLocalContactEtags` to `getLocalContactMeta`**:
    -   Instead of just ETags, retrieve `SOURCE_ID`, `SYNC1` (ETag), and `DIRTY` status.
    -   Filter by `DELETED=0` to identify currently active local contacts.
2.  **Fix `updateLocalContactMeta`**:
    -   Update the `DELETED` column explicitly using `put(ContactsContract.RawContacts.DELETED, if (deleted) 1 else 0)`.
    -   This ensures that when a contact is being updated from the server, any existing `DELETED=1` flag is cleared.
3.  **Add `getLocalDirtyOrDeletedContactFileNames()` helper**:
    -   This will find all `RawContacts` for the account that have `DELETED=1` or `DIRTY=1`.
4.  **Update `syncWithPropfind()`**:
    -   Use `getLocalContactMeta()` to identify contacts that are missing, have ETag mismatches, or are locally dirty.
    -   Ensure these are all added to the `toDownload` list.
5.  **Update `syncWithToken()`**:
    -   Supplement the server-reported changes with locally dirty or deleted contacts.
    -   This is necessary because the server sync-token report only includes server-side changes, missing local deviations that need to be reverted.

## Verification Plan

### Manual Verification
1.  **Sync Back Deleted Contacts**:
    -   Perform a successful sync.
    -   Delete one or more contacts using the phone's Contacts app.
    -   Trigger a sync from the AliPafContacts app.
    -   Verify that the deleted contacts are restored to the phone.
2.  **Revert Local Edits**:
    -   Perform a successful sync.
    -   Edit a contact's phone number or name on the phone.
    -   Trigger a sync.
    -   Verify that the local changes are overwritten by the server version.
3.  **Normal Sync**:
    -   Perform a sync where nothing has changed on either side.
    -   Verify that no contacts are re-downloaded (check logs for "Downloading 0 contacts").
