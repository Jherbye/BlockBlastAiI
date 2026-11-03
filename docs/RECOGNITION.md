# Recognition design and honest status

Everything runs on-device in pure Kotlin (no OpenCV, no external AI API). Frames are analysed in memory and never saved.

## Pipeline (`core/.../vision`)
1. `BoardLocator` - finds the 8x8 grid by its dark cell GAPS: mean-luminance profiles (texture and decals average out), a
   7-interior-line valley lattice search with an outside-the-board penalty, a per-row vertical-gap energy window that fixes
   which rows are the board, then sub-pixel regression. Returns a `BoardGeometry` (left, top, cell size) and a confidence.
2. `CellClassifier` - per-cell brightness+colourfulness, Otsu split into empty/occupied. No clear split => treated as empty.
3. `PieceExtractor` - foreground mask of the tray region below the board, connected blobs, one common tray cell size
   (near-ties prefer 0.5 x board cell), per-cell sampling -> normalized `Piece` shapes. Shape-based, not colour-based.
4. `RecognitionPipeline` - combines the above, caches automatic geometry, accepts a manual calibration override.
5. `StableStateTracker` - accepts a state only after 2 consecutive identical confident scans (filters animations/drags).
6. `ChangeDetector` - cheap sampled comparison between scans, ignoring points under our own overlay.

## Overlay vs. capture
The overlay would be captured by MediaProjection. Before each full scan the service hides both overlay windows, waits for a
fresh frame, restores them, then recognizes. In between, `ChangeDetector` watches for board/tray changes (excluding our own
highlight cells and panel) and only triggers a rescan once the screen settles.

## What is validated
- **One real screenshot** (user-supplied, chocolate/heart theme, 1080x2400, three tray pieces, ad banner, overlay panel
  on screen). The first APK run on it failed with "board lattice confidence 0.17 too low": the original locator looked
  for brightness EDGES, which the textured background and heart decals swamp. Probing the image showed the 8x8 gaps as
  clean periodic valleys (pitch ~119.5 px), hence the valley-based locator. In a Python mirror of the algorithms the
  new locator gives left 61.6 / top 578.2 / cell 119.2 (truth ~61 / 580 / 119.5), the classifier reads the 8x8 board
  EXACTLY as read by eye, and the piece reader returns all three pieces correctly (1x5 bar, S shape, vertical 4-bar,
  slots 0/1/2).
- Synthetic frames (`SyntheticFrames` in the tests) run in CI. A mirror run exposed two earlier synthetic bugs.
- The Kotlin port of the new locator and a local-only `RealScreenshotTest` (set `BLOCKBLAST_REAL_SCREENSHOTS`; skipped in
  CI because real screenshots are not committed without permission) have NOT been executed yet.
- Everything else (other themes, phones, mid-animation, dragging, overlay alignment on device): unvalidated.

## Assumptions that real screenshots may break
- Board spans 60-98% of screen width, sits in the 10%-78% height band, and its cell borders produce luminance edges.
- Occupied cells are clearly brighter/more colourful than empty cells; a fully occupied board does not occur.
- Pieces are drawn 0.4-6.2 board-cells below the board, edge-connected, at most 5 cells wide/tall, tray cell about 0.3-0.95
  of a board cell (0.5 preferred; the real screenshot measures ~0.45). Ads/banners inside that band would confuse it.
- Previews/ghost pieces while dragging are handled only by the 2-scan stability rule.

## Fallbacks
Manual calibration (panel "Cal": drag/resize a grid over the board) overrides automatic detection; "Auto" clears it.
Unreliable readings are never presented as certain: highlights are hidden and the panel says why.

## Next validation step
Collect permission-cleared real screenshots (various phones, mid-game, animations, dragging), label them, and measure.
