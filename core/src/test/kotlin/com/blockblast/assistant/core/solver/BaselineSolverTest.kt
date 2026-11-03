package com.blockblast.assistant.core.solver

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.RulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineSolverTest {
    private val rules = RulesEngine()
    private val solver = BaselineSolver()
    private val dot = Piece.parse("#")
    private val domino = Piece.parse("##")
    private val square = Piece.parse("##", "##")
    private val deterministic = SearchBudget(maxNodes = 2_000_000L, maxMillis = null)

    @Test fun rankedMovesAreAllLegalAndSequencesReplay() {
        val board = Board.fromRows("#.#.#.#.", *Array(7) { "........" })
        val result = solver.search(board, listOf(dot, domino, square), deterministic)
        assertTrue(result.completed)
        for (rm in result.ranked) {
            assertTrue(rules.canPlace(board, rm.first.piece, rm.first.x, rm.first.y))
            // Replaying the full sequence must be legal at every step.
            var b = board
            for (m in rm.sequence) {
                val r = rules.place(b, m.piece, m.x, m.y)
                assertTrue("illegal step in sequence", r != null)
                b = r!!.board
            }
        }
    }

    @Test fun allFirstMovesPresentEvenWhenBudgetExpires() {
        val tiny = SearchBudget(maxNodes = 50L, maxMillis = null)
        val result = solver.search(Board.EMPTY, listOf(dot, domino, square), tiny)
        assertFalse(result.completed)
        assertEquals(64 + 56 + 49, result.ranked.size)
    }

    @Test fun cancellationStopsSearchButKeepsLegalMoves() {
        val token = CancellationToken().also { it.cancel() }
        val result = solver.search(Board.EMPTY, listOf(dot, domino, square), deterministic, token)
        assertTrue(result.cancelled)
        assertFalse(result.completed)
        assertTrue(result.ranked.isNotEmpty())
        assertTrue(result.ranked.all { rules.canPlace(Board.EMPTY, it.first.piece, it.first.x, it.first.y) })
    }

    @Test fun duplicatePiecesAreSearchedOnce() {
        val result = solver.search(Board.EMPTY, listOf(dot, dot, dot), deterministic)
        assertEquals(64, result.ranked.size)
        assertTrue(result.completed)
    }

    @Test fun differentPieceOrdersAreConsidered() {
        val result = solver.search(Board.EMPTY, listOf(dot, domino, square), deterministic)
        assertEquals(setOf(0, 1, 2), result.ranked.map { it.first.pieceIndex }.toSet())
    }

    @Test fun prefersSequenceThatClearsALine() {
        val board = Board.fromRows(".#######", *Array(7) { "........" })
        val result = solver.search(board, listOf(domino, dot), deterministic)
        val best = result.ranked.first()
        assertTrue(best.sequence.sumOf { it.rowsCleared + it.colsCleared } >= 1)
        assertEquals(result.ranked.maxOf { it.heuristicScore }, best.heuristicScore, 0.0)
    }

    @Test fun deadEndIsPenalised() {
        val board = Board.fromRows("#######.", *Array(7) { "########" })
        // Only a single hole remains; the square can never be placed, the dot can.
        val result = solver.search(board, listOf(square, dot), deterministic)
        assertEquals(1, result.ranked.size)
        assertEquals(1, result.ranked.first().first.pieceIndex)
    }

    @Test fun deterministicRepeatedRuns() {
        val board = Board.fromRows("#.#.#.#.", "..##..##", *Array(6) { "........" })
        val pieces = listOf(domino, square, dot)
        val a = solver.search(board, pieces, deterministic)
        val b = solver.search(board, pieces, deterministic)
        assertEquals(a.ranked, b.ranked)
        assertEquals(a.nodesExplored, b.nodesExplored)
    }
}
