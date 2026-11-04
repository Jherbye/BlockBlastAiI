# Recognition design and honest status

Everything runs on-device in pure Kotlin (no OpenCV, no external AI API). Frames are analysed in memory and never saved.
Numbers: `docs/ROBUSTNESS_REPORT.md`. **Implemented is not the same as validated; see the two tables at the end.**

## Principle
The game can change theme and block appearance, so nothing may depend on absolute colours, textures or one background.
Thresholds are colour DIFFERENCES (CIE Lab dE) or ratios; the board is found from periodic geometry; blocks are found by
how a cell differs from the other cells and from the board structure; shapes come from occupied squares.

## Active pipeline (`core/.../vision/adaptive`, used by the app)
1. `LabImage` - downscaled Lab planes, gradients, integral images (O(1) region stats).
2. `GridDetector` - 8x8 lattice from edge-energy periodicity (comb score on lattice lines vs. mid-cell, polarity free),
   sub-pixel line regression with a shared pitch, extent test (the lattice must stop at the board), autocorrelation hint.
   Gives geometry + confidence. No background colour is assumed.
3. `CellFeatures` / `CellAnalyzer` - per-cell colour, texture, relief and lattice-contrast features; plane detrending (gradients),
   checkerboard parity normalisation; an unsupervised "tight densest group + outliers" split fused over several signals
   with conflict detection; orientation vote for which group is "occupied" (minority, chroma, texture, tray colours).
   Per-cell confidence. A uniform board is handled as such.
4. `ThemeModel` - one-class model of what EMPTY cells look like in the current theme, learned only from verified,
   high-confidence or user-confirmed/corrected states. Once ready it replaces the unsupervised split. A theme change drops it.
5. `TrayReader` - per-row and global background modes, closing/hole fill, connected components, shadow removal, one common
   cell pitch from a whole-number fit + lattice support + learned tray/board ratio (ambiguity is reported), shape from
   occupied sub-squares, contrast-capped confidence. Shapes that are dropped, merged with the background, or run into a
   banner / band edge are NOT silent: they add a note and cap confidence so the reading abstains.
6. `TemporalFusion` / `TransitionValidator` - per-cell stability across frames, inconsistency cap, and a check that a change
   between states is explainable by one move (RulesEngine).
7. `AdaptiveRecognizer` - combines all of it into a `RecognitionStatus`:
   `READY` (moves may be shown), `NEEDS_CONFIRMATION` (new theme, occupancy was inferred), `NEEDS_CORRECTION`,
   `NO_PIECES`, `NO_BOARD`. Also a plausibility check (no full rows/columns on a settled board).
8. `AdaptiveStateTracker` - requires 2 consecutive identical READY readings (user-corrected states are accepted at once).

## Never guess silently
Moves are drawn ONLY for READY. For any other status the overlay clears highlights, the panel says why, and the correction
editor opens (once per unsure episode; "Fix" reopens it). The editor (`ReviewView`) shows the reading with low-confidence
cells/pieces outlined in red; the user toggles board cells and 5x5 piece grids, then "Apply my fixes" or, for a new theme,
"Looks right". The corrected state is used immediately and teaches `ThemeModel`. "Reset theme" forgets what was learned.
"Cal" (manual grid alignment) and "Auto" still exist. The editor edits only the assistant's own reading; it never sends
input to the game (it is modal while open so a stray tap cannot reach the game). No taps, drags or gestures are injected.

## Overlay vs. capture
Unchanged: both overlay windows are hidden before each scan, a fresh frame is grabbed, windows restored; `ChangeDetector`
watches between scans, ignoring our own overlay regions. Highlights use the detected (or calibrated) geometry, so they stay
aligned with the real board.

## Legacy pipeline
`BoardLocator`/`CellClassifier`/`PieceExtractor`/`RecognitionPipeline` (fixed assumptions: dark gaps, brighter blocks) are
kept only as a baseline for the benchmark and for `RealScreenshotTest`; the app no longer uses them.

## IMPLEMENTED (code exists, unit/benchmark tested in the JVM)
All of the above, the editor UI, and the service integration.

## VALIDATED against real gameplay screenshots
- **Adaptive pipeline: nothing.** No real screenshot was available in this work; it has never seen real Block Blast.
- Legacy pipeline: one real screenshot (chocolate/heart theme, supplied earlier) was read correctly by a Python mirror;
  the Kotlin port and `RealScreenshotTest` (env `BLOCKBLAST_REAL_SCREENSHOTS`) were not run against it. Run that test
  with the adaptive recognizer too before trusting it.
- Synthetic only: 12 development themes, random held-out and sealed themes, brightness/gamma/noise/blur/scaling/resolution
  perturbations. See the report.
- The `:app` module (service, overlay, editor) was NOT compiled here (no Android SDK in this environment). It is written
  against the same APIs as the previously CI-built code, but expect possible compile errors on first CI run.

## Assumptions real screenshots may break
- Board is an 8x8 lattice with visible cell structure (edges/relief/colour differences), roughly square.
- Tray pieces sit below the board, edge-connected, <=5x5, one common cell pitch, slots roughly aligned to board thirds.
- The shape catalog in `ShapeCatalog` is an assumption (unknown shapes only lower confidence, never block).
- Animations, particles, combo text, ads over the tray, and drag previews are handled only by the stability rule and
  abstention; none were tested.
- Per-cell contrast inside a piece is not checked (see the known failure in the report).
