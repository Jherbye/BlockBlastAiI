# Development status

## Current stage
Stage B (pure puzzle engine + baseline solver) — written, **not compiled, not test-run**.
Build-validation task: CI workflow added; **no CI run has happened**, so compilation and test status is still UNKNOWN.

## Authoring environment (audit)
JDK 21 and git 2.43 present. No Kotlin compiler, Gradle, Android SDK, or network. No prior repository.
Therefore: no `./gradlew` wrapper was generated, Gradle plugin version 2.0.21 and JUnit 4.13.2 are unverified
choices, and no Kotlin test has been executed.

## What was actually verified
- A Python re-implementation of the bit tricks (flood fill, isolated-cell count, region count, placement and
  simultaneous row/column clearing) matched naive grid implementations on 20,000 random boards and 30,000
  random placements (1,229 legal). This checks the algorithm, **not the Kotlin source**.

## Implemented (unverified by compiler)
- `Board`, `Piece`, `RulesEngine`/`RuleConfig`, `BoardEvaluator`/`EvalWeights`, `BaselineSolver` with node/time budget and cancellation.
- Tests: `RulesEngineTest` (13), `BaselineSolverTest` (8).

## Design decisions
- Board is a 64-bit bitboard (bit = y*8+x), immutable. Pieces normalized to a (0,0) top-left origin; no rotation.
- Solver always returns every legal first move; on budget expiry or cancellation unexplored tails are scored as-is.
- Equal-shaped pieces are searched once. Ties break by piece index, then y, then x.
- Scoring is not modelled; heuristic score is separate and labelled as such.

## Unverified game-rule assumptions (need observed gameplay)
Simultaneous row+column clears; no piece rotation; clears resolve only after each placement; scoring rules.

## Known defects / risks
- Code may contain compile errors until first built.
- Evaluator weights are untuned starting values.

## Build validation pipeline (added after Stage B)
- `.github/workflows/core-validation.yml`: JDK 21 (Temurin), Gradle 8.10.2 via `gradle/actions/setup-gradle`,
  generates the wrapper if absent, then compiles main+test, runs `gradle :core:test`, re-runs the suite through
  `./gradlew`, and uploads test reports plus the generated wrapper as artifacts. Read-only (`contents: read`).
- Checked locally: the YAML parses. Not checked: that the workflow actually runs (nothing has been pushed).
- Gradle wrapper files are NOT in the repo. The wrapper jar is a binary that cannot be produced without Gradle
  or network access here. The first CI run produces it as the `gradle-wrapper-8.10.2` artifact; commit those files.

## Build and test results
None yet. Compilation errors: none recorded (never compiled). Test failures: none recorded (never run).
Add the real CI output here after the first run.

## Source areas to watch on first compile (suspicions, not observed errors)
- `Piece.parse` uses `Array.flatMapIndexed` and `String.mapIndexedNotNull`; their availability on these receivers is unconfirmed.
- Smart-cast and generics in `BaselineSolver`/tests have never been through the compiler.

## Next steps
1. With authorization, push the repo (or run the workflow some other way), read the real CI output, record every error here, fix genuine issues without weakening tests, and commit the wrapper files from the artifact.
2. Commit the wrapper. Collect user-provided examples to confirm rules and scoring.
3. Stage C: benchmark corpus, then beam search / transposition table / pruning, kept only if measured better.
4. Stages D–F: Android app module, MediaProjection capture, recognition, overlay.
