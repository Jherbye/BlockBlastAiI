package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.solver.BaselineSolver
import com.blockblast.assistant.core.solver.SearchBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VisionTest {
    private val std = SyntheticFrames.STANDARD
    private val pieces3 = listOf(SyntheticFrames.L_SHAPE, SyntheticFrames.BAR3, SyntheticFrames.SQUARE)

    private fun bitsOf(vararg cells: Pair<Int, Int>): Long = cells.fold(0L) { acc, (x, y) -> acc or (1L shl (y * 8 + x)) }

    private fun spec(
        occupied: Long = 0L, pieces: List<com.blockblast.assistant.core.model.Piece?> = pieces3,
        w: Int = std.width, h: Int = std.height, left: Int = std.boardLeft, top: Int = std.boardTop, cell: Int = std.cell,
    ) = SyntheticFrames.Spec(w, h, left, top, cell, occupied, pieces)

    private val sampleBoard = bitsOf(0 to 0, 1 to 0, 2 to 0, 7 to 7, 3 to 4, 4 to 4, 4 to 5, 0 to 7, 6 to 2)

    @Test fun locatorFindsBoardOnStandardLayout() {
        val frame = SyntheticFrames.render(spec(sampleBoard))
        val r = BoardLocator().locate(frame)
        assertNotNull(r)
        val g = r!!.geometry
        assertTrue("left ${g.left}", abs(g.left - 60f) <= 12f)
        assertTrue("top ${g.top}", abs(g.top - 480f) <= 12f)
        assertTrue("cell ${g.cellSize}", abs(g.cellSize - 120f) <= 2.5f)
        assertTrue("conf ${r.confidence}", r.confidence >= 0.35)
    }

    @Test fun locatorWorksOnOtherResolutionAndOffset() {
        val frame = SyntheticFrames.render(spec(sampleBoard, w = 720, h = 1560, left = 36, top = 330, cell = 81))
        val g = BoardLocator().locate(frame)!!.geometry
        assertTrue("left ${g.left}", abs(g.left - 36f) <= 9f)
        assertTrue("top ${g.top}", abs(g.top - 330f) <= 9f)
        assertTrue("cell ${g.cellSize}", abs(g.cellSize - 81f) <= 2.5f)
    }

    @Test fun locatorWorksWhenTheBoardIsLowerAndSlightlyLarger() {
        val frame = SyntheticFrames.render(spec(sampleBoard, left = 40, top = 700, cell = 121))
        val g = BoardLocator().locate(frame)!!.geometry
        assertTrue("left ${g.left}", abs(g.left - 40f) <= 12f)
        assertTrue("top ${g.top}", abs(g.top - 700f) <= 12f)
        assertTrue("cell ${g.cellSize}", abs(g.cellSize - 121f) <= 2.5f)
    }

    @Test fun locatorWorksOnEmptyBoard() {
        val g = BoardLocator().locate(SyntheticFrames.render(spec(0L)))!!.geometry
        assertTrue(abs(g.left - 60f) <= 12f && abs(g.top - 480f) <= 12f && abs(g.cellSize - 120f) <= 2.5f)
    }

    @Test fun classifierReadsOccupancy() {
        val frame = SyntheticFrames.render(spec(sampleBoard))
        val reading = CellReader().read(frame, BoardGeometry(60f, 480f, 120f), emptyList(), null)
        assertEquals(sampleBoard, reading.bits)
        assertTrue("conf ${reading.confidence}", reading.confidence >= 0.75)
    }

    @Test fun classifierTreatsEmptyBoardAsEmpty() {
        val reading = CellReader().read(SyntheticFrames.render(spec(0L)), BoardGeometry(60f, 480f, 120f), emptyList(), null)
        assertEquals(0L, reading.bits)
        assertTrue("conf ${reading.confidence} notes ${reading.notes}", reading.confidence >= 0.75)
    }

    @Test fun extractorReadsPiecesBySlot() {
        val frame = SyntheticFrames.render(spec(sampleBoard))
        val reading = PieceExtractor().extract(frame, BoardGeometry(60f, 480f, 120f))!!
        assertEquals(3, reading.pieces.size)
        assertEquals(pieces3, reading.pieces.map { it.piece })
        assertEquals(listOf(0, 1, 2), reading.pieces.map { it.slot })
        assertTrue("conf ${reading.confidence}", reading.confidence >= 0.5)
    }

    @Test fun extractorHandlesMissingPieces() {
        val frame = SyntheticFrames.render(spec(sampleBoard, pieces = listOf(null, SyntheticFrames.DOT, null)))
        val reading = PieceExtractor().extract(frame, BoardGeometry(60f, 480f, 120f))!!
        assertEquals(listOf(SyntheticFrames.DOT), reading.pieces.map { it.piece })
        assertEquals(listOf(1), reading.pieces.map { it.slot })
    }

    @Test fun extractorReportsNothingWhenTrayIsEmpty() {
        val frame = SyntheticFrames.render(spec(sampleBoard, pieces = emptyList()))
        val reading = PieceExtractor().extract(frame, BoardGeometry(60f, 480f, 120f))!!
        assertTrue(reading.pieces.isEmpty())
    }

    @Test fun pipelineEndToEndFeedsTheSolver() {
        val frame = SyntheticFrames.render(spec(sampleBoard))
        val result = RecognitionPipeline().recognize(frame)
        assertTrue("notes=${result.notes} conf=${result.confidence}", result.ok)
        assertEquals(sampleBoard, result.board!!.bits)
        assertEquals(pieces3, result.pieceList)
        val search = BaselineSolver().search(Board.ofBits(result.board!!.bits), result.pieceList, SearchBudget(100_000, null))
        assertTrue(search.ranked.isNotEmpty())
    }

    @Test fun pipelineUsesManualGeometryWhenGiven() {
        val frame = SyntheticFrames.render(spec(sampleBoard))
        val result = RecognitionPipeline().recognize(frame, BoardGeometry(60f, 480f, 120f))
        assertEquals(GeometrySource.MANUAL, result.source)
        assertEquals(sampleBoard, result.board!!.bits)
    }

    @Test fun pipelineRejectsAFrameWithoutABoard() {
        val blank = PixelFrame(1080, 2340, IntArray(1080 * 2340) { Px.rgb(20, 28, 60) })
        val result = RecognitionPipeline().recognize(blank)
        assertTrue(!result.ok)
        assertTrue(result.source == GeometrySource.NONE || result.locateConfidence < 0.5)
    }

    @Test fun trackerNeedsConsecutiveIdenticalReadings() {
        val pipeline = RecognitionPipeline()
        val tracker = StableStateTracker(2)
        val a = pipeline.recognize(SyntheticFrames.render(spec(sampleBoard)))
        val b = pipeline.recognize(SyntheticFrames.render(spec(sampleBoard or bitsOf(5 to 5))))
        assertTrue(tracker.offer(a) is Stability.Pending)
        assertTrue("changed board must restart", tracker.offer(b) is Stability.Pending)
        assertTrue(tracker.offer(b) is Stability.Verified)
        val rejected = tracker.offer(pipeline.recognize(PixelFrame(1080, 2340, IntArray(1080 * 2340))))
        assertTrue(rejected is Stability.Rejected)
    }

    @Test fun changeDetectorSeesGameChangesButNotExcludedOverlayRegions() {
        val g = BoardGeometry(60f, 480f, 120f)
        val before = SyntheticFrames.render(spec(sampleBoard))
        val overlay = listOf(g.cellRect(5, 5))
        val base = ChangeDetector().baseline(before, g, overlay)
        assertEquals(0, ChangeDetector().changedCount(before, base))
        val changedExcluded = SyntheticFrames.render(spec(sampleBoard or bitsOf(5 to 5)))
        assertEquals("excluded cell must be ignored", 0, ChangeDetector().changedCount(changedExcluded, base))
        val changedReal = SyntheticFrames.render(spec(sampleBoard or bitsOf(1 to 6)))
        assertTrue(ChangeDetector().changedCount(changedReal, base) >= 4)
        val pieceUsed = SyntheticFrames.render(spec(sampleBoard, pieces = listOf(null, SyntheticFrames.BAR3, SyntheticFrames.SQUARE)))
        assertTrue(ChangeDetector().changedCount(pieceUsed, base) >= 4)
    }
}
