# FP24 Panel Viewer

A personal-use Android WebView wrapper for `https://panel.freeplay24.com/login`.

## Included

- Panel login remains inside Android WebView cookies.
- Navigation is restricted to `panel.freeplay24.com`; external HTTPS links open in the phone browser.
- Cleartext HTTP and invalid SSL pages are blocked.
- The app converts matching Laravel/toastr and new-row events into private Android notifications.
- Notifications intentionally omit usernames, amounts, account numbers, and UTR values.
- A **Bell** button sends a safe test notification; **Refresh** reloads the panel.

## Important limitation

The panel does not expose an official push API or Firebase configuration to this app. Alerts work while the WebView is active and may continue briefly while the app remains alive in Recent Apps. They are not guaranteed after force-close, phone restart, Android battery suspension, session expiry, or a panel-side UI/event change.

Reliable always-on background alerts require an official FreePlay24 webhook/API connected to a push backend such as Firebase Cloud Messaging.

## Privacy

No usernames or passwords are stored in this source code. Do not add credentials, cookies, API keys, or exported customer data to the project or its Git history.

## Build

Open this folder in Android Studio and build the `debug` APK, or use the included GitHub Actions workflow. The generated debug APK is installable for personal testing and is not intended for Play Store distribution.
