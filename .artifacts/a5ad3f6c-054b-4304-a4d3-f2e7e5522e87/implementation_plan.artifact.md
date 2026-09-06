# Implementation Plan - Map CATEGORIES to Contact Groups

The user reported that contacts with the "CATEGORIES" field are not being put into separate groups in the phone's contacts app. This plan describes how to implement automatic mapping of VCard categories to Android contact groups during synchronization.

## User Review Required

> [!IMPORTANT]
> The app will create new contact groups in the Android Contacts app corresponding to the `CATEGORIES` found in the synced VCards. If a contact has multiple categories, it will be added to multiple groups.

> [!NOTE]
> Group membership management will be "authoritative" from the server: if a category is removed from a contact on the server, the contact will be removed from the corresponding group on the phone (but only for groups managed by this app's account).

## Proposed Changes

### Sync Component

#### [MODIFY] [ContactsSyncManager.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/sync/ContactsSyncManager.kt)

- Add group caching mechanism to avoid redundant queries.
- Implement `loadAccountGroups()` to initialize the cache at the start of synchronization.
- Implement `getOrCreateGroup(title: String)` to manage group lifecycle in `ContactsContract.Groups`.
- Implement `applyContactGroups(rawContactId: Long, categories: List<String>)` to manage `GroupMembership` data rows.
- Hook group application into the contact processing flow in `applyContactToProvider`.

## Verification Plan

### Manual Verification
1. Prepare a VCard with `CATEGORIES:Work,Friends` on the server.
2. Trigger a sync in the app.
3. Open the phone's Contacts app and check if "Work" and "Friends" groups were created.
4. Verify that the contact is a member of both groups.
5. Modify the VCard on the server to `CATEGORIES:Work`.
6. Trigger a sync again.
7. Verify that the contact is no longer in the "Friends" group but remains in the "Work" group.
