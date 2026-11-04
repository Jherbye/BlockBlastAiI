# Theme-robustness report

**Everything below was measured on SYNTHETIC renderings produced by `ThemeRenderer` (`core/src/test/.../vision/adaptive`).
No real Block Blast screenshots of other themes were available. These numbers show that the algorithms are internally
consistent across many invented looks; they are NOT evidence of accuracy on the real game.** The renderer was written by
the same author as the recogniser, so shared blind spots are possible.

## How to read it
- *Cold*: a brand-new recogniser sees one frame of a theme it has never seen. *Warm*: the user corrected the first frame
  once (the theme model learns from it) and 3 more frames of that theme are read.
- **READY-but-wrong** is the safety metric: frames where the app would have SHOWN MOVES from a wrong board or wrong pieces.
  Everything that is not READY (needs confirmation / correction / no board) shows no moves, so a low READY rate costs
  convenience, not correctness.
- Cell/piece accuracy count abstained frames too (their reading is still compared with the truth).
- "Development themes" were used while building. "HELD-OUT" random themes (seeds 1000-1039) were first evaluated after
  the algorithms were frozen for that round, but I then iterated on what they showed, so they are only semi-independent.
  "SEALED" (seeds 9000-9039) were evaluated exactly once, with the final code, after all changes in this document.
- "Legacy" columns are the previous fixed-assumption pipeline (`RecognitionPipeline`) on the same frames, kept as a baseline
  ("accepted-but-wrong" = it reported ok with a wrong board or pieces).

## Final results (all code as committed)

### A. Cold start (new recognizer, one frame, theme never seen)

| Condition | Frames | Cell acc. | Board exact | Piece acc. | READY | READY-but-wrong | Conf. right/wrong | Legacy cell acc. | Legacy exact | Legacy accepted-but-wrong |
|---|---|---|---|---|---|---|---|---|---|---|
| Development themes (12) | 24 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.94 / - | 93.4% | 62.5% | 3 |
| Light theme | 3 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.96 / - | 63.5% | 0.0% | 3 |
| Dark themes | 11 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.96 / - | 96.0% | 63.6% | 1 |
| Occupied/empty similar colours | 9 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.91 / - | 83.0% | 33.3% | 0 |
| Gradient / textured / patterned boards | 10 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.95 / - | 100.0% | 90.0% | 0 |
| Transparent, striped, noisy backgrounds | 8 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.96 / - | 100.0% | 62.5% | 1 |
| HELD-OUT random themes (40) | 80 | 97.5% | 95.0% | 98.5% | 78.8% | 0 | 0.86 / 0.14 | 83.8% | 57.5% | 11 |

### B. Capture / display perturbations (cold start, development themes)

| Condition | Frames | Cell acc. | Board exact | Piece acc. | READY | READY-but-wrong | Conf. right/wrong | Legacy cell acc. | Legacy exact | Legacy accepted-but-wrong |
|---|---|---|---|---|---|---|---|---|---|---|
| Brightness x0.6 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.93 / - | 92.7% | 33.3% | 1 |
| Brightness x0.8 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.93 / - | 92.7% | 50.0% | 0 |
| Brightness x1.25 (clipped) | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.95 / - | 92.7% | 58.3% | 1 |
| Gamma 0.7 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.95 / - | 92.7% | 50.0% | 2 |
| Gamma 1.5 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.95 / - | 93.6% | 50.0% | 2 |
| Sensor noise sigma 6 | 12 | 95.8% | 91.7% | 83.3% | 50.0% | 0 | 0.78 / 0.14 | 93.6% | 58.3% | 2 |
| Blur (3x3 box) | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.94 / - | 93.6% | 58.3% | 2 |
| Display scaling 0.67x (bilinear) | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.91 / - | 93.6% | 58.3% | 2 |
| Display scaling 0.85x (bilinear) | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.92 / - | 93.6% | 58.3% | 2 |
| Native 720x1560 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.93 / - | 93.6% | 50.0% | 3 |
| Native 1440x3120 | 12 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.94 / - | 93.6% | 58.3% | 2 |
| Native 1080x2160 (18:9) | 12 | 100.0% | 100.0% | 95.8% | 91.7% | 0 | 0.94 / 0.28 | 93.6% | 50.0% | 2 |

