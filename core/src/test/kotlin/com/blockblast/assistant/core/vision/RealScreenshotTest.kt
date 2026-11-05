package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Regression test on a REAL gameplay screenshot. Real screenshots are not committed to the repository (privacy /
 * permission). Point the environment variable BLOCKBLAST_REAL_SCREENSHOTS at a folder containing
 * 1000077025.jpg to run it locally; otherwise the test is skipped (it is skipped in CI).
 *
 * Ground truth below was read from the screenshot by eye: chocolate/heart theme, 1080x2400.
 */
class RealScreenshotTest {
    private fun load(name: String): PixelFrame? {
        val dir = System.getenv("BLOCKBLAST_REAL_SCREENSHOTS") ?: return null
        val f = File(dir, name)
        if (!f.exists()) return null
        val img = ImageIO.read(f) ?: return null
        val px = IntArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) px[y * img.width + x] = img.getRGB(x, y)
        return PixelFrame(img.width, img.height, px)
    }

    @Test fun chocolateThemeScreenshot() {
        val frame = load("1000077025.jpg")
        assumeTrue("real screenshot not available; skipping", frame != null)
        val result = RecognitionPipeline().recognize(frame!!)
        val g = result.geometry!!
        assertTrue("left ${g.left}", abs(g.left - 61f) <= 10f)
        assertTrue("top ${g.top}", abs(g.top - 580f) <= 10f)
        assertTrue("cell ${g.cellSize}", abs(g.cellSize - 119.5f) <= 2.5f)
        val rows = listOf("....##.#", "....##.#", "....##.#", "....##.#", ".##....#", ".##.####", "...#####", "...####.")
        assertEquals(rows, com.blockblast.assistant.core.model.Board.ofBits(result.board!!.bits).toRows())
        assertEquals(
            listOf(Piece.parse("#####"), Piece.parse("#.", "##", ".#"), Piece.parse("#", "#", "#", "#")),
            result.pieceList,
        )
        assertEquals(listOf(0, 1, 2), result.slots)
        assertTrue("notes=${result.notes} conf=${result.confidence}", result.ok)
    }

    /** Lavender board, all-magenta blocks, beveled tiles (com.block.juggle), 1080x2400, ad banner. */
    @Test fun lavenderThemeScreenshot() {
        val frame = load("Screenshot_20261105_103743_com_block_juggle.jpg")
        assumeTrue("real screenshot not available; skipping", frame != null)
        val result = RecognitionPipeline().recognize(frame!!)
        val g = result.geometry!!
        assertTrue("left ${g.left}", abs(g.left - 64f) <= 10f)
        assertTrue("top ${g.top}", abs(g.top - 583f) <= 10f)
        assertTrue("cell ${g.cellSize}", abs(g.cellSize - 119.2f) <= 2.5f)
        val rows = listOf("........", "##......", "........", "###.....", "###.....", "........", "....###.", "..#.....")
        assertEquals(rows, com.blockblast.assistant.core.model.Board.ofBits(result.board!!.bits).toRows())
        assertEquals(listOf(Piece.parse("####"), Piece.parse("#####"), Piece.parse("..#", "###")), result.pieceList)
        assertEquals(listOf(0, 1, 2), result.slots)
        assertTrue("notes=${result.notes} conf=${result.confidence}", result.ok)
    }
}
