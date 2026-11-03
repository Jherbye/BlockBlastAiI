package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece

data class VerifiedState(
    val boardBits: Long,
    val geometry: BoardGeometry,
    val pieces: List<Piece>,
    val slots: List<Int>,
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
        val state = VerifiedState(board.bits, g, r.pieceList, r.slots)
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
