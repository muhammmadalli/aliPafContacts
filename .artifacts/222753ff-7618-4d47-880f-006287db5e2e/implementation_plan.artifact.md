# Sync Toolbar appearance between MainActivity and AppInfoActivity

The toolbar in `MainActivity` (`activity_main.xml`) is currently transparent and uses the basic `androidx.appcompat.widget.Toolbar`, which prevents it from correctly responding to light and dark theme changes as seen in `AppInfoActivity`.

## Proposed Changes

### UI Layouts

#### [MODIFY] [activity_main.xml](file:///D:/programDo/codex/aliPafContacts/app/src/main/res/layout/activity_main.xml)
- Remove `android:background="@android:color/transparent"` from `AppBarLayout`.
- Remove `app:elevation="0dp"` from `AppBarLayout`.
- Change `androidx.appcompat.widget.Toolbar` to `com.google.android.material.appbar.MaterialToolbar`.
- Keep the `ImageButton` for app info inside the toolbar.

## Verification Plan

### Manual Verification
- Deploy the app to a device or emulator.
- Verify `MainActivity` toolbar color in Light Mode (should be blue).
- Verify `MainActivity` toolbar color in Dark Mode (should be blackish/dark gray).
- Ensure the "i" info button still works and is correctly positioned.
