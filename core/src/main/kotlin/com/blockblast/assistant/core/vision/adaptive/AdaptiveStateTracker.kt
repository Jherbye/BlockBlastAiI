package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.BoardGeometry

class AdaptiveVerified(val boardBits: Long, val geometry: BoardGeometry, val pieces: List<Piece>, val slots: List<Int>)

sealed class AdaptiveStability {
    data class Verified(val state: AdaptiveVerified) : AdaptiveStability()
    data class Pending(val seen: Int, val needed: Int) : AdaptiveStability()
    /** Not confident: carries the status so the UI can ask for confirmation or correction instead of showing moves. */
    data class NotReady(val status: RecognitionStatus, val reason: String) : AdaptiveStability()
}

/** Accepts a state only after [needed] consecutive identical READY readings; user-corrected states are accepted at once. */
class AdaptiveStateTracker(private val needed: Int = 2) {
    private var candidate: AdaptiveVerified? = null
    private var count = 0

    fun reset() { candidate = null; count = 0 }

    fun offer(r: AdaptiveResult): AdaptiveStability {
        val g = r.geometry
        val bits = r.bits
        if (!r.ok || g == null || bits == null) {
            reset()
            return AdaptiveStability.NotReady(r.status, r.notes.joinToString("; ").ifEmpty { "recognition not confident" })
        }
        val state = AdaptiveVerified(bits, g, r.pieceList, r.slots)
        if (r.userCorrected) { candidate = state; count = needed; return AdaptiveStability.Verified(state) }
        val prev = candidate
        if (prev != null && prev.boardBits == state.boardBits && prev.pieces == state.pieces && prev.geometry.isSameAs(state.geometry)) count++
        else { candidate = state; count = 1 }
        return if (count >= needed) AdaptiveStability.Verified(state) else AdaptiveStability.Pending(count, needed)
    }
}
