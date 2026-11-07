package com.blockblast.assistant.core.autoplay

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.RulesEngine
import com.blockblast.assistant.core.solver.Move
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.PieceGrab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoplayPlannerTest {
    private val rules = RulesEngine()
    private val g = BoardGeometry(60f, 480f, 120f)
    private val domino = Piece.parse("##")

    private fun move(piece: Piece, x: Int, y: Int) = Move(0, piece, x, y, 0, 0)

    @Test fun dragEndsOnTheMatchingCellOfTheTarget() {
        val plan = AutoplayPlanner.plan(g, move(domino, 3, 2), PieceGrab(1, 0, 500f, 1700f), 0f, 0f)
        assertEquals(500f, plan.fromX, 0.001f)
        assertEquals(1700f, plan.fromY, 0.001f)
        assertEquals(60f + (3 + 1 + 0.5f) * 120f, plan.toX, 0.001f)
        assertEquals(480f + (2 + 0 + 0.5f) * 120f, plan.toY, 0.001f)
    }

    @Test fun dropOffsetShiftsTheEndPoint() {
        val a = AutoplayPlanner.plan(g, move(domino, 0, 0), PieceGrab(0, 0, 100f, 100f), 0f, 0f)
        val b = AutoplayPlanner.plan(g, move(domino, 0, 0), PieceGrab(0, 0, 100f, 100f), 10f, -50f)
        assertEquals(a.toX + 10f, b.toX, 0.001f)
        assertEquals(a.toY - 50f, b.toY, 0.001f)
    }

    @Test fun expectedBoardIncludesLineClears() {
        val before = Board.fromRows("#######.", *Array(7) { "........" })
        val after = AutoplayPlanner.expectedBoard(rules, before, move(Piece.parse("#"), 7, 0))
        assertEquals(Board.EMPTY, after)
        assertNull(AutoplayPlanner.expectedBoard(rules, before, move(Piece.parse("#"), 0, 0))) // overlap = illegal
    }

    @Test fun landingOffsetIsInferredFromTheObservedBoard() {
        val before = Board.EMPTY
        val intended = move(domino, 3, 3)
        val actual = rules.place(before, domino, 4, 2)!!.board // landed one cell right and one up
        val pl = AutoplayPlanner.explainPlacement(rules, before, domino, actual)
        assertNotNull(pl)
        assertEquals(4, pl!!.x - 0)
        assertEquals(1, pl.x - intended.x)
        assertEquals(-1, pl.y - intended.y)
    }

    @Test fun nothingHappenedOrAmbiguousGivesNoExplanation() {
        val before = Board.EMPTY
        assertNull(AutoplayPlanner.explainPlacement(rules, before, domino, before))
    }
}
