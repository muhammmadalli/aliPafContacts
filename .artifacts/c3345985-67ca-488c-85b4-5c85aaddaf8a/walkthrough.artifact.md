# Walkthrough - Restoring Locally Deleted Contacts

I have implemented the logic to ensure that contacts deleted or modified locally on the phone are correctly synced back from the server. This reinforces the "server-to-client" sync direction.

## Changes Made

### Contacts Sync Logic
#### [ContactsSyncManager.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/ContactsSyncManager.kt)

- **Enhanced Local State Tracking**: Refactored the local contact metadata retrieval to include the `DIRTY` status and correctly identify contacts marked for deletion (`DELETED=1`).
- **Enforced Server State in Token Sync**: Updated `syncWithToken` to check for local deviations (edits or deletions) and include those contacts in the download queue, even if the server doesn't report them as changed. This was the primary reason why locally deleted contacts weren't being restored.
- **Improved Propfind Sync**: Updated `syncWithPropfind` to also re-download contacts that are locally dirty.
- **Fixed Metadata Updates**: Modified `updateLocalContactMeta` to explicitly clear the `DELETED` flag when updating a contact from the server. This ensures that a restored contact is actually visible in the phone's contact list.

## Verification Results

### Automated Tests
- Ran `./gradlew app:assembleDebug` to verify that the changes compile correctly.

### Manual Verification (Logic Check)
1. **Local Deletion**: When a user deletes a contact, Android sets `DELETED=1`. My new `getLocalDirtyOrDeletedContactFileNames()` picks this up. `syncWithToken` then adds it to the download list, fetching the VCard from the server. `applyContactToProvider` finds the existing record (even if deleted) and updates it, while `updateLocalContactMeta` sets `DELETED=0`, making it visible again.
2. **Local Edit**: When a user edits a contact, Android sets `DIRTY=1`. My logic detects this and forces a re-download from the server, overwriting the local changes.
3. **Server Change**: Standard server-side changes (ETag mismatches) continue to be handled correctly by both sync methods.
