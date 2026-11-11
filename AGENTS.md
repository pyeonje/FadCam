# Developer & Agent Guide: Best Practices for Workflow Orchestration and Task Management

## Personal fork overrides

- The default debug application ID is `com.pyeonje.fadcam.beta`; the Java namespace remains `com.fadcam`. Use this package instead of the upstream debug package in the device examples below.
- Setup launcher: `com.pyeonje.fadcam.beta/com.fadcam.SplashActivity`.
- Saved-settings recording launcher: `com.pyeonje.fadcam.beta/com.fadcam.PersonalRecordingStartActivity`.
- Install with `:app:installDefaultDebug`, selecting the intended phone with `ANDROID_SERIAL`. `build-personal.ps1` provides the workstation's toolchain configuration.
- All phone installations must target the main user only (`--user 0`). The user explicitly rejected installing a duplicate in Samsung's `DUAL_APP` profile (user 95). Keep the Gradle `installation.installOptions("--user", "0")` restriction and verify profile installation state after each install. Preserve the setup and recording launcher entries in the main profile.
- Initial fork installation was verified without launching the app or recording. Start a recording only when the current task authorizes a recording session; installation-only verification checks the package and launcher registration.
- Use the patched Media3 revision in `personal-build.lock.json`; local SDK and patched checkout paths belong in ignored `local.properties`.

## Workflow Orchestration

### 1. Plan Node Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
-Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness
  
### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

#### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management
1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles

### 7. Device Test Playbook — record, pull, verify (FadCam)

Do not install or launch an app, connect test hardware, or record unless the current
task specifically authorizes that action. Use dedicated QA storage and non-user
test content.

**Install (when authorized):** select the intended phone with `ANDROID_SERIAL`.
Use `gradlew.bat :app:installDefaultDebug` on Windows (or `./gradlew` on other
hosts). Keep the Gradle install restriction `--user 0` and verify the debug
package for Android user 0. Do not install in other profiles, including Samsung
Dual App (user 95). Debug package: `com.pyeonje.fadcam.beta`; component names
use the Java namespace `com.fadcam`. Never record using the stable package.

**Record (when separately authorized):**

1. Generate a unique QA session ID (for example a UUID). Create and select a
   dedicated empty SAF tree for that ID. Record the previous storage setting so
   it can be restored. Note the phone serial, user 0, test case, and selected tree URI.
2. Use the saved-settings launcher
   `com.pyeonje.fadcam.beta/com.fadcam.PersonalRecordingStartActivity` or
   the debug app UI, then stop that recording with the app UI or its debug stop
   component `com.pyeonje.fadcam.beta/com.fadcam.RecordingStopActivity`.
3. Record the exact output URI for each new segment or photo using the app or
   selected document provider. Confirm it belongs to the recorded QA tree and
   session. Record the exact file path too if the provider exposes it. If the
   output cannot be uniquely identified, stop without copying or deleting it.

**Copy and verify:** export only the recorded URI through the document provider,
or use `adb -s <SERIAL> pull "<EXACT_QA_FILE_PATH>" "<LOCAL_QA_SESSION_DIR>"`
when that exact path is known. Keep device originals after pull. On the local
copy:

1. Run `ffmpeg -v error -i file.mp4 -f null -`; check that decoding exits 0.
2. Run `ffprobe -v error -show_streams -show_format file.mp4`; verify the expected
   audio policy, duration and start times. For A/V QA use a visible and audible
   reference event and compare the measured delay in both track start orders.
3. Box-walk the appended `moov`: the `stsz` entries must match concatenated
   `trun` sample sizes in each track. Relevant `trun` flags: `0x001` data offset,
   `0x100` duration, `0x200` size, `0x400` flags; values are interleaved per sample.

**Cleanup:** never delete existing device media. If device cleanup is requested,
obtain separate approval and act on only the exact recorded QA output URI (or
verified exact QA path). Do not select targets by timestamps, wildcards, or
recursive directory traversal. Leave the output in place if identity is uncertain.
Restore the previous storage setting if the approved QA procedure changed it.

## Core Principles (legacy)
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Backward Compat**: Never add backward-compatible overloads, fallbacks, or polyfills to support outdated code. When frontend/backend/Android contract changes (method signatures, API shapes), always ask the user before silently adding compat shims. Clean deploys preferred over layered hacks.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

---
