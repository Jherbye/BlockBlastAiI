# Development status

## Latest: theme-independent recognition (adaptive)
- Implemented: `vision/adaptive` (grid geometry, theme-free cell analysis, learned theme model, tray reader, temporal fusion,
  confidence gating), integrated into `AssistantService` (replaces `RecognitionPipeline`), correction/confirmation editor
  (`ReviewView`, "Fix", "Reset theme"), snapshot fields for status/unsure cells. See `docs/RECOGNITION.md`.
- Tested (JVM, standalone kotlinc + JUnit): 52 core tests pass; synthetic robustness suite in `docs/ROBUSTNESS_REPORT.md`.
- **Not validated on real gameplay screenshots. `:app` not compiled in this environment. Not run on a device.**
- The "Recognition sensitivity" setting now means strictness (confidence threshold 0.45-0.65; default equals the tested 0.55).

## Earlier stage
Stages D-F implemented as a first functional version (app module, capture, recognition, overlay, solver integration, APK CI).

## Real CI results so far (from logs pasted by the user)
- `core-validation`, `:core:test`: **Kotlin compiled** (core main + tests incl. the vision package). **35 tests ran: 33 passed, 2 failed.**
  All Stage B rules/solver tests passed. Failures (both `VisionTest`):
  - `pipelineEndToEndFeedsTheSolver` (VisionTest.kt:87) and `trackerNeedsConsecutiveIdenticalReadings` (:113):
    `RecognitionResult.ok` was false on a clean synthetic frame.
  - Root cause: `PieceExtractor` chose the tray cell size closest to the 0.6 prior instead of the best fit, and scaled the
    fit confidence too harshly (fit error 0.079 -> confidence 0.47, below the 0.5 acceptance threshold). My Python
    mirror had omitted that confidence term, so it did not catch this before CI.
  - Fix (in this revision, NOT yet re-run in CI): choose the minimum-error cell size, break near-ties with the prior, and
    scale fit confidence by 2x the fit limit. Mirror now includes the term: pipeline confidence 0.88 / 0.96, ok=true.
- **Real device observation:** the user installed the app and sent a screenshot of Block Blast with the overlay panel
  showing "Cannot read the screen reliably - board lattice confidence 0.17 too low". So the APK builds/installs/runs,
  MediaProjection capture and the floating panel work on a real phone, and recognition failed in the locator. Diagnosed
  and fixed (valley-based locator, see docs/RECOGNITION.md); the fix is NOT yet rebuilt or re-tested on the phone.
- `build-apk` first run: stopped at the "Set up Android SDK" step (the step's own log was not seen). The workflow was
  changed (ubuntu-24.04, preinstalled SDK + license acceptance, setup-java v5). **The `:app` module has not been
  compiled yet; APK status unknown.**
- Test logging now prints failure messages in the CI console.

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
- `:core` compiles (CI). `:app` compile: unknown. AGP 8.7.3 and the runner's Android SDK are assumptions.
- Vision accuracy on the real game: unknown (synthetic frames only). Expect to need calibration and threshold tuning.
- Adaptive recogniser: one known low-contrast piece-column failure (report), on-device speed/memory unmeasured, editor UX untested on a phone.
- Overlay/alignment on real devices, cutouts, and rotation: untested. Capture size uses real display metrics.
- Hiding the overlay for each scan causes a brief flicker of the panel (every few seconds while unverified).
- Dragging a piece may briefly verify a transient state; it is corrected after the drop.
- Scoring and exact line-clear rules remain unverified assumptions; heuristic scores are search guides only.
- Notification/FGS-type permission flows on Android 14+/15 untested.

## Next steps
1. Push this revision; read the `core-validation` and `build-apk` runs; record every compile error/test failure here; fix without weakening tests.
2. Install the debug APK; report what the diagnostics show on a real Block Blast screen (and screenshots you are happy to share).
3. Tune recognition on real data; then consider Stage C solver improvements.
4. Authorize versioned GitHub Releases when ready.
