# Fix Card Backgrounds for Dark Theme

The `MaterialCardView` elements in `activity_main.xml` and `item_account.xml` have hardcoded background colors (`#CCFFFFFF`), which prevents them from adapting to the system dark theme. This plan introduces theme-aware color resources to fix this.

## Proposed Changes

### [Resources]

#### [MODIFY] [colors.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/values/colors.xml)
Add a new color resource `card_surface_background` with the current light theme value.

#### [NEW] [colors.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/values-night/colors.xml)
Create a dark theme version of `card_surface_background` using a semi-transparent dark grey (e.g., `#CC1E1E1E`).

---

### [Layouts]

#### [MODIFY] [activity_main.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/layout/activity_main.xml)
Update `main_instructions_card` and `sync_status_card` to use `@color/card_surface_background` instead of the hardcoded hex value.

#### [MODIFY] [item_account.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/layout/item_account.xml)
Update the root `MaterialCardView` to use `@color/card_surface_background`.

## Verification Plan

### Automated Tests
- Not applicable for this UI change, but I will verify that the project still builds.

### Manual Verification
1.  Deploy the app to a device/emulator.
2.  Switch the system theme to **Dark Mode**.
3.  Verify that `item_account`, `main_instructions_card`, and `sync_status_card` now have a dark background.
4.  Switch back to **Light Mode** and verify they are still light white.
