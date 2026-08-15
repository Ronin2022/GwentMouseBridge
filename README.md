# Gwent Mouse Bridge

Experimental Android mouse-to-touch bridge for **GWENT: The Witcher Card Game** (`com.cdprojektred.gwent`).

The target device sees the physical mouse at the Linux/Android input layer, but GWENT hides the system cursor and does not treat mouse clicks as touch input. This prototype keeps its own virtual cursor and injects touch gestures only while GWENT is foreground.

## MVP behavior

- Shizuku UserService runs with shell identity and reads numeric Linux `input_event` records
  directly from the dynamically discovered mouse node.
- While GWENT is foreground, the reader uses `EVIOCGRAB` on that open node so Android 12
  cannot cancel an injected touch drag with a simultaneous hardware mouse hover event.
  The grab is released on foreground loss, bridge disable, disconnect, binder death, and shutdown.
- `REL_X` / `REL_Y` are accumulated until `SYN_REPORT`, then update the virtual cursor once per Linux input frame.
- A non-touchable Accessibility overlay makes that virtual cursor visible while GWENT is foreground.
- Left-button press arms an interaction. Releasing without motion dispatches one tap.
- The first mouse-motion frame while held starts a touch stroke at the original press point,
  and later frames extend that same stroke using `StrokeDescription.continueStroke()`.
- Left-button release ends the touch stroke.
- No gesture is intentionally injected outside `com.cdprojektred.gwent`.
- Foreground verification follows the input-focused GWENT accessibility window, so a
  non-focusable heads-up notification does not latch the bridge off. A focused notification
  shade or another application still disables capture and injection immediately.
- No root, internet permission, analytics, right-click mapping, or scroll mapping.
- Input capture fails closed unless Shizuku reports the non-root shell identity (UID 2000).
- The left button is recognized by canonical numeric code `0x110`; textual `BTN_MOUSE` and `BTN_LEFT` aliases are both accepted.

## First-device assumptions

- Android 12 (API 31)
- Huawei tablet
- Preferred input device name: `HUAWEI Mouse CD26 SE Mouse`

The privileged reader discovers the corresponding `/dev/input/event*` node from a fresh
`/proc/bus/input/devices` snapshot, with `getevent -pl` as a shell-side inventory fallback,
whenever capture starts or a disconnected reader retries; it does not hard-code `event14`.

## Setup on the tablet

1. Install the debug APK from the latest GitHub Actions artifact.
2. Start Shizuku.
3. Open **Gwent Mouse Bridge** and grant Shizuku permission.
4. Open Android Accessibility settings and enable **Gwent Mouse Bridge**.
5. Turn on **Enable bridge** in the app.
6. Launch GWENT. A small crosshair should appear at the bridge's virtual pointer location.
7. Tune pointer sensitivity from the app if raw `REL_X/REL_Y` motion feels too slow or too fast.

## Build

The repository includes the Gradle 8.9 Wrapper. The `Build debug APK` GitHub Actions workflow
installs JDK 17, Android SDK 35, NDK, and CMake; runs unit tests plus `assembleDebug`; and uploads
`app-debug.apk` as `GwentMouseBridge-debug`.

## Safety notes

This is an early prototype. The Accessibility service has gesture-injection capability by design.
Its code gates both gesture injection and exclusive mouse capture on GWENT foreground state,
Shizuku shell availability, and the user's explicit bridge switch. Review the source before
enabling the service.

Not affiliated with CD PROJEKT RED, Huawei, Android, or Shizuku.
