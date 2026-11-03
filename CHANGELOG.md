# Changelog

## Unreleased
- Android app module (MediaProjection capture, floating overlay, solver integration), pure-Kotlin vision pipeline in `:core`, `build-apk` workflow. Uncompiled.
- core now targets JVM 17 so the Android module can consume it.
- CI: `core-validation` GitHub Actions workflow (JDK 21, Gradle 8.10.2); not yet run.
- Stage B: `:core` module with Board, Piece, RulesEngine, BoardEvaluator, BaselineSolver and unit tests (uncompiled).
