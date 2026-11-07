package com.blockblast.assistant.app

enum class Level { SEARCHING, GOOD, UNCERTAIN, NEEDS_CORRECTION }
enum class Mode { ASSIST, AUTOPLAY }
enum class RunState { STOPPED, RUNNING, PAUSED, NEEDS_ATTENTION }
enum class Severity { INFO, WARNING, ERROR }

/**
 * Everything the floating panel shows. The panel renders ONLY when this value changes (data-class equality), so a
 * repeated identical status never redraws, flickers, or re-announces itself.
 */
data class PanelState(
    val level: Level = Level.SEARCHING,
    val gridConfidence: Double? = null,
    val boardConfidence: Double? = null,
    val piecesText: String = "-",
    val mode: Mode = Mode.ASSIST,
    val run: RunState = RunState.STOPPED,
    val assistPaused: Boolean = false,
    val highlightsOn: Boolean = true,
    val message: String = "Starting...",
    val severity: Severity = Severity.INFO,
    val progress: String = "",
)

/** Panel position survives service restarts within one app session (process). */
object PanelMemory {
    @Volatile var x: Int = Int.MIN_VALUE
    @Volatile var y: Int = Int.MIN_VALUE
    @Volatile var collapsed: Boolean = false
}