### C. Warm (user corrected the first frame once; 3 further frames of the same theme)

| Condition | Frames | Cell acc. | Board exact | Piece acc. | READY | READY-but-wrong | Conf. right/wrong |
|---|---|---|---|---|---|---|---|
| Development themes | 36 | 100.0% | 100.0% | 100.0% | 100.0% | 0 | 0.95 / - |
| HELD-OUT random themes (40) | 120 | 100.0% | 100.0% | 95.4% | 75.0% | 0 | 0.85 / 0.34 |
| Dev themes + brightness x0.7 + noise | 36 | 100.0% | 100.0% | 100.0% | 97.2% | 0 | 0.92 / - |

### D. SEALED random themes (single evaluation)

| Condition | Frames | Cell acc. | Board exact | Piece acc. | READY | READY-but-wrong | Conf. right/wrong | Legacy cell acc. | Legacy exact | Legacy accepted-but-wrong |
|---|---|---|---|---|---|---|---|---|---|---|
| Sealed cold (40) | 80 | 95.0% | 95.0% | 91.5% | 75.0% | 0 | 0.83 / 0.24 | 75.2% | 41.3% | 8 |
| Sealed warm (40) | 120 | 95.8% | 95.8% | 90.4% | 75.0% | 0 | 0.87 / 0.40 | 0.0% | 0.0% | 0 |


## What the testing found, and what changed (honest log)
1. First full run: **1 READY-but-wrong** at 1080x2160 (a 5-bar silently vanished when it merged with a striped
   background; the "spans the whole tray" rule discarded it without a note). Fixed: such merges are now reported and make
   the reading abstain.
2. First sealed run (seeds 5000-5039): **2 cold + 4 warm READY-but-wrong**. Causes: tall pieces whose lower rows are
   hidden by a bottom banner were read one or two rows short, with high confidence. Fixed: a piece that runs into a
   persistent horizontal edge (banner) or the end of the band is capped to low confidence. One of the first fixes
   over-triggered on striped backgrounds (held-out READY fell from 80% to 60%); it was tightened (the step must persist to
   the end of the band and the piece must start above it).
3. Those seeds are now **burned** (I debugged on them), which is why the sealed set was moved to 9000-9039.
4. **Known remaining failure** (seed 5025, warm only, 1 frame in ~200): a 3x3 piece read as 2 wide at confidence 0.81,
   because one column is close to the tray-panel colour. Per-cell contrast is not checked, only per-piece contrast. This
   can still produce a wrong READY in rare low-contrast cases. Not fixed.

## Known weaknesses (measured)
- Sensor noise (sigma 6) drops READY to 50% and lowers piece accuracy to 83%; it abstains rather than guesses, but it does
  misread some cells when it does answer (95.8% cell accuracy).
- Held-out/sealed READY is ~75-79%: roughly one new-theme frame in four asks for confirmation or correction.
- Orientation (which of the two cell groups is "occupied") on dense boards of an unseen theme is inferred, not known; the
  recogniser asks for confirmation instead of showing moves when that vote is weak.
- Camouflaged pieces (nearly the colour of the tray) are never reported with high confidence.

## What this does NOT test
Real screenshots of other themes; real animations/particles/combos; real ads and notifications; real device colour
management; the Android capture path; on-device speed and memory (the Lab integrals need roughly 36 MB transient).

## Reproduce
```
cd /home/claude/tools && ./build.sh                                  # compile + all JUnit tests (52)
java -Xmx3g -cp out/main:out/test:kotlinc/lib/kotlin-stdlib.jar \
  com.blockblast.assistant.core.vision.adaptive.RobustnessSuite [quick] [sealed seal=<start>] out=report.md
```
The suite is a standalone main (about 4 minutes), NOT part of `:core:test`; JUnit covers `AdaptiveRecognitionTest` and `TraySafetyTest` (regressions for the failures above). Use a NEW `seal=` value for every new sealed evaluation.
