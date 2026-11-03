package com.blockblast.assistant.core.solver

data class Recommendation(
    val rank: Int,
    val move: Move,
    val sequence: List<Move>,
    /** Internal search value. NOT the game score, NOT a guarantee that this is the best move. */
    val heuristicScore: Double,
    val linesCleared: Int,
    val explanation: String,
)

object Recommender {
    fun from(result: SearchResult, limit: Int = 12): List<Recommendation> =
        result.ranked.take(limit).mapIndexed { i, r ->
            val lines = r.first.rowsCleared + r.first.colsCleared
            val seqLines = r.sequence.sumOf { it.rowsCleared + it.colsCleared }
            val why = buildString {
                append("clears $lines line(s) now")
                if (seqLines > lines) append(", $seqLines over the best sequence found")
                append("; heuristic ${"%.1f".format(r.heuristicScore)}")
                if (!result.completed) append(" (search cut off by budget)")
            }
            Recommendation(i + 1, r.first, r.sequence, r.heuristicScore, lines, why)
        }
}
