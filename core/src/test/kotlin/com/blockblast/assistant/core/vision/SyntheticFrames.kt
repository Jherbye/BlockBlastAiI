package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece

/**
 * Renders SYNTHETIC game-like frames for tests (dark gradient, board with gapped cells, three tray slots).
 * These prove the algorithms work on frames that follow our assumptions; they are NOT real screenshots and say
 * nothing about accuracy on the real game.
 */
object SyntheticFrames {
    private val BLOCK_COLORS = intArrayOf(
        Px.rgb(230, 70, 70), Px.rgb(240, 160, 40), Px.rgb(240, 210, 50),
        Px.rgb(70, 190, 90), Px.rgb(70, 130, 240), Px.rgb(160, 90, 220),
    )

    class Spec(
        val width: Int,
        val height: Int,
        val boardLeft: Int,
        val boardTop: Int,
        val cell: Int,
        val occupied: Long = 0L,
        val pieces: List<Piece?> = emptyList(), // up to 3, index = slot (null = empty slot)
        val gap: Int = 6,
        val trayScale: Double = 0.6,
        val bannerAtBottom: Boolean = true,
    )

    fun render(spec: Spec): PixelFrame {
        val w = spec.width
        val h = spec.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val c = Px.rgb((18 + 12 * t).toInt(), (24 + 16 * t).toInt(), (56 + 34 * t).toInt())
            for (x in 0 until w) px[y * w + x] = c
        }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) px[y * w + x] = color
        }
        // header bar (should be ignored by the locator's vertical band)
        fill(0, 0, w, (h * 0.07).toInt(), Px.rgb(200, 200, 220))
        val panel = Px.rgb(40, 52, 100)
        val empty = Px.rgb(52, 66, 122)
        val s = spec.cell
        fill(spec.boardLeft - 8, spec.boardTop - 8, spec.boardLeft + 8 * s + 8, spec.boardTop + 8 * s + 8, panel)
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val occ = (spec.occupied ushr (cy * 8 + cx)) and 1L == 1L
            val color = if (occ) BLOCK_COLORS[(cx + cy) % BLOCK_COLORS.size] else empty
            val x0 = spec.boardLeft + cx * s + spec.gap / 2
            val y0 = spec.boardTop + cy * s + spec.gap / 2
            fill(x0, y0, x0 + s - spec.gap, y0 + s - spec.gap, color)
        }
        val tc = (s * spec.trayScale).toInt()
        val trayCenterY = spec.boardTop + 8 * s + (2.6 * s).toInt()
        spec.pieces.forEachIndexed { slot, piece ->
            if (piece == null) return@forEachIndexed
            val cx = (w * (2 * slot + 1)) / 6
            val x0 = cx - piece.width * tc / 2
            val y0 = trayCenterY - piece.height * tc / 2
            for (c in piece.cells) {
                val color = BLOCK_COLORS[(slot * 2 + 1) % BLOCK_COLORS.size]
                fill(x0 + c.x * tc + 1, y0 + c.y * tc + 1, x0 + (c.x + 1) * tc - 1, y0 + (c.y + 1) * tc - 1, color)
            }
        }
        if (spec.bannerAtBottom) fill(0, (h * 0.93).toInt(), w, h, Px.rgb(235, 235, 235))
        return PixelFrame(w, h, px)
    }

    val STANDARD = Spec(1080, 2340, 60, 480, 120)
    val L_SHAPE: Piece = Piece.parse("#.", "#.", "##")
    val BAR3: Piece = Piece.parse("###")
    val SQUARE: Piece = Piece.parse("##", "##")
    val DOT: Piece = Piece.parse("#")
}
