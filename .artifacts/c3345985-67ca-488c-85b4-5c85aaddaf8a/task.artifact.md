# Tasks - Fix Sync Back of Locally Deleted Contacts

- `[x]` Update `ContactsSyncManager.kt`
    - `[x]` Refactor `getLocalContactEtags` to `getLocalContactMeta`
    - `[x]` Add `getLocalDirtyOrDeletedContactFileNames`
    - `[x]` Update `updateLocalContactMeta` to handle `DELETED` column correctly
    - `[x]` Update `syncWithPropfind` logic
    - `[x]` Update `syncWithToken` logic
- `[x]` Verify changes
    - `[x]` Compile and check for errors
    - `[x]` Manual verification (logic walkthrough)
