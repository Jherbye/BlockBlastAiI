package com.blockblast.assistant.core.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LocateResult(val geometry: BoardGeometry, val confidence: Double)

/**
 * Finds the 8x8 grid without any device-specific coordinates.
 *
 * Idea: a grid of 8x8 cells produces luminance edges at 9 evenly spaced vertical and 9 evenly spaced horizontal
 * lines. We build an edge-strength profile per column, search for the (cell size, offset) whose 9 lattice lines
 * stand out most from the average, then repeat for rows with that cell size, restricted to the board's columns.
 *
 * ASSUMPTIONS (validated only on synthetic frames so far): the board is roughly horizontally centred and spans
 * 60-98% of the screen width, lies in the vertical band 10%-78% of screen height, and its cell borders produce
 * luminance edges. Real-game validation is outstanding; manual calibration is the fallback.
 */
class BoardLocator(private val downscale: Int = 2) {

    fun locate(frame: PixelFrame): LocateResult? {
        val d = downscale
        val w = frame.width / d
        val h = frame.height / d
        if (w < 80 || h < 80) return null

        val luma = IntArray(w * h)
        val area = d * d
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until d) for (dx in 0 until d) sum += frame.luma(x * d + dx, y * d + dy)
                luma[y * w + x] = sum / area
            }
        }

        val y0 = (h * 0.10).toInt()
        val y1 = (h * 0.78).toInt()
        val col = DoubleArray(w)
        for (y in y0 until y1) {
            val base = y * w
            for (x in 1 until w) {
                val diff = abs(luma[base + x] - luma[base + x - 1])
                if (diff >= EDGE_MIN) col[x] += diff.toDouble()
            }
        }
        val colCapped = capOutliers(col)
        val colLattice = bestLattice(colCapped, 0.60 * w / 8.0, 0.98 * w / 8.0, 0.25, 0, colCapped.size - 1.0) ?: return null
        val refinedCol = refine(colCapped, colLattice.cell, colLattice.offset)
        val s = refinedCol.first
        val colOffset = refinedCol.second

        val x0 = max(1, colOffset.roundToInt())
        val x1 = min(w, (colOffset + 8 * s).roundToInt())
        if (x1 - x0 < 8) return null
        val row = DoubleArray(h)
        for (y in 1 until h) {
            val base = y * w
            for (x in x0 until x1) {
                val diff = abs(luma[base + x] - luma[base - w + x])
                if (diff >= EDGE_MIN) row[y] += diff.toDouble()
            }
        }
        val rowCapped = capOutliers(row)
        val rowLattice = bestLattice(rowCapped, s, s, 0.25, (h * 0.08).toInt(), (h * 0.85).toInt().toDouble()) ?: return null
        val rowOffset = refine(rowCapped, s, rowLattice.offset).second

        val geometry = BoardGeometry(
            left = (colOffset * d).toFloat(),
            top = (rowOffset * d).toFloat(),
            cellSize = (s * d).toFloat(),
        )
        if (geometry.right > frame.width + d || geometry.bottom > frame.height + d) return null
        return LocateResult(geometry, min(colLattice.confidence, rowLattice.confidence))
    }

    private class Lattice(val cell: Double, val offset: Double, val confidence: Double)

    /** A few very strong edges (header, banner, board frame) must not outvote the regular cell borders. */
    private fun capOutliers(p: DoubleArray): DoubleArray {
        val nz = p.filter { it > 0.0 }.sorted()
        if (nz.isEmpty()) return p
        val cap = 2.0 * nz[nz.size / 2]
        return DoubleArray(p.size) { min(p[it], cap) }
    }

    /**
     * Sub-pixel refinement: take the centroid of the edge profile around each INTERIOR lattice line (the outer board
     * frame is excluded because it biases the pitch) and least-squares fit position = offset + i * cell.
     */
    private fun refine(p: DoubleArray, cell: Double, offset: Double): Pair<Double, Double> {
        val idx = ArrayList<Double>()
        val pos = ArrayList<Double>()
        for (i in 1..7) {
            val c = (offset + i * cell).roundToInt()
            val lo = max(0, c - 2)
            val hi = min(p.size - 1, c + 2)
            var wSum = 0.0
            var xSum = 0.0
            for (x in lo..hi) {
                wSum += p[x]
                xSum += x * p[x]
            }
            if (wSum <= 0.0) continue
            idx.add(i.toDouble())
            pos.add(xSum / wSum)
        }
        if (idx.size < 5) return cell to offset
        val n = idx.size.toDouble()
        val mx = idx.sum() / n
        val my = pos.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        for (k in idx.indices) {
            sxx += (idx[k] - mx) * (idx[k] - mx)
            sxy += (idx[k] - mx) * (pos[k] - my)
        }
        if (sxx <= 0.0) return cell to offset
        val slope = sxy / sxx
        return slope to (my - slope * mx)
    }

    private fun bestLattice(
        p: DoubleArray, sMin: Double, sMax: Double, sStep: Double, minOffset: Int, maxEnd: Double,
    ): Lattice? {
        val n = p.size
        val dil = DoubleArray(n) { i ->
            var m = p[i]
            if (i > 0 && p[i - 1] > m) m = p[i - 1]
            if (i < n - 1 && p[i + 1] > m) m = p[i + 1]
            m
        }
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + dil[i]

        var best: Lattice? = null
        var bestScore = 0.0
        var s = sMin
        while (s <= sMax + 1e-9) {
            val span = 8.0 * s
            val maxOffset = maxEnd - span
            var off = minOffset
            while (off <= maxOffset) {
                var sum = 0.0
                for (i in 0..8) sum += dil[(off + i * s).roundToInt()]
                val lineMean = sum / 9.0
                val b = min(n, (off + span).roundToInt())
                val allMean = (pre[b] - pre[off]) / max(1, b - off)
                val score = lineMean - allMean
                if (score > bestScore) {
                    bestScore = score
                    val ratio = lineMean / (allMean + 1e-6)
                    best = Lattice(s, off.toDouble(), ((ratio - 1.0) / 2.0).coerceIn(0.0, 1.0))
                }
                off++
            }
            s += sStep
        }
        return best
    }

    private companion object {
        const val EDGE_MIN = 8
    }
}
