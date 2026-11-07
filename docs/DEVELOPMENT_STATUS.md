# Development status

## Current stage
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

## Latest round: confidence, persistent panel, Autoplay
IMPLEMENTED (code written; none of it compiled or run by the author):
- Recognition: first-significant-gap decision line, structural check (complete line impossible), per-stage confidence
  (grid / board / each piece), `weakCells`, `uncertainSlots`, `summary`; board acceptance 0.9; empty-board-at-start rule.
- Panel: one persistent compact panel (never recreated, only re-rendered when `PanelState` changes, sticky message with a
  1.5 s cooldown, 44 dp buttons, draggable, position remembered per session, collapse button, hidden during a scan only if it
  covers the board/tray); Recognition Good/Uncertain/Needs correction; Mode chip; Start/Pause/Resume; Prev/Next; Undo;
  Hide; Cal; Auto; Fix (highlights the uncertain piece); Scan; E-STOP; Quit.
- Autoplay: see docs/AUTOPLAY.md. `AutoplayPlanner` (+ unit tests) is pure Kotlin.
VALIDATED: board/pieces/confidence on three real screenshots in the Python mirror only; calibration on synthetic themes
(Python mirror). NOT validated: any of the Kotlin or Android code, the panel on a phone, any Autoplay gesture.

## Real-device feedback log
- Build 1: "lattice confidence 0.17 too low" on the chocolate theme -> gap-based locator.
- CI compile failure: stale `CellClassifier.kt` left in the repo (copying a zip over a clone never deletes files; use
  `rsync -a --delete`). After removing it the app built and ran on the phone (the Fix button is visible in the user's screenshot).
- Build 2: lavender/magenta theme read the board correctly but lost the tray once pieces covered > 50% of a row
  ("no pieces found in the tray band") -> multi-background piece reader (see docs/RECOGNITION.md). Not yet re-tested on the phone.

## Theme-independent recognition (latest work)
Requirement: the vision must not depend on fixed colours, textures or themes. Implemented: gap-geometry locator (both
polarities, normalised), empty-look cell reader with multi-cue orientation and learning, background-relative tray reader
with empty-slot cross-check, per-cell/piece/geometry confidence, strict acceptance rule, manual calibration (**Cal**),
manual correction with theme learning (**Fix**), synthetic theme generator and robustness test with a CI-uploaded report.
See `docs/RECOGNITION.md` for the implemented / validated split. The Kotlin port and the app changes are NOT yet compiled.

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
- Overlay/alignment on real devices, cutouts, and rotation: untested. Capture size uses real display metrics.
- Hiding the overlay for each scan causes a brief flicker of the panel (every few seconds while unverified).
- Dragging a piece may briefly verify a transient state; it is corrected after the drop.
- Scoring and exact line-clear rules remain unverified assumptions; heuristic scores are search guides only.
- Notification/FGS-type permission flows on Android 14+/15 untested.

## Next steps
00. Push; read `build-apk` (compile, :core:test incl. ThemeRobustnessTest report, APK). Then test Assist on the phone, then Autoplay cautiously.
0. Push, read the CI run (core tests incl. `ThemeRobustnessTest`, then `:app:assembleDebug`); fix real compile/test failures.
1. Push this revision; read the `core-validation` and `build-apk` runs; record every compile error/test failure here; fix without weakening tests.
2. Install the debug APK; report what the diagnostics show on a real Block Blast screen (and screenshots you are happy to share).
3. Tune recognition on real data; then consider Stage C solver improvements.
4. Authorize versioned GitHub Releases when ready.
