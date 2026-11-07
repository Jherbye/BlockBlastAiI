package com.blockblast.assistant.core.autoplay

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.Placement
import com.blockblast.assistant.core.rules.RulesEngine
import com.blockblast.assistant.core.solver.Move
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.PieceGrab

data class DragPlan(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

/**
 * Pure planning and verification helpers for Autoplay (no Android code, unit-testable).
 *
 * A move is a drag: the finger starts on a filled cell of the tray piece ([PieceGrab]) and ends on the board cell where
 * that same cell of the piece must land, shifted by a learned drop offset (games often draw the dragged piece offset
 * from the finger; the offset is learned from verified results, see [explainPlacement]).
 */
object AutoplayPlanner {
    fun plan(g: BoardGeometry, move: Move, grab: PieceGrab, offsetX: Float, offsetY: Float): DragPlan {
        val toX = g.left + (move.x + grab.cellX + 0.5f) * g.cellSize + offsetX
        val toY = g.top + (move.y + grab.cellY + 0.5f) * g.cellSize + offsetY
        return DragPlan(grab.x, grab.y, toX, toY)
    }

    /** The board the game should show after [move], including line clears; null if the move is illegal. */
    fun expectedBoard(rules: RulesEngine, board: Board, move: Move): Board? =
        rules.place(board, move.piece, move.x, move.y)?.board

    /**
     * Which placement of [piece] on [before] explains [observed]? Only a UNIQUE explanation is returned; null means
     * nothing matches (the drag failed or something else changed) or the observation is ambiguous.
     */
    fun explainPlacement(rules: RulesEngine, before: Board, piece: Piece, observed: Board): Placement? {
        var found: Placement? = null
        for (pl in rules.legalPlacements(before, piece)) {
            val after = rules.place(before, piece, pl.x, pl.y)?.board ?: continue
            if (after == observed) {
                if (found != null) return null
                found = pl
            }
        }
        return found
    }
}
