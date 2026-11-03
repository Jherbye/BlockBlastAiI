# Recognition design and honest status

Everything runs on-device in pure Kotlin (no OpenCV, no external AI API). Frames are analysed in memory and never saved.

## Pipeline (`core/.../vision`)
1. `BoardLocator` - finds the 8x8 grid from periodic luminance edges (outlier-capped column/row edge profiles, 9-line lattice
   search, sub-pixel regression on the interior lines). Returns a `BoardGeometry` (left, top, cell size) and a confidence.
2. `CellClassifier` - per-cell brightness+colourfulness, Otsu split into empty/occupied. No clear split => treated as empty.
3. `PieceExtractor` - foreground mask of the tray region below the board, connected blobs, one common tray cell size
   (prefers 0.6 x board cell), per-cell sampling -> normalized `Piece` shapes. Shape-based, not colour-based.
4. `RecognitionPipeline` - combines the above, caches automatic geometry, accepts a manual calibration override.
5. `StableStateTracker` - accepts a state only after 2 consecutive identical confident scans (filters animations/drags).
6. `ChangeDetector` - cheap sampled comparison between scans, ignoring points under our own overlay.

## Overlay vs. capture
The overlay would be captured by MediaProjection. Before each full scan the service hides both overlay windows, waits for a
fresh frame, restores them, then recognizes. In between, `ChangeDetector` watches for board/tray changes (excluding our own
highlight cells and panel) and only triggers a rescan once the screen settles.

## What is validated
- Synthetic frames only (`SyntheticFrames` in the tests): dark gradient, gapped board, three tray slots, header and banner.
  A Python mirror of the algorithms matched the intended results on those frames and exposed one real locator bug
  (it locked onto the tray/banner) that was fixed before the Kotlin port. The Kotlin tests themselves run only in CI.
- **NOT validated on any real Block Blast screenshot.** Real accuracy is unknown.

## Assumptions that real screenshots may break
- Board spans 60-98% of screen width, sits in the 10%-78% height band, and its cell borders produce luminance edges.
- Occupied cells are clearly brighter/more colourful than empty cells; a fully occupied board does not occur.
- Pieces are drawn 0.4-6.2 board-cells below the board, edge-connected, at most 5 cells wide/tall, tray cell about 0.3-0.95
  of a board cell (0.6 preferred). Ads/banners inside that band would confuse it.
- Previews/ghost pieces while dragging are handled only by the 2-scan stability rule.

## Fallbacks
Manual calibration (panel "Cal": drag/resize a grid over the board) overrides automatic detection; "Auto" clears it.
Unreliable readings are never presented as certain: highlights are hidden and the panel says why.

## Next validation step
Collect permission-cleared real screenshots (various phones, mid-game, animations, dragging), label them, and measure.
