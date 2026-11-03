package com.blockblast.assistant.core.rules

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTest {
    private val rules = RulesEngine()
    private val dot = Piece.parse("#")
    private val domino = Piece.parse("##")
    private val vDomino = Piece.parse("#", "#")

    /** Pads the given top rows with empty rows up to 8. */
    private fun board(vararg top: String): Board =
        Board.fromRows(*top, *Array(Board.SIZE - top.size) { "........" })

    @Test fun pieceIsNormalized() {
        assertEquals(Piece(listOf(Cell(0, 0), Cell(1, 0))), Piece(listOf(Cell(5, 5), Cell(6, 5))))
        assertEquals(2, Piece(listOf(Cell(3, 4), Cell(4, 4), Cell(3, 4))).size)
    }

    @Test fun placementsRespectBounds() {
        assertTrue(rules.canPlace(Board.EMPTY, domino, 6, 0))
        assertFalse(rules.canPlace(Board.EMPTY, domino, 7, 0))
        assertFalse(rules.canPlace(Board.EMPTY, domino, -1, 0))
        assertFalse(rules.canPlace(Board.EMPTY, vDomino, 0, 7))
        assertNull(rules.place(Board.EMPTY, dot, 8, 0))
    }

    @Test fun overlapIsIllegal() {
        val b = rules.place(Board.EMPTY, dot, 3, 3)!!.board
        assertFalse(rules.canPlace(b, domino, 2, 3))
        assertTrue(rules.canPlace(b, domino, 4, 3))
    }

    @Test fun legalPlacementCounts() {
        assertEquals(64, rules.legalPlacements(Board.EMPTY, dot).size)
        assertEquals(49, rules.legalPlacements(Board.EMPTY, Piece.parse("##", "##")).size)
        assertEquals(32, rules.legalPlacements(Board.EMPTY, Piece.parse("#####")).size)
    }

    @Test fun rowClears() {
        val b = board("#######.")
        val r = rules.place(b, dot, 7, 0)!!
        assertEquals(1, r.rowsCleared)
        assertEquals(0, r.colsCleared)
        assertEquals(Board.EMPTY, r.board)
    }

    @Test fun columnClears() {
        val b = Board.fromRows(
            "#.......", "#.......", "#.......", "#.......",
            "#.......", "#.......", "#.......", "........",
        )
        val r = rules.place(b, dot, 0, 7)!!
        assertEquals(0, r.rowsCleared)
        assertEquals(1, r.colsCleared)
        assertEquals(Board.EMPTY, r.board)
    }

    @Test fun simultaneousRowAndColumnClearKeepsOtherCells() {
        val b = Board.fromRows(
            ".#######", "#.......", "#.......", "#..#....",
            "#.......", "#.......", "#.......", "#.......",
        )
        val r = rules.place(b, dot, 0, 0)!!
        assertEquals(1, r.rowsCleared)
        assertEquals(1, r.colsCleared)
        assertEquals(2, r.linesCleared)
        assertEquals(1, r.board.occupiedCount)
        assertTrue(r.board.isOccupied(3, 3))
    }

    @Test fun multipleRowsClearTogether() {
        val b = board(".#######", ".#######")
        val r = rules.place(b, vDomino, 0, 0)!!
        assertEquals(2, r.rowsCleared)
        assertEquals(Board.EMPTY, r.board)
    }

    @Test fun placementDoesNotMutateInput() {
        val b = board("#.......")
        val before = b.bits
        rules.place(b, dot, 1, 0)
        assertEquals(before, b.bits)
    }

    @Test fun sequenceOfPlacements() {
        var b = Board.EMPTY
        for (x in 0 until 8) {
            val r = rules.place(b, dot, x, 0)
            assertNotNull(r)
            b = r!!.board
        }
        assertEquals(Board.EMPTY, b) // the eighth dot completed and cleared row 0
    }

    @Test fun noLegalMoveDetection() {
        val full = Board.fromRows(*Array(8) { "########" })
        assertFalse(rules.hasAnyMove(full, listOf(dot, domino)))
        val oneHole = Board.fromRows("#######.", *Array(7) { "########" })
        assertTrue(rules.hasAnyMove(oneHole, listOf(dot)))
        assertFalse(rules.hasAnyMove(oneHole, listOf(domino, vDomino)))
    }

    @Test fun clearFlagsAreConfigurable() {
        val noRows = RulesEngine(RuleConfig(clearRows = false))
        val r = noRows.place(board("#######."), dot, 7, 0)!!
        assertEquals(0, r.rowsCleared)
        assertEquals(8, r.board.occupiedCount)
    }

    @Test fun topologyHelpers() {
        assertEquals(1, Board.EMPTY.emptyRegionCount())
        assertEquals(0, Board.EMPTY.isolatedEmptyCellCount())
        // (0,0) walled off by (1,0) and (0,1); also a fully separate pocket would add a region.
        val b = board(".#......", "#.......")
        assertEquals(1, b.isolatedEmptyCellCount())
        assertEquals(2, b.emptyRegionCount())
    }

    @Test fun deterministic() {
        val b = board("#.#.#.#.")
        assertEquals(rules.legalPlacements(b, domino), rules.legalPlacements(b, domino))
    }
}
