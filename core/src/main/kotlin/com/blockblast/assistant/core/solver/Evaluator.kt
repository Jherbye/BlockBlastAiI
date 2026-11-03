package com.blockblast.assistant.core.solver

import com.blockblast.assistant.core.model.Board

/**
 * Weights for the HEURISTIC score. This is a search guide, not the game's score, and it carries no
 * claim about future pieces. Values are starting points to be tuned by benchmark (Stage C).
 */
data class EvalWeights(
    val lineCleared: Double = 10.0,
    val occupiedCell: Double = -1.0,
    val isolatedEmptyCell: Double = -4.0,
    val extraEmptyRegion: Double = -2.0,
    val unplacedPiece: Double = -1000.0,
)

class BoardEvaluator(val weights: EvalWeights = EvalWeights()) {
    fun evaluate(board: Board, linesCleared: Int, unplacedPieces: Int): Double {
        val extraRegions = maxOf(0, board.emptyRegionCount() - 1)
        return weights.lineCleared * linesCleared +
            weights.occupiedCell * board.occupiedCount +
            weights.isolatedEmptyCell * board.isolatedEmptyCellCount() +
            weights.extraEmptyRegion * extraRegions +
            weights.unplacedPiece * unplacedPieces
    }
}
