# Walkthrough - Add Sync Icon to Instructions

I have added the sync button icon (`@drawable/syncbtn2`) inline within the instructions text on the main activity.

## Changes Made

### [MainActivity](file:///D:/programDo/codex/aliPafContacts/app/src/main/java/ali/paf/contacts/ui/MainActivity.kt)

- Added `setupInstructionsWithIcon()` method to programmatically insert the icon into the `TextView`.
- Used `SpannableStringBuilder` and `ImageSpan` to position the icon after the phrase "2. Pressing Sync Button".
- Scaled the icon to 120% of the text size for visibility and added a preceding space for better spacing.

## Verification Results

### Manual Verification
- Deployed to device and verified the UI.
- The icon appears correctly aligned with the text in the instructions card.
