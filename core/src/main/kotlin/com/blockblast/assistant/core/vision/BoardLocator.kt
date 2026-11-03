package com.blockblast.assistant.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LocateResult(val geometry: BoardGeometry, val confidence: Double)

/**
 * Finds the 8x8 grid without any device-specific coordinates, by its GAPS.
 *
 * The cell gaps form 9 evenly spaced vertical and 9 evenly spaced horizontal dark valleys in the luminance profile.
 * Averaging luminance over many rows (or columns) suppresses background texture and decals, so the valleys of the
 * 8x8 lattice stand out. Steps:
 *  1. Column profile (mean luminance over a vertical band, several bands tried) -> best (cell size, offset) lattice
 *     scored on the 7 INTERIOR lines, with a penalty if the lines one cell OUTSIDE the board are also valleys.
 *  2. Vertical placement: per-row energy of the 7 vertical gap lines is high only on board rows, so the 8-cell-tall
 *     window with the most energy fixes which rows are the board (this removes the "shifted by one cell" ambiguity).
 *  3. Exact top edge from the row profile (valley lattice, offsets restricted near step 2); sub-pixel regression.
 *
 * Validated on one real screenshot (chocolate/heart theme, 1080x2400) plus synthetic frames. Other themes and layouts
 * are untested; manual calibration is the fallback.
 */
class BoardLocator(private val downscale: Int = 2) {

