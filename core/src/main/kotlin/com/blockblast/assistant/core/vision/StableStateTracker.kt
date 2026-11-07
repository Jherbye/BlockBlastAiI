package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece

/**
 * Where to put a finger to pick a tray piece up: the centre of one of its filled cells (never an empty corner of its
 * bounding box), the index of that cell inside the normalized piece, and its screen position.
 */
data class PieceGrab(val cellX: Int, val cellY: Int, val x: Float, val y: Float)

data class VerifiedState(
    val boardBits: Long,
    val geometry: BoardGeometry,
    val pieces: List<Piece>,
    val slots: List<Int>,
    /** Parallel to [pieces]; null entries (e.g. after a manual correction) mean the position is unknown. */
    val grabs: List<PieceGrab?> = emptyList(),
)

sealed class Stability {
    data class Verified(val state: VerifiedState) : Stability()
    data class Pending(val seen: Int, val needed: Int) : Stability()
    data class Rejected(val reason: String) : Stability()
}

/** Accepts a state only after [needed] consecutive identical, confident recognitions (filters animations and drags). */
class StableStateTracker(private val needed: Int = 2) {
    private var candidate: VerifiedState? = null
    private var count = 0

    fun reset() {
        candidate = null
        count = 0
    }

    fun offer(r: RecognitionResult): Stability {
        val g = r.geometry
        val board = r.board
        if (!r.ok || g == null || board == null) {
            reset()
            return Stability.Rejected(r.notes.joinToString("; ").ifEmpty { "recognition not confident" })
        }
        val state = VerifiedState(board.bits, g, r.pieceList, r.slots, r.pieces?.pieces?.map { it.grab } ?: emptyList())
        val prev = candidate
        if (prev != null && same(prev, state)) count++ else {
            candidate = state
            count = 1
        }
        return if (count >= needed) Stability.Verified(state) else Stability.Pending(count, needed)
    }

    private fun same(a: VerifiedState, b: VerifiedState): Boolean =
        a.boardBits == b.boardBits && a.pieces == b.pieces && a.geometry.isSameAs(b.geometry)
}
