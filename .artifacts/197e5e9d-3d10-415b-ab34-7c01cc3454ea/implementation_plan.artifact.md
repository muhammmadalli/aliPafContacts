# Add Sync Icon to Main Instructions

The user wants to add the `@drawable/syncbtn2` icon after the text "2. Pressing Sync Button" in the `main_instructions_text` TextView on the main screen. Since Android XML does not support inline images in `TextView`, this will be implemented using a `SpannableString` in `MainActivity.kt`.

## Proposed Changes

### [MainActivity](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)

#### [MODIFY] [MainActivity.kt](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)
- Update `onCreate` to set the instructions text using a `SpannableStringBuilder`.
- Use `Html.fromHtml` to preserve the existing bolding in the string resource.
- Find the specific text "2. Pressing Sync Button" and insert the `@drawable/syncbtn2` as an `ImageSpan`.
- Adjust the icon size to match the text height.

## Verification Plan

### Manual Verification
- Deploy the app to a device/emulator.
- Verify that the instructions card on the main screen shows the sync button icon immediately after the words "2. Pressing Sync Button".
- Ensure the icon is properly aligned with the text and does not disrupt the layout.
