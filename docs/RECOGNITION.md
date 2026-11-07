# Theme-independent recognition: design, implementation status, validation status

Everything runs offline on the device, in pure Kotlin (no OpenCV, no external AI API). Frames stay in memory.
**Nothing below assumes a colour, texture, background or theme.** Where a number appears (e.g. 0.2 cell), it is relative
to the detected cell size or to the measured dynamic range of the screen being analysed.

## Signals and how they are combined

| Stage | Signals used | Why it is theme independent |
|---|---|---|
| Board geometry (`BoardLocator`) | periodicity of narrow luminance dips/bumps (cell gaps), both polarities; row/column "gap-line energy" windows that decide which 8x8 cells are the board; sub-pixel regression | profiles are normalised by their own 5-95% range; only relative depth of a narrow gap is used; light or dark gaps both work |
| Cell occupancy (`CellReader`) | per-cell mean colour, texture (luma std), edge density, ring contrast (inner area vs rim); clustering of cells into "looks"; cues for which look is EMPTY: closeness to the grid-gap colour, low texture, distance from tray-piece colours, cluster size, learned empty look from confirmed boards | occupied = differs from the board's own empty look in ANY feature; hue-agnostic; the empty look is inferred from the screen, then learned from user corrections |
| Tray pieces (`PieceExtractor`) | per-row background colour (median, edge fallback), colour distance + Otsu threshold with noise-relative floors, loose mask (connectivity) and strict mask (shape, ignores soft shadows), connected components, whole-number cell fit across blobs, per-cell sampling, empty-slot cross-check | foreground is "differs from the local background", not a colour; tray cell size is measured |
| Temporal (`StableStateTracker`, `ChangeDetector`, `CellReader` model) | state must repeat in 2 consecutive clean scans; cheap change detector ignores our own overlay; learned empty look carries across scans | filters animations and drags; adapts to the theme in use |
| Confidence | per-cell, per-piece, geometry; acceptance needs geometry >= 0.5, board (2nd-lowest cell) >= 0.75, pieces >= 0.5 | anything less is NOT shown as a recommendation |

## Uncertainty, calibration, correction (never a silent guess)
- Low confidence => no highlights, the panel says why, and **Fix** turns into **FIX!**.
- **Cal**: drag/resize a grid over the board (manual geometry; overrides automatic detection; **Auto** reverts).
- **Fix**: shows the recognized state over the game. Tap board cells to toggle occupied/empty, tap cells of the three
  5x5 editors to correct pieces, Confirm. The corrected state is used immediately and the app **learns the theme's
  empty look** from it (stored on the phone; "Forget learned theme" in the main screen clears it).
- These touches edit the app's own data only. Nothing is ever sent to the game.

## IMPLEMENTED (code exists)
All of the above. Overlay is hidden during each full scan so it never contaminates the reading.

## Confidence: what each number means (and how it was checked)
`RecognitionResult` reports the stages separately so the UI can say WHICH stage is doubtful:
- **grid** (`locateConfidence`): dip strength, fraction of grid lines that are real dips, inside/outside energy contrast.
- **board** (`board.confidence`): second-lowest per-cell confidence (one doubtful cell is tolerated by the maths, two are not),
  capped at 0.3 if the reading contains a complete row/column (impossible: lines clear at once). Per-cell confidence is the
  margin from the decision line, scaled by how clear the empty/occupied break is. `weakCells` lists the doubtful cells.
- **pieces**: each piece has its own confidence (cell-sampling margin, tray-cell-size fit, connectedness); `uncertainSlots` names
  the doubtful pieces and slots that look empty but hold faint shapes. A good board with one doubtful piece is reported as
  "board good; piece N uncertain" - not as an unreadable screen.
Acceptance: grid >= 0.5, board >= 0.9, pieces >= 0.5, at least one piece.

**Why a screenshot scored 0.49 although everything was read correctly** (dark board, lavender and tan blocks): about ten EMPTY
cells (slightly textured) sat just under the empty/occupied decision line, which was placed relative to the empty look instead
of in the natural gap between the groups. Fix: the line is now the first significant break above the empty cluster. A first
attempt used the BIGGEST gap and was wrong on boards with several block colours (it put one block colour with the empties:
13 confident errors on a fresh set of 100 synthetic scenes); that was caught by testing on seeds not used for tuning.

