package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Board
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BoardReading(
    val bits: Long,
    val confidence: Double,
    val ambiguousCells: Int,
    val threshold: Double,
    val separation: Double,
)

/**
 * Decides which cells are occupied. Feature per cell = mean luminance + mean colourfulness of the cell's centre
 * region. Empty cells are dark and muted, blocks are bright and saturated, so the 64 features split into two
 * clusters (Otsu threshold). If there is no clear split the board is treated as uniformly EMPTY, because a fully
 * occupied board cannot persist in the game (full lines clear).
 *
 * ASSUMPTION (unvalidated on real screenshots): occupied cells are clearly brighter/more colourful than empty ones.
 */
class CellClassifier {

    fun classify(frame: PixelFrame, g: BoardGeometry, sensitivity: Double = 0.5): BoardReading? {
        if (g.left < -2f || g.top < -2f || g.right > frame.width + 2f || g.bottom > frame.height + 2f) return null
        val n = Board.SIZE * Board.SIZE
        val f = DoubleArray(n)
        for (cy in 0 until Board.SIZE) for (cx in 0 until Board.SIZE) f[cy * Board.SIZE + cx] = feature(frame, g, cx, cy)

        val sorted = f.sortedArray()
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + sorted[i]
        var bestVar = -1.0
        var bestIdx = 0
        for (i in 0 until n - 1) {
            val n0 = (i + 1).toDouble()
            val n1 = (n - i - 1).toDouble()
            val m0 = prefix[i + 1] / n0
            val m1 = (prefix[n] - prefix[i + 1]) / n1
            val v = n0 * n1 * (m0 - m1) * (m0 - m1)
            if (v > bestVar) {
                bestVar = v
                bestIdx = i
            }
        }
        val n0 = (bestIdx + 1).toDouble()
        val m0 = prefix[bestIdx + 1] / n0
        val m1 = (prefix[n] - prefix[bestIdx + 1]) / (n - bestIdx - 1).toDouble()
        val separation = m1 - m0

        if (separation < MIN_SEPARATION) {
            val mean = prefix[n] / n
            val conf = if (mean < UNIFORM_BRIGHT) 0.6 else 0.3
            return BoardReading(0L, conf, 0, mean, separation)
        }
        val threshold = (sorted[bestIdx] + sorted[bestIdx + 1]) / 2.0
        val margin = 0.15 * separation * (1.5 - sensitivity.coerceIn(0.0, 1.0))
        var bits = 0L
        var ambiguous = 0
        for (i in 0 until n) {
            if (f[i] > threshold) bits = bits or (1L shl i)
            if (abs(f[i] - threshold) < margin) ambiguous++
        }
        val conf = (1.0 - min(1.0, ambiguous / 4.0)) * min(1.0, separation / 90.0)
        return BoardReading(bits, conf, ambiguous, threshold, separation)
    }

    private fun feature(frame: PixelFrame, g: BoardGeometry, cx: Int, cy: Int): Double {
        val r = g.cellSize * 0.22f
        val px = g.left + (cx + 0.5f) * g.cellSize
        val py = g.top + (cy + 0.5f) * g.cellSize
        val step = max(1, (r / 3f).toInt())
        var sumL = 0.0
        var sumS = 0.0
        var count = 0
        var y = (py - r).toInt()
        while (y <= (py + r).toInt()) {
            var x = (px - r).toInt()
            while (x <= (px + r).toInt()) {
                val p = frame.pixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                sumL += Px.luma(p)
                sumS += Px.saturation(p)
                count++
                x += step
            }
            y += step
        }
        return (sumL + sumS) / max(1, count)
    }

    private companion object {
        const val MIN_SEPARATION = 60.0
        const val UNIFORM_BRIGHT = 200.0
    }
}
