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

## VALIDATED against actual gameplay screenshots
- **One** real screenshot (chocolate/heart theme, 1080x2400, ad banner, overlay panel on screen): in a Python mirror of
  the algorithms the geometry (61.9 / 579.8 / cell 119.0), all 64 cells and all three tray pieces are read exactly, and the
  result is accepted. The same real image is also checked by `RealScreenshotTest` (local only; real screenshots are not
  committed; set `BLOCKBLAST_REAL_SCREENSHOTS`). The first APK failed on that screenshot ("lattice confidence 0.17"),
  which led to the gap-based locator.
- No other real theme has been tested. **Accuracy on other real themes is unknown.**

## VALIDATED only on SYNTHETIC themes (not real gameplay)
`ThemeScenes` generates random themes (light/dark; solid, gradient or noisy backgrounds; visible or no board panel; gap
width and polarity; flat, gradient, textured or inset empty cells; flat, gradient, textured, bevel, outline or decal
blocks; corner radius; tray scale and shadows; display brightness/contrast; pixel noise; 5 resolutions/aspect ratios;
header clutter; ad banner) plus two adversarial kinds ("lowcontrast": blocks about as bright as empty cells;
"samecolor": blocks the same colour as empty cells). `ThemeRobustnessTest` measures geometry, board-cell accuracy,
piece-shape accuracy, acceptance rate, confidence and confident-wrong rate, and writes
`core/build/reports/theme-robustness.txt` (uploaded by CI).

Prototype (Python, same algorithms) on 240 synthetic scenes, the reference for what to expect:
- Accepted 94 of 240 (39%); **5 of the 94 accepted scenes contained an error** (cells, a piece set, or geometry),
  i.e. the system is NOT safe to trust blindly on hard themes; the correction UI exists for that reason.
- Board-cell accuracy given acceptance about 99.8%; geometry succeeded on about 75% of scenes (the rest are flagged).
- Hard kinds are mostly flagged rather than guessed: low-contrast accepted 17%, same-colour accepted 20% (one same-colour
  scene was accepted with the whole board inverted).
- Known weak spots: gradient-shaded tiles bias the grid by up to ~0.1 cell; themes whose panel colour lies between the
  empty and block colours weaken the gap signal; textured/noisy backgrounds lower geometry confidence.

The Kotlin port has not yet been executed anywhere; the first CI run produces the Kotlin numbers.
