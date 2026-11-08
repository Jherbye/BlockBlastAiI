package com.blockblast.assistant.core.autoplay

import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.PixelFrame
import com.blockblast.assistant.core.vision.Px
import com.blockblast.assistant.core.vision.SyntheticFrames
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DraggedPieceLocatorTest {
    private val square = Piece.parse("##", "##")
    private val before = SyntheticFrames.render(
        SyntheticFrames.Spec(1080, 2340, 60, 480, 120, 0b1000000L, listOf(SyntheticFrames.L_SHAPE, SyntheticFrames.BAR3, square)),
    )

    /** Frame with a bright 2x2 piece (cell [c] px) whose top-left is at (x0, y0), as if hovering under a finger. */
    private fun withDragged(base: PixelFrame, x0: Int, y0: Int, c: Int): PixelFrame {
        val px = base.argb.copyOf()
        for (j in 0 until 2) for (i in 0 until 2) {
            for (y in y0 + j * c + 3 until y0 + (j + 1) * c - 3) for (x in x0 + i * c + 3 until x0 + (i + 1) * c - 3) px[y * base.width + x] = Px.rgb(255, 0, 255)
        }
        return PixelFrame(base.width, base.height, px)
    }

    @Test fun findsThePieceWhereItReallyIs() {
        val now = withDragged(before, 660, 840, 120) // over empty cells on the right of the board
        val f = DraggedPieceLocator.locate(before, now, square, listOf(120.0, 72.0), 720f, 1000f, 900f, 1752f, 250f, 700f)
        assertNotNull(f)
        assertTrue("left ${f!!.left}", abs(f.left - 663f) <= 15f)
        assertTrue("top ${f.top}", abs(f.top - 843f) <= 15f)
    }

    @Test fun nothingDraggedMeansNothingFound() {
        assertNull(DraggedPieceLocator.locate(before, before, square, listOf(120.0, 72.0), 720f, 1000f, 900f, 1752f, 250f, 700f))
    }

    @Test fun theVanishedTrayPieceIsNotMistakenForTheDraggedOne() {
        // slot 2's square disappears from the tray (it was picked up) but nothing appears on the board yet
        val now = SyntheticFrames.render(
            SyntheticFrames.Spec(1080, 2340, 60, 480, 120, 0b1000000L, listOf(SyntheticFrames.L_SHAPE, SyntheticFrames.BAR3, null)),
        )
        assertNull(DraggedPieceLocator.locate(before, now, square, listOf(120.0, 72.0), 720f, 1000f, 900f, 1752f, 250f, 700f))
    }

    @Test fun candidatesFarFromTheFingerAreIgnored() {
        val now = withDragged(before, 660, 840, 120)
        assertNull(DraggedPieceLocator.locate(before, now, square, listOf(120.0, 72.0), 100f, 2000f, 900f, 1752f, 250f, 300f))
    }
}
