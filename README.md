# Thunder LINE Speaker

Android app for vivo V30 Pro that reads visible LINE notification text containing Thunder and a labeled amount, then speaks the amount in Thai.

## Build APK
GitHub Actions builds a debug APK automatically after pushes to `main`. Open the Actions tab, select **Build Android APK**, open the latest successful run, and download `thunder-line-speaker-debug-apk` from Artifacts.

## Install
Install the APK, open the app, and grant Notification Access. Ensure LINE notification previews and Thai text-to-speech are enabled. Android battery management may affect background operation.

## Limitations
This version cannot read text inside image-only LINE notifications, does not modify the LINE webhook, and does not send data to a server. Notification content must contain the word Thunder and an amount label. Build and device behavior must be tested.