    fun locate(frame: PixelFrame): LocateResult? {
        val d = downscale
        val w = frame.width / d
        val h = frame.height / d
        if (w < 80 || h < 80) return null

        val luma = DoubleArray(w * h)
        val area = (d * d).toDouble()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until d) for (dx in 0 until d) sum += frame.luma(x * d + dx, y * d + dy)
                luma[y * w + x] = sum / area
            }
        }

        // 1. columns: try several vertical bands, keep the strongest lattice
        val bandH = (0.30 * h).toInt()
        val bandStep = max(1, (0.05 * h).toInt())
        var best: Lattice? = null
        var bestTop = 0
        var top = (0.05 * h).toInt()
        while (top + bandH <= (0.75 * h).toInt()) {
            val profile = meanOverRows(luma, w, top, top + bandH)
            val r = bestValley(profile, 0.60 * w / 8.0, 0.98 * w / 8.0, 0.25, 0, w - 1.0)
            if (r != null && (best == null || r.score > best.score)) {
                best = r
                bestTop = top
            }
            top += bandStep
        }
        val colLattice = best ?: return null
        val colProfile = meanOverRows(luma, w, bestTop, bestTop + bandH)
        val (s, colOffset) = refine(colProfile, colLattice.cell, colLattice.offset)

        // 2. rows: which 8-cell-tall window holds the vertical gap lines?
        val half = 0.5 * s
        val energy = DoubleArray(h)
        for (i in 1..7) {
            val xi = colOffset + i * s
            val xa = (xi - half).roundToInt().coerceIn(0, w - 1)
            val xb = (xi + half).roundToInt().coerceIn(0, w - 1)
            val c0 = xi.roundToInt()
            val cLo = max(0, c0 - 2)
            val cHi = min(w - 1, c0 + 2)
            for (y in 0 until h) {
                val base = y * w
                var m = luma[base + c0.coerceIn(0, w - 1)]
                for (x in cLo..cHi) if (luma[base + x] < m) m = luma[base + x]
                energy[y] += (0.5 * (luma[base + xa] + luma[base + xb]) - m).coerceIn(0.0, 60.0)
            }
        }
        val prefix = DoubleArray(h + 1)
        for (y in 0 until h) prefix[y + 1] = prefix[y] + energy[y] / 7.0
        val span = (8 * s).roundToInt()
        val lo = (0.04 * h).toInt()
        val hi = (0.95 * h).toInt() - span
        if (hi < lo) return null
        var off0 = lo
        var bestSum = -1.0
        for (o in lo..hi) {
            val sum = prefix[o + span] - prefix[o]
            if (sum > bestSum) {
                bestSum = sum
                off0 = o
            }
        }

        // 3. exact top edge from the row profile, close to off0
        val x0 = max(0, (colOffset + s).roundToInt())
        val x1 = min(w, (colOffset + 7 * s).roundToInt())
        if (x1 - x0 < 8) return null
        val rowProfile = DoubleArray(h)
        for (y in 0 until h) {
            var sum = 0.0
            for (x in x0 until x1) sum += luma[y * w + x]
            rowProfile[y] = sum / (x1 - x0)
        }
        val rowLattice = bestValley(
            rowProfile, s, s, 0.25,
            max(lo, (off0 - s / 3.0).toInt()),
            min((0.95 * h).toInt().toDouble(), off0 + s / 3.0 + span),
        ) ?: return null
        val rowOffset = refine(rowProfile, s, rowLattice.offset).second

        val geometry = BoardGeometry(
            left = (colOffset * d).toFloat(),
            top = (rowOffset * d).toFloat(),
            cellSize = (s * d).toFloat(),
        )
        if (geometry.left < -d || geometry.top < -d || geometry.right > frame.width + d || geometry.bottom > frame.height + d) return null
        val strength = (min(colLattice.score, rowLattice.score) / 20.0).coerceIn(0.0, 1.0)
        return LocateResult(geometry, strength * min(colLattice.fraction, rowLattice.fraction))
    }

    private class Lattice(val cell: Double, val offset: Double, val score: Double, val fraction: Double)

    private fun meanOverRows(luma: DoubleArray, w: Int, from: Int, to: Int): DoubleArray {
        val out = DoubleArray(w)
        for (y in from until to) {
            val base = y * w
            for (x in 0 until w) out[x] += luma[base + x]
        }
        val n = (to - from).toDouble()
        for (x in 0 until w) out[x] /= n
        return out
    }

    private fun minFilter(p: DoubleArray, r: Int = 2): DoubleArray {
        val n = p.size
        return DoubleArray(n) { i ->
            var m = p[i]
            for (k in max(0, i - r)..min(n - 1, i + r)) if (p[k] < m) m = p[k]
            m
        }
    }

    /** How much darker the valley at [pos] is than the cell interiors half a cell to each side. */
    private fun depth(p: DoubleArray, pm: DoubleArray, pos: Double, half: Double): Double {
        val n = p.size
        val a = (pos - half).roundToInt().coerceIn(0, n - 1)
        val b = (pos + half).roundToInt().coerceIn(0, n - 1)
        val c = pos.roundToInt().coerceIn(0, n - 1)
        return 0.5 * (p[a] + p[b]) - pm[c]
    }

    private fun bestValley(
        p: DoubleArray, sMin: Double, sMax: Double, sStep: Double, minOff: Int, maxEnd: Double,
    ): Lattice? {
        val pm = minFilter(p)
        var best: Lattice? = null
        var bestScore = -1.0
        var s = sMin
        while (s <= sMax + 1e-9) {
            val half = 0.5 * s
            val hiOff = (maxEnd - 8.0 * s).toInt()
            var off = minOff
            while (off <= hiOff) {
                var tot = 0.0
                var cnt = 0
                for (i in 1..7) {
                    val dep = depth(p, pm, off + i * s, half)
                    tot += dep.coerceIn(0.0, 60.0)
                    if (dep > 5.0) cnt++
                }
                var penalty = 0.0
                for (i in intArrayOf(-1, 9)) penalty += 0.5 * depth(p, pm, off + i * s, half).coerceIn(0.0, 60.0)
                val score = tot / 7.0 * (cnt / 7.0) - penalty
                if (score > bestScore) {
                    bestScore = score
                    best = Lattice(s, off.toDouble(), score, cnt / 7.0)
                }
                off++
            }
            s += sStep
        }
        return best
    }

    /** Sub-pixel: centroid of each interior valley, then least-squares position = offset + i * cell. */
    private fun refine(p: DoubleArray, cell: Double, offset: Double): Pair<Double, Double> {
        val n = p.size
        val idx = ArrayList<Double>()
        val pos = ArrayList<Double>()
        val half = 0.5 * cell
        for (i in 1..7) {
            val c = (offset + i * cell).roundToInt()
            val lo = max(0, c - 3)
            val hi = min(n - 1, c + 3)
            val a = (offset + i * cell - half).roundToInt().coerceIn(0, n - 1)
            val b = (offset + i * cell + half).roundToInt().coerceIn(0, n - 1)
            val base = 0.5 * (p[a] + p[b])
            var wSum = 0.0
            var xSum = 0.0
            for (x in lo..hi) {
                val wgt = max(0.0, base - p[x])
                wSum += wgt
                xSum += x * wgt
            }
            if (wSum <= 0.0) continue
            idx.add(i.toDouble())
            pos.add(xSum / wSum)
        }
        if (idx.size < 5) return cell to offset
        val count = idx.size.toDouble()
        val mx = idx.sum() / count
        val my = pos.sum() / count
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
}
