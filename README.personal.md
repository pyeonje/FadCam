# Personal FadCam fork

The personal package is `com.pyeonje.fadcam.beta` for the default debug build. The Java namespace stays `com.fadcam`.

Launcher entries:

- **FadCam Personal**: setup, recording settings, files, and shortcut customization.
- **촬영 시작**: starts using the saved camera selection and recording settings after onboarding and camera/microphone permissions are complete. Missing preparation opens setup instead.

Upstream shortcut customization remains available: rename an action, choose an icon image, and pin it to the home screen. The installed app drawer entry has a fixed packaged label/icon; arbitrary runtime name/image changes apply to pinned home screen shortcuts.

## Build on this workstation

`build-personal.ps1 -Action Build` builds the default debug variant. `build-personal.ps1 -Action Install -DeviceSerial <ADB_SERIAL>` installs through Gradle. The script uses the local Android toolchain prepared for this fork.

`local.properties` (ignored) supplies the SDK directory and the sibling `FadCam-media3-patched` checkout. The required source revision is recorded in `personal-build.lock.json`. The modified Media3 build is essential to preserve FadCam's hybrid MP4 finalization behavior; do not silently replace it with unmodified Maven Media3.

Installation does not grant runtime permissions or start a recording. Recording reliability and the applied video settings must be checked in an intentional later recording session.
## Initial installation verification — 2026-09-30

- Default debug build and Gradle installation completed successfully on SM-S928N, Android 16 / API 36.
- Installed version: `4.0.0-beta10.6`, version code `52`.
- Package manager reports both launcher entries and `installed=true`.
- The app remains `stopped=true`, `notLaunched=true`; camera and microphone permissions remain ungranted.
- Source review, compilation, packaged resource metadata, and `git diff --check` passed. Recording execution and video validity have not been tested.
