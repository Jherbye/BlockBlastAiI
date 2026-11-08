# Changelog

## Unreleased
- Autoplay: closed-loop drop (hold, measure the dragged piece, nudge, release or cancel), DraggedPieceLocator, no foreground-package check, one re-check before declaring a mismatch, Events log.
- Recognition: first-significant-gap decision line, structural checks, per-stage and per-piece confidence, board acceptance 0.9.
- Persistent compact control panel with debounced status; Assist/Autoplay modes; AutoplayAccessibilityService; AutoplayPlanner; E-STOP.
- Piece reader tries three background models (fixes tray lost when pieces cover most of a row, found on a real screenshot); second real screenshot added to the local real-screenshot test.
- Theme-independent recognition (gap-geometry locator, empty-look cell reader, background-relative tray reader), per-cell confidence, Fix (manual correction + theme learning), synthetic theme generator and robustness test. Uncompiled.
- Locator rewritten to detect cell-gap valleys (fixes "lattice confidence too low" on a real screenshot); tray scale prior 0.5; local-only real-screenshot test.
- Android app module (MediaProjection capture, floating overlay, solver integration), pure-Kotlin vision pipeline in `:core`, `build-apk` workflow. Uncompiled.
- core now targets JVM 17 so the Android module can consume it.
- CI: `core-validation` GitHub Actions workflow (JDK 21, Gradle 8.10.2); not yet run.
- Stage B: `:core` module with Board, Piece, RulesEngine, BoardEvaluator, BaselineSolver and unit tests (uncompiled).