**Calibration measured on synthetic themes the code was not tuned on** (Python mirror of the same algorithms; the Kotlin
`ThemeRobustnessTest` prints the same table in CI): on 240+ fresh scenes about half are accepted, and about 2% of ACCEPTED
scenes still contain an error (roughly 4-5 of 205). Among readings with board confidence >= 0.9, about 95% of boards were read
exactly. Hard kinds (blocks nearly the colour of empty cells) are mostly flagged rather than guessed. Confidence is therefore
informative but NOT a guarantee; that is what Fix and Autoplay's move verification are for.

## VALIDATED against actual gameplay screenshots
Three real screenshots, all from the user's phone (1080x2400, ad banner at the bottom):
1. Chocolate/heart theme (pink and tan blocks on a dark textured board). Python mirror: geometry 61.9 / 579.8 / cell 119.0,
   all 64 cells and all three pieces exact. The first APK failed here ("lattice confidence 0.17") because the original
   locator used brightness edges; this led to the gap-based locator.
2. Lavender board with all-magenta beveled blocks (com.block.juggle). Python mirror: auto geometry 59.5 / 580.8 / 119.1
   (about 0.04 cell from the true grid), all 64 cells exact at confidence 1.0, all three pieces exact (1x4, 1x5, L).
   The APK on the phone read this board fine at first, then reported "Cannot read the screen reliably - no pieces found"
   once the tray held three long pieces: they covered ~59% of a row, so the per-row MEDIAN background became the piece
   colour, the real background was flagged as foreground and everything merged into one screen-wide blob that was
   rejected. Fix: `PieceExtractor` now tries three background models (per-row median, screen-edge colour, single
   band-wide median) and keeps the most trustworthy result; regression test `extractorHandlesPiecesCoveringMostOfARow`.
3. Dark board with lavender and tan beveled blocks, other apps' chat-head overlays visible. Python mirror: grid 61.1 / 579.5 / cell
   119.3, all 64 cells exact, all three pieces exact (two vertical 4-bars and a 2x3 block). The APK scored the board 0.49 here
   (see above); after the fix the mirror gives board confidence 1.0 on all three screenshots.
All three screenshots are also encoded in `RealScreenshotTest` (local only: real screenshots are not committed; set
`BLOCKBLAST_REAL_SCREENSHOTS`). That test has not been run on the Kotlin code. **Three themes is still a tiny sample;
accuracy on other real themes is unknown.**

## VALIDATED only on SYNTHETIC themes (not real gameplay)
`ThemeScenes` generates random themes (light/dark; solid, gradient or noisy backgrounds; visible or no board panel; gap
width and polarity; flat, gradient, textured or inset empty cells; flat, gradient, textured, bevel, outline or decal
blocks; corner radius; tray scale and shadows; display brightness/contrast; pixel noise; 5 resolutions/aspect ratios;
header clutter; ad banner) plus two adversarial kinds ("lowcontrast": blocks about as bright as empty cells;
"samecolor": blocks the same colour as empty cells). `ThemeRobustnessTest` measures geometry, board-cell accuracy,
piece-shape accuracy, acceptance rate, confidence and confident-wrong rate, and writes
`core/build/reports/theme-robustness.txt` (uploaded by CI).

Prototype (Python, same algorithms) on 240 synthetic scenes, the reference for what to expect:
- Accepted 106 of 240 (44%); **4 of the 106 accepted scenes contained an error** (cells, a piece set, or geometry),
  i.e. the system is NOT safe to trust blindly on hard themes; the correction UI exists for that reason.
- Board-cell accuracy given acceptance about 99.8%; geometry succeeded on about 75% of scenes (the rest are flagged).
- Hard kinds are mostly flagged rather than guessed: low-contrast accepted 17%, same-colour accepted 20% (one same-colour
  scene was accepted with the whole board inverted).
- Known weak spots: gradient-shaded tiles bias the grid by up to ~0.1 cell; themes whose panel colour lies between the
  empty and block colours weaken the gap signal; textured/noisy backgrounds lower geometry confidence.

The Kotlin port has not yet been executed anywhere; the first CI run produces the Kotlin numbers.
