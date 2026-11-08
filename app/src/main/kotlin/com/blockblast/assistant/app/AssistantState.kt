package com.blockblast.assistant.app

import java.util.concurrent.CopyOnWriteArrayList

/** Everything the diagnostics screen shows. Written by the service, read by the activity (same process). */
data class Snapshot(
    val running: Boolean = false,
    val status: String = "Stopped",
    val boardRows: List<String> = emptyList(),
    val pieceLines: List<String> = emptyList(),
    val geometryText: String = "-",
    val recognitionConfidence: Double = 0.0,
    val searchMillis: Long = 0,
    val nodes: Long = 0,
    val searchCompleted: Boolean = true,
    val stateHash: String = "-",
    val lastTransition: String = "-",
    val lastError: String = "-",
    val notes: String = "-",
    val recommendationCount: Int = 0,
    val needsCorrection: Boolean = false,
    val lowConfidenceCells: Int = 0,
    val learnedTheme: Boolean = false,
    val events: List<String> = emptyList(),
)

object AssistantState {
    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun update(transform: (Snapshot) -> Snapshot) {
        val s = synchronized(this) {
            snapshot = transform(snapshot)
            snapshot
        }
        listeners.forEach { it(s) }
    }

    /** Appends a timestamped line to the diagnostics event log (last 14 kept) so every stop has a visible reason. */
    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        update { it.copy(events = (it.events + "$stamp $message").takeLast(14)) }
    }

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }
}
