# Gwent Mouse Bridge

Experimental Android mouse-to-touch bridge for **GWENT: The Witcher Card Game** (`com.cdprojektred.gwent`).

The target device sees the physical mouse at the Linux/Android input layer, but GWENT hides the system cursor and does not treat mouse clicks as touch input. This prototype keeps its own virtual cursor and injects touch gestures only while GWENT is foreground.

## MVP behavior

- Shizuku UserService runs with shell identity and reads the physical mouse event stream using `/system/bin/getevent`.
- `REL_X` / `REL_Y` update an internal virtual cursor.
- A non-touchable Accessibility overlay makes that virtual cursor visible while GWENT is foreground.
- Left-button press starts a touch stroke.
- Mouse motion while held extends that same stroke using `StrokeDescription.continueStroke()`, providing drag behavior for cards.
- Left-button release ends the touch stroke.
- No gesture is intentionally injected outside `com.cdprojektred.gwent`.
- No root, internet permission, analytics, right-click mapping, or scroll mapping.
- Input capture fails closed unless Shizuku reports the non-root shell identity (UID 2000).

## First-device assumptions

- Android 12 (API 31)
- Huawei tablet
- Preferred input device name: `HUAWEI Mouse CD26 SE Mouse`

The privileged reader discovers the corresponding `/dev/input/event*` node from `/proc/bus/input/devices`; it does not hard-code `event14`.

## Setup on the tablet

1. Install the debug APK from the latest GitHub Actions artifact.
2. Start Shizuku.
3. Open **Gwent Mouse Bridge** and grant Shizuku permission.
4. Open Android Accessibility settings and enable **Gwent Mouse Bridge**.
5. Turn on **Enable bridge** in the app.
6. Launch GWENT. A small crosshair should appear at the bridge's virtual pointer location.
7. Tune pointer sensitivity from the app if raw `REL_X/REL_Y` motion feels too slow or too fast.

## Build

The repository includes the Gradle 8.9 Wrapper. The `Build debug APK` GitHub Actions workflow installs JDK 17 and Android SDK 35, validates the wrapper, runs unit tests plus `assembleDebug`, and uploads `app-debug.apk` as `GwentMouseBridge-debug`.

## Safety notes

This is an early prototype. The Accessibility service has gesture-injection capability by design. Its code gates injections on GWENT foreground state and the user's explicit bridge switch. Review the source before enabling the service.

Not affiliated with CD PROJEKT RED, Huawei, Android, or Shizuku.
