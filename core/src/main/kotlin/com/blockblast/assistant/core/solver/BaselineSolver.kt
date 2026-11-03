package com.blockblast.assistant.core.solver

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.RulesEngine

class CancellationToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() { isCancelled = true }
}

/** [maxMillis] = null disables the wall-clock limit, which makes a run fully deterministic. */
data class SearchBudget(val maxNodes: Long = 200_000L, val maxMillis: Long? = 1_000L)

data class Move(
    val pieceIndex: Int,
    val piece: Piece,
    val x: Int,
    val y: Int,
    val rowsCleared: Int,
    val colsCleared: Int,
)

/**
 * One candidate first move with the best continuation found for it.
 * [heuristicScore] is an internal search value: NOT the game score, and NOT a guarantee of optimality.
 */
data class RankedMove(
    val first: Move,
    val sequence: List<Move>,
    val heuristicScore: Double,
)

data class SearchResult(
    val ranked: List<RankedMove>,
    val nodesExplored: Long,
    /** False when the budget ran out or the search was cancelled; scores of cut-off branches are then approximate. */
    val completed: Boolean,
    val cancelled: Boolean,
    val elapsedMillis: Long,
)

/**
 * Stage B baseline: exhaustive depth-first search over every ordering and placement of the visible pieces,
 * with no pruning or caching. It exists to be correct and to be the reference for later optimizations.
 *
 * Every legal first move is always returned, even when the budget expires or the search is cancelled;
 * in that case unexplored continuations are scored by evaluating the board as it stands.
 * Pieces that are equal in shape are searched once (duplicate orderings are skipped).
 */
class BaselineSolver(
    private val rules: RulesEngine = RulesEngine(),
    private val evaluator: BoardEvaluator = BoardEvaluator(),
) {
    fun search(
        board: Board,
        pieces: List<Piece>,
        budget: SearchBudget = SearchBudget(),
        token: CancellationToken = CancellationToken(),
    ): SearchResult {
        require(pieces.isNotEmpty()) { "At least one piece is required" }
        return Run(board, pieces, budget, token).execute()
    }

    private class Outcome(val value: Double, val moves: List<Move>)

    private inner class Run(
        private val board: Board,
        private val pieces: List<Piece>,
        private val budget: SearchBudget,
        private val token: CancellationToken,
    ) {
        private var nodes = 0L
        private var stopped = false
        private var cancelled = false
        private val startNs = System.nanoTime()
        private val deadlineNs: Long? = budget.maxMillis?.let { startNs + it * 1_000_000L }

        fun execute(): SearchResult {
            val ranked = ArrayList<RankedMove>()
            val tried = ArrayList<Piece>()
            for (i in pieces.indices) {
                val piece = pieces[i]
                if (tried.contains(piece)) continue
                tried.add(piece)
                val rest = pieces.indices.filter { it != i }
                for (pl in rules.legalPlacements(board, piece)) {
                    val r = rules.place(board, piece, pl.x, pl.y) ?: continue
                    val move = Move(i, piece, pl.x, pl.y, r.rowsCleared, r.colsCleared)
                    val tail = dfs(r.board, rest, r.linesCleared)
                    ranked.add(RankedMove(move, listOf(move) + tail.moves, tail.value))
                }
            }
            ranked.sortWith(
                compareByDescending<RankedMove> { it.heuristicScore }
                    .thenBy { it.first.pieceIndex }
                    .thenBy { it.first.y }
                    .thenBy { it.first.x },
            )
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return SearchResult(ranked, nodes, completed = !stopped, cancelled = cancelled, elapsedMillis = elapsedMs)
        }

        private fun shouldStop(): Boolean {
            if (stopped) return true
            if (token.isCancelled) { stopped = true; cancelled = true; return true }
            if (nodes >= budget.maxNodes) { stopped = true; return true }
            val deadline = deadlineNs
            if (deadline != null && (nodes and 255L) == 0L && System.nanoTime() >= deadline) {
                stopped = true
                return true
            }
            return false
        }

        private fun dfs(b: Board, remaining: List<Int>, lines: Int): Outcome {
            if (remaining.isEmpty() || shouldStop()) {
                return Outcome(evaluator.evaluate(b, lines, 0), emptyList())
            }
            nodes++
            var best: Outcome? = null
            val tried = ArrayList<Piece>()
            for (idx in remaining) {
                val piece = pieces[idx]
                if (tried.contains(piece)) continue
                tried.add(piece)
                val placements = rules.legalPlacements(b, piece)
                if (placements.isEmpty()) continue
                val rest = remaining.filter { it != idx }
                for (pl in placements) {
                    val r = rules.place(b, piece, pl.x, pl.y) ?: continue
                    val move = Move(idx, piece, pl.x, pl.y, r.rowsCleared, r.colsCleared)
                    val tail = dfs(r.board, rest, lines + r.linesCleared)
                    val current = best
                    if (current == null || tail.value > current.value) {
                        best = Outcome(tail.value, listOf(move) + tail.moves)
                    }
                }
            }
            // No remaining piece can be placed: genuine dead end, penalised per unplaced piece.
            return best ?: Outcome(evaluator.evaluate(b, lines, remaining.size), emptyList())
        }
    }
}
