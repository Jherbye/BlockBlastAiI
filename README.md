# Block Blast Assistant (suggestion-only)

Native Android assistant that will observe a Block Blast screen, find strong moves, and highlight them in a
floating overlay. **It never taps, drags, or automates gameplay; the user makes every move.**

## Status

Stage B of the plan is written: the pure-Kotlin puzzle engine (`:core`) with a baseline solver and unit tests.
**Not yet compiled or run** — see `docs/DEVELOPMENT_STATUS.md` for exactly what is and is not verified.
Android capture, vision, and overlay (Stages D–E) do not exist yet.

## Requirements
- JDK 17 or newer
- Gradle 8.x (to generate the wrapper once)
- Later stages: Android Studio and Android SDK

## Build and test
```
gradle wrapper --gradle-version 8.10.2   # one time; wrapper files are not yet committed
./gradlew :core:test
```

## Continuous validation
`.github/workflows/core-validation.yml` builds and tests `:core` on JDK 21 with Gradle 8.10.2 and uploads the generated wrapper as an artifact.

## Layout
- `core/model` — `Board` (immutable 8x8 bitboard), `Piece` (normalized relative cells)
- `core/rules` — placement, line clears, no-move detection; rule assumptions in `RuleConfig`
- `core/solver` — `BaselineSolver` (exhaustive reference search), `BoardEvaluator` (heuristic weights)
- `docs/` — development status and notes

## Known limitations
- Game scoring is not modelled (unverified). Heuristic scores are search guides, not game scores.
- Line-clear behaviour (simultaneous row/column clears, no rotation) is an unverified assumption.
- No vision, capture, or overlay code yet.
