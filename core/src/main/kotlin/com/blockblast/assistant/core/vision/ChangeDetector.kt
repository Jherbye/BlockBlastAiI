package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Board
import kotlin.math.abs
import kotlin.math.min

class FrameBaseline(
    val xs: IntArray,
    val ys: IntArray,
    val values: IntArray,
    val excluded: BooleanArray,
)

/**
 * Cheap "did the screen change?" test used between full scans. Samples a few points per board cell and a coarse grid
 * over the tray. Points under our own overlay (highlight cells, control panel) are excluded so drawing a
 * recommendation does not look like a game change.
 */
class ChangeDetector(private val colorTolerance: Int = 60) {

    fun baseline(frame: PixelFrame, g: BoardGeometry, excludedRects: List<FloatRect>): FrameBaseline {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val s = g.cellSize
        for (cy in 0 until Board.SIZE) for (cx in 0 until Board.SIZE) {
            val px = g.left + (cx + 0.5f) * s
            val py = g.top + (cy + 0.5f) * s
            val d = 0.25f * s
            for ((ox, oy) in listOf(0f to 0f, -d to -d, d to -d, -d to d, d to d)) {
                xs.add((px + ox).toInt().coerceIn(0, frame.width - 1))
                ys.add((py + oy).toInt().coerceIn(0, frame.height - 1))
            }
        }
        val top = g.bottom + 0.4f * s
        val bottom = min(frame.height.toFloat(), g.bottom + 6.2f * s)
        val cols = 48
        val rows = 20
        for (r in 0 until rows) for (c in 0 until cols) {
            xs.add(((c + 0.5f) * frame.width / cols).toInt().coerceIn(0, frame.width - 1))
            ys.add((top + (r + 0.5f) * (bottom - top) / rows).toInt().coerceIn(0, frame.height - 1))
        }
        val xa = xs.toIntArray()
        val ya = ys.toIntArray()
        val values = IntArray(xa.size) { frame.pixel(xa[it], ya[it]) and 0xFFFFFF }
        val excluded = BooleanArray(xa.size) { i -> excludedRects.any { it.contains(xa[i].toFloat(), ya[i].toFloat()) } }
        return FrameBaseline(xa, ya, values, excluded)
    }

    /** Number of non-excluded sample points whose colour changed noticeably since [base] was taken. */
    fun changedCount(frame: PixelFrame, base: FrameBaseline): Int {
        var changed = 0
        for (i in base.xs.indices) {
            if (base.excluded[i]) continue
            val now = frame.pixel(base.xs[i].coerceIn(0, frame.width - 1), base.ys[i].coerceIn(0, frame.height - 1))
            val old = base.values[i]
            val diff = abs(Px.r(now) - Px.r(old)) + abs(Px.g(now) - Px.g(old)) + abs(Px.b(now) - Px.b(old))
            if (diff > colorTolerance) changed++
        }
        return changed
    }
}
