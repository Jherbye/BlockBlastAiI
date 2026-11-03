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

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }
}
