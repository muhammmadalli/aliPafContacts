# Walkthrough - Toolbar Theme Sync

I have updated the toolbar in `MainActivity` to match the behavior of the toolbar in `AppInfoActivity`.

## Changes Made

### UI Layouts

#### [activity_main.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/layout/activity_main.xml)
- Replaced the generic `androidx.appcompat.widget.Toolbar` with `com.google.android.material.appbar.MaterialToolbar`.
- Removed manual `android:background="@android:color/transparent"` and `app:elevation="0dp"` from `AppBarLayout`. This allows the toolbar to use the theme's primary color (`@color/nextcloud_blue`) and default elevation, which automatically adapts to Light and Night modes.

## Verification Results

### Manual Verification Required
- [ ] Open the app in Light Mode and confirm the toolbar is blue.
- [ ] Switch the device/emulator to Night Mode and confirm the toolbar becomes dark.
