# Personal FadCam fork

The personal package is `com.pyeonje.fadcam.beta` for the default debug build. The Java namespace stays `com.fadcam`.

Launcher entries:

- **FadCam Personal**: setup, recording settings, files, and shortcut customization.
- **촬영 시작**: starts using the saved camera selection and recording settings after camera/microphone permissions are complete. Missing preparation opens setup instead.

Upstream shortcut customization remains available: rename an action, choose an icon image, and pin it to the home screen. The installed app drawer entry has a fixed packaged label/icon; arbitrary runtime name/image changes apply to pinned home screen shortcuts.

## Build on this workstation

`build-personal.ps1 -Action Build` builds the default debug variant. `build-personal.ps1 -Action Install -DeviceSerial <ADB_SERIAL>` installs through Gradle. The script uses the local Android toolchain prepared for this fork.

Gradle installation is restricted to the main phone profile (`--user 0`). Do not install into Samsung's Dual Messenger / `DUAL_APP` profile. The unintended initial copy in user 95 was removed; both launcher entries belong to the single app in the main profile.

`local.properties` (ignored) supplies the SDK directory and the sibling `FadCam-media3-patched` checkout. The required source revision is recorded in `personal-build.lock.json`. The modified Media3 build is essential to preserve FadCam's hybrid MP4 finalization behavior; do not silently replace it with unmodified Maven Media3.

Installation does not grant runtime permissions or start a recording. Recording reliability and the applied video settings must be checked in an intentional later recording session.
## Initial installation verification — 2026-09-30

- Default debug build and Gradle installation completed successfully on SM-S928N, Android 16 / API 36.
- Installed version: `4.0.0-beta10.6`, version code `52`.
- Package manager reports both launcher entries and `installed=true`.
- The app remains `stopped=true`, `notLaunched=true`; camera and microphone permissions remain ungranted.
- Source review, compilation, packaged resource metadata, and `git diff --check` passed. Recording execution and video validity have not been tested.

## Personal recording UI — 2026-09-30

The phone UI opens directly to a circular timer and a green start/stop button. Three tabs expose recording, saved videos, and focused settings. The settings entry points cover camera/resolution/FPS, audio, storage, shortcut name/image customization, notifications, and watermark. Korean resources cover these screens and common file operations (923 translated strings including the personal UI). The shortcut page lists only saved-settings start, stop, and toggle actions, preserving custom labels/images and existing pinned shortcuts. The previous branding delay and marketing onboarding no longer interrupt launch. Granting runtime permissions only prepares the app; another explicit button press starts recording.

Android 16 launch warning diagnosis: the shipped TensorFlow Task Vision native library had 4KB ELF LOAD alignment. The personal arm64 build removes the optional AI/motion detection model and dependencies, and records continuously after the user starts it. FFmpeg, OpenCV, Camera2/MediaCodec, and the patched Media3 muxer remain. Every built APK is checked for 16KB ELF and ZIP native alignment by `tools/check_native_alignment.py` before `build-personal.ps1` permits installation. This fork now builds only the arm64 APK for the owner's phone.

Recording lifecycle/state broadcasts are package-targeted so the private receiver receives them under Android 14+ intent restrictions. This fixes a new home screen remaining stuck at state synchronization. Swipes/DPAD traverse only the three visible tabs, and restored upstream home/settings fragments are replaced with personal screens.

Device validation: rebuilt and installed using Gradle on main user 0 only; DUAL_APP user 95 remains absent. Cold launch/relaunch shows the new Korean home without the compatibility dialog. Idle state is synchronized to NONE, with the start button enabled. Settings and shortcut screens were opened without starting capture. No recording was made; video output, actual FPS, and screen-off recording reliability are still not verified.
