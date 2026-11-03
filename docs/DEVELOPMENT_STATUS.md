# Development status

## Current stage
Stages D-F implemented as a first functional version (app module, capture, recognition, overlay, solver integration, APK CI).
**Nothing in this repository has been compiled or run by the author** (no Kotlin/Android toolchain in the authoring
sandbox). The user reported the earlier Stage B `core-validation` CI run "worked"; this was not independently seen.
The first CI run of `build-apk` will be the first compile of the Android module and the new vision code.

## What exists
- `:core` (pure Kotlin, JVM 17 target): Board/Piece/RulesEngine, BaselineSolver (unchanged), Recommender, and the vision
  package (see `docs/RECOGNITION.md`).
- `:app` (Android, minSdk 29, targetSdk/compileSdk 35, plain Views, kotlinx-coroutines only):
  - `MainActivity`: overlay permission, notification permission, MediaProjection consent, start/stop, settings
    (opacity, search time, sensitivity, follow-up numbers), optional diagnostics (matrix, pieces, calibration,
    confidence, search time/nodes/budget, state hash, transition, errors).
  - `AssistantService` (foreground, type mediaProjection): capture loop, clean-scan / watch state machine, stale
    invalidation, baseline solver on a worker thread with cancellation, ranked alternatives, rotation handling
    (resize the existing VirtualDisplay), pause/resume, recalculation.
  - `ScreenCapturer`: ImageReader -> in-memory ARGB frames, never saved.
  - `OverlayController`: full-screen NOT_TOUCHABLE highlight window (cell fills, outlines, numbered follow-ups),
    draggable/collapsible control panel (Prev, Next, Redo, Pause, Hide, Cal, Auto, Stop), manual calibration view.
- CI: `core-validation.yml` (core tests) and `build-apk.yml` (core tests + `:app:assembleDebug`, uploads the debug APK
  as artifact `block-blast-assistant-debug-apk`). No release publishing; that needs the owner's authorization.

## Safety boundary (unchanged)
No gestures, taps, drags, Accessibility Service, ADB or input injection anywhere. The panel only receives touches meant
for its own buttons. Verified by inspection only: the manifest declares no accessibility service.

## Not verified / known risks
- Compiles: unknown. Gradle/AGP/Kotlin versions (8.10.2 / 8.7.3 / 2.0.21) and the GitHub runner's Android SDK are assumptions.
- Vision accuracy on the real game: unknown (synthetic frames only). Expect to need calibration and threshold tuning.
- Overlay/alignment on real devices, cutouts, and rotation: untested. Capture size uses real display metrics.
- Hiding the overlay for each scan causes a brief flicker of the panel (every few seconds while unverified).
- Dragging a piece may briefly verify a transient state; it is corrected after the drop.
- Scoring and exact line-clear rules remain unverified assumptions; heuristic scores are search guides only.
- Notification/FGS-type permission flows on Android 14+/15 untested.

## Next steps
1. Authorize a push; read the `build-apk` run; record every compile error/test failure here; fix without weakening tests.
2. Install the debug APK; report what the diagnostics show on a real Block Blast screen (and screenshots you are happy to share).
3. Tune recognition on real data; then consider Stage C solver improvements.
4. Authorize versioned GitHub Releases when ready.
