package com.blockblast.assistant.core.rules

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece

/**
 * Rule assumptions, isolated here so they can change without touching the solver.
 * UNVERIFIED against the real game: both flags default to the commonly observed behaviour
 * (a full row or column clears, and everything completed by one placement clears together).
 * Scoring is deliberately NOT modelled until it has been verified from observed gameplay.
 */
data class RuleConfig(
    val clearRows: Boolean = true,
    val clearColumns: Boolean = true,
)

data class Placement(val x: Int, val y: Int)

data class PlacementResult(
    val board: Board,
    val rowsCleared: Int,
    val colsCleared: Int,
    val cellsPlaced: Int,
) {
    val linesCleared: Int get() = rowsCleared + colsCleared
}

class RulesEngine(val config: RuleConfig = RuleConfig()) {

    fun canPlace(board: Board, piece: Piece, x: Int, y: Int): Boolean {
        val mask = piece.maskAt(x, y)
        return mask != 0L && (board.bits and mask) == 0L
    }

    /** All legal top-left positions for [piece], ordered by row then column (deterministic). */
    fun legalPlacements(board: Board, piece: Piece): List<Placement> {
        val result = ArrayList<Placement>()
        for (y in 0..Board.SIZE - piece.height) {
            for (x in 0..Board.SIZE - piece.width) {
                if (canPlace(board, piece, x, y)) result.add(Placement(x, y))
            }
        }
        return result
    }

    /** Places [piece] and resolves line clears; returns null if the placement is illegal. Never mutates [board]. */
    fun place(board: Board, piece: Piece, x: Int, y: Int): PlacementResult? {
        val mask = piece.maskAt(x, y)
        if (mask == 0L || (board.bits and mask) != 0L) return null
        val placed = board.bits or mask

        var clearMask = 0L
        var rows = 0
        var cols = 0
        if (config.clearRows) {
            for (r in 0 until Board.SIZE) {
                val m = Board.ROW_MASKS[r]
                if ((placed and m) == m) { clearMask = clearMask or m; rows++ }
            }
        }
        if (config.clearColumns) {
            for (c in 0 until Board.SIZE) {
                val m = Board.COL_MASKS[c]
                if ((placed and m) == m) { clearMask = clearMask or m; cols++ }
            }
        }
        return PlacementResult(Board.ofBits(placed and clearMask.inv()), rows, cols, piece.size)
    }

    /** True if at least one of [pieces] has a legal placement. */
    fun hasAnyMove(board: Board, pieces: Collection<Piece>): Boolean =
        pieces.any { piece ->
            (0..Board.SIZE - piece.height).any { y ->
                (0..Board.SIZE - piece.width).any { x -> canPlace(board, piece, x, y) }
            }
        }
}
