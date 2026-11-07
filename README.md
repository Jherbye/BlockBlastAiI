# Block Blast Assistant (suggestion-only)

Native Android assistant that will observe a Block Blast screen, find strong moves, and highlight them in a
floating overlay. **It never taps, drags, or automates gameplay; the user makes every move.**

## Status

First functional version of the Android app, **never compiled or run yet**; see `docs/DEVELOPMENT_STATUS.md` and
`docs/RECOGNITION.md` for exactly what is and is not verified. Recognition has been exercised on synthetic frames only.

## Modes
- **Assist** (default): scans, recognises, highlights recommended placements. Never touches the game.
- **Autoplay** (optional, opt-in): drags pieces for you via an accessibility service you enable yourself; verifies every move;
  stops when recognition is doubtful; E-STOP ends it. See `docs/AUTOPLAY.md` (not validated on a device yet).

## Install (debug APK)
GitHub Actions builds it: open the `build-apk` run -> **Artifacts** -> `block-blast-assistant-debug-apk` -> unzip -> install
the `.apk` (allow installs from your file manager/browser). Then: grant overlay permission, tap Start, accept the screen
capture prompt, open Block Blast. Use **Cal** in the floating panel if highlights do not align.

## Requirements
- JDK 17 or newer (CI uses 21), Gradle 8.10.2, Android SDK 35
- Android 10+ (minSdk 29)

## Build and test
```
gradle wrapper --gradle-version 8.10.2   # one time, unless the wrapper is committed
./gradlew :core:test
./gradlew :app:assembleDebug
```

## Continuous validation
`.github/workflows/core-validation.yml` builds and tests `:core` on JDK 21 with Gradle 8.10.2 and uploads the generated wrapper as an artifact.

## Layout
- `core/model` — `Board` (immutable 8x8 bitboard), `Piece` (normalized relative cells)
- `core/rules` — placement, line clears, no-move detection; rule assumptions in `RuleConfig`
- `core/solver` — `BaselineSolver` (exhaustive reference search), `BoardEvaluator` (heuristic weights)
- `core/vision` — board locator, cell classifier, piece extractor, stability tracker, change detector
- `app/` — Android app: MediaProjection capture service, overlay, UI
- `docs/` — development status and recognition notes

## Known limitations
- Game scoring is not modelled (unverified). Heuristic scores are search guides, not game scores.
- Line-clear behaviour (simultaneous row/column clears, no rotation) is an unverified assumption.
- Recognition is unvalidated on real screenshots; manual calibration is the fallback.
- Suggestions only: the app never taps, swipes or automates the game.
