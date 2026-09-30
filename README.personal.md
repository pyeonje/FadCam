# Personal FadCam fork

The personal package is `com.pyeonje.fadcam.beta` for the default debug build. The Java namespace stays `com.fadcam`.

Launcher entries:

- **FadCam** (blue calendar): recording with a live preview, settings, files, and shortcut customization.
- **바로촬영/중지** (orange calendar with a green dot): starts using saved camera/recording settings, or stops the active session, without showing recording UI. Missing preparation opens setup instead.

Upstream shortcut customization remains available: rename an action, choose an icon image, and pin it to the home screen. The installed app drawer entry has a fixed packaged label/icon; arbitrary runtime name/image changes apply to pinned home screen shortcuts.

## Build on this workstation

`build-personal.ps1 -Action Build` builds the default debug variant. `build-personal.ps1 -Action Install -DeviceSerial <ADB_SERIAL>` installs through Gradle. The script uses the local Android toolchain prepared for this fork.

Gradle installation is restricted to the main phone profile (`--user 0`). Do not install into Samsung's Dual Messenger / `DUAL_APP` profile. The unintended initial copy in user 95 was removed; both launcher entries belong to the single app in the main profile.

`local.properties` (ignored) supplies the SDK directory and the sibling `FadCam-media3-patched` checkout. The required source revision is recorded in `personal-build.lock.json`. The modified Media3 build is essential to preserve FadCam's hybrid MP4 finalization behavior; do not silently replace it with unmodified Maven Media3.

Installation does not grant runtime permissions or start a recording. Intentional recording validation results are recorded below.
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

## Live preview and calendar controls — 2026-09-30

The recording tab attaches a TextureView to the existing recording pipeline while foreground recording is active, including when opening the main app during a quick-start session. Leaving the screen detaches the preview without stopping recording. The quick launcher uses a separate task affinity so Samsung One UI executes its action rather than bringing the existing main task to the front. It remains a launcher entry of the same APK, not a second installed package. Its home-screen icon was placed in the empty first cell of the second app row on the existing middle home page.

Main/quick adaptive and monochrome icons use distinct calendar designs. Default pinned start/stop/toggle icons also use the calendar family; custom shortcut images and labels retain priority.

Restoring preview exposed an upstream camera/surface readiness race: both callbacks could queue pipeline startup before the capture session changed STARTING to IN_PROGRESS. RecordingService now claims startup once per accepted session with AtomicBoolean. The focused regression check in `tools/verify_start_dispatch.ps1` exercises the production readiness method with repeated/concurrent callbacks, delayed session configuration, stopping, and a subsequent session.

Device validation on SM-S928N / Android 16: Gradle installed only in user 0, with user 95 absent; all 13 native libraries passed 16KB alignment. Main-button recording displayed the live camera image. Actual home-icon taps started and stopped capture while keeping the launcher visible; opening the main app during that session displayed preview, and returning home detached it while capture continued. Disposable recordings used the saved rear camera, 4K portrait output (2160×3840), target 30 FPS, and audio settings. FFmpeg decode passed, ffprobe reported positive durations, and finalized moov stsz matched embedded moof trun sample sizes for both tracks with zero mismatches. Hybrid finalization embeds the original fragments inside the outer mdat; `tools/verify_recording_mp4.py` handles that layout. Short tests do not establish long-duration or screen-off reliability.

## Shared recording feedback and appearance — 2026-09-30

Main and quick actions control the same live service session, including startup and pause. Toggle reads actual volatile service state, not persisted recovery flags. After process death, stale flags are cleared before starting. Main UI re-queries that service on return and receives package-scoped stop events. A successful recording start emits one 120ms pulse; ending that session emits two pulses (100ms, 80ms gap, 180ms). Feedback belongs to the service and is deduplicated across repeated callbacks and cleanup; state queries and failed/cancelled startup remain silent. Recording confirmations are independent of the older touch-feedback switch.

Settings → App name and icon edits the main and quick home-screen entries separately, including arbitrary gallery images and names, reset/default icons, existing pinned updates, and explicit first pin. Installed app-drawer labels/icons remain packaged defaults. The current ordinary home app icon is not automatically replaced by a custom shortcut; the screen explains adding the custom icon and removing the old home entry if desired. Images are sampled to at most 1024px before writing the 432px icon. Start/stop-only shortcut editing remains available from this page.

Notification settings show the actual system permission and link directly to Android app notification settings. Camera/audio setup and playback no longer request notification permission automatically. Final installation with the explicit HideNotifications switch revokes POST_NOTIFICATIONS only for user0 and verifies denial. The required foreground-service registration remains intact, so notification denial does not prevent capture; Android's running-app and camera/microphone indicators remain system-controlled. See [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

Local validation: all11 JUnit/Robolectric tests passed (4 haptic lifecycle, 4 live-session toggle, 3 pinned name/icon metadata and update tests), the production pipeline-start race regression passed, and the arm64 APK/native alignment build passed. Per the owner's timing restriction, wireless debugging is reserved for final installation; this update does not perform device recording/UI/vibration tests.


Final installation completed on SM-S928N / Android16 with Gradle. User0 only, no secondary profile package; POST_NOTIFICATIONS verified granted=false in the user0 block. No device recording, screen control, or haptic test was performed during this update.

