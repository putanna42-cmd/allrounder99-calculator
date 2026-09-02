# FP24 Panel Viewer

A personal-use Android WebView wrapper for `https://panel.freeplay24.com/login`.

## Included

- Panel login remains inside Android WebView cookies.
- Navigation is restricted to `panel.freeplay24.com`; external HTTPS links open in the phone browser.
- Cleartext HTTP and invalid SSL pages are blocked.
- The app listens for the panel's exact `.DepositAdded` and `.WithdrawAdded` Laravel Echo events.
- Two private background WebViews keep the Deposit and Withdraw event pages active after login.
- Automatic reconnect, page health checks, and request-list snapshots recover events missed during brief network interruptions.
- The always-on special-use foreground service is not subject to Android 15's six-hour `dataSync` timeout.
- Request alerts use a public, high-priority lock-screen notification channel.
- A native-injected black theme is applied to every panel page.
- Pull down from the top of the panel to refresh; there is no extra app toolbar or refresh button.
- Notifications intentionally omit usernames, amounts, account numbers, and UTR values.
- A one-time “notifications active” message confirms Android notification permission.

## Important limitation

The panel does not expose an official push API or Firebase configuration to this app. A low-priority foreground-service notification keeps live monitoring active after login. Transaction alerts are still not guaranteed after force-stop, session expiry, Android terminating the service, or a panel-side event change.

Reliable always-on background alerts require an official FreePlay24 webhook/API connected to a push backend such as Firebase Cloud Messaging.

## Privacy

No usernames or passwords are stored in this source code. Do not add credentials, cookies, API keys, or exported customer data to the project or its Git history.

## Build

Open this folder in Android Studio and build the `debug` APK, or use the included GitHub Actions workflow. The generated debug APK is installable for personal testing and is not intended for Play Store distribution.
