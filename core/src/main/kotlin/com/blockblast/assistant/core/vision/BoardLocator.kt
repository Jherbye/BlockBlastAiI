package com.blockblast.assistant.core.vision

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LocateResult(val geometry: BoardGeometry, val confidence: Double)

/**
 * Theme-independent board detection from grid GEOMETRY only (no colours, textures or fixed coordinates).
 *
 * Cell gaps form 9 evenly spaced lines in each direction. A gap is a NARROW local dip (dark gaps) or bump (light gaps)
 * in luminance, so everything is expressed relative to the profile's own dynamic range (brightness / contrast /
 * resolution independent), and both polarities are tried.
 *  1. Column profiles over several vertical bands -> cell size and grid phase from the periodicity of narrow dips.
 *  2. Which 8 rows? Energy of ALL vertical gap lines per screen row; the 8-cell window with most energy is the board.
 *  3. Exact top from the row profile (restricted near step 2); which 8 columns? Energy of the horizontal gap lines per
 *     screen column, same window logic; exact left from the column profile.
 * Confidence combines dip strength, the fraction of lines that are real dips, and inside/outside energy contrast.
 *
 * Known limits: gradient-shaded tiles bias the grid by up to ~0.1 cell; very low gap contrast fails (-> low
 * confidence -> manual calibration). Validated on one real screenshot and on procedurally generated themes only.
 */
class BoardLocator(private val downscale: Int = 2) {

    private class Scan(val cell: Double, val offset: Double, val score: Double, val fraction: Double)

    fun locate(frame: PixelFrame): LocateResult? {
        val d = downscale
        val w = frame.width / d
        val h = frame.height / d
        if (w < 80 || h < 80) return null
        val luma = DoubleArray(w * h)
        val area = (d * d).toDouble()
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until d) for (dx in 0 until d) sum += frame.luma(x * d + dx, y * d + dy)
            luma[y * w + x] = sum / area
        }

        // 1. cell size and phase
        val bandH = (0.30 * h).toInt()
        val bandStep = max(1, (0.05 * h).toInt())
        var best: Scan? = null
        var bestTop = 0
        var bestPol = 1
        var bestLo = 0.0
        var bestR = 1.0
        var top = (0.05 * h).toInt()
        while (top + bandH <= (0.75 * h).toInt()) {
            val prof = meanOverRows(luma, w, top, top + bandH)
            val (lo, r) = range(prof)
            for (pol in intArrayOf(1, -1)) {
                val q = DoubleArray(w) { val v = (prof[it] - lo) / r * 100.0; if (pol > 0) v else 100.0 - v }
                val res = valleyScan(q, 0.60 * w / 8.0, 0.98 * w / 8.0, 0.25, 0, w - 1.0)
                if (res != null && (best == null || res.score > best.score)) {
                    best = res; bestTop = top; bestPol = pol; bestLo = lo; bestR = r
                }
            }
            top += bandStep
        }
        val first = best ?: return null
        val prof0 = meanOverRows(luma, w, bestTop, bestTop + bandH)
        val q0 = DoubleArray(w) { val v = (prof0[it] - bestLo) / bestR * 100.0; if (bestPol > 0) v else 100.0 - v }
        val (s, off) = refine(q0, first.cell, first.offset)
        val phase = off - floor(off / s) * s

        val norm = DoubleArray(w * h) { val v = (luma[it] - bestLo) / bestR * 100.0; if (bestPol > 0) v else 100.0 - v }
        val hh = 0.2 * s
        val span = (8 * s).roundToInt()

        // 2. which 8 rows
        val xs = ArrayList<Double>()
        var k = 0
        while (phase + k * s <= w) {
            val pos = phase + k * s
            if (pos >= 0.5 * s && pos <= w - 0.5 * s) xs.add(pos)
            k++
        }
        val rowEnergy = DoubleArray(h)
        for (xi in xs) {
            val xa = (xi - hh).roundToInt().coerceIn(0, w - 1)
            val xb = (xi + hh).roundToInt().coerceIn(0, w - 1)
            val c0 = xi.roundToInt()
            for (y in 0 until h) {
                val base = y * w
                var m = Double.MAX_VALUE
                for (x in max(0, c0 - 2)..min(w - 1, c0 + 2)) if (norm[base + x] < m) m = norm[base + x]
                rowEnergy[y] += (min(norm[base + xa], norm[base + xb]) - m).coerceIn(0.0, 40.0)
            }
        }
        if (xs.isNotEmpty()) for (y in 0 until h) rowEnergy[y] /= xs.size
        val wy = bestWindow(rowEnergy, span, (0.02 * h).toInt(), (0.97 * h).toInt() - span) ?: return null

        // 3a. exact top
        val rowProf = DoubleArray(h) { y -> var sum = 0.0; for (x in 0 until w) sum += norm[y * w + x]; sum / w }
        val rowScan = valleyScan(
            rowProf, s, s, 0.25,
            max((0.02 * h).toInt(), (wy.index - s / 3.0).toInt()),
            min((0.97 * h).toInt(), (wy.index + s / 3.0 + span).toInt()).toDouble(),
        ) ?: return null
        val rowOffset = refine(rowProf, s, rowScan.offset).second

        // 3b. which 8 columns, exact left
        val colEnergy = DoubleArray(w)
        for (j in 1..7) {
            val yi = rowOffset + j * s
            val ya = (yi - hh).roundToInt().coerceIn(0, h - 1)
            val yb = (yi + hh).roundToInt().coerceIn(0, h - 1)
            val c0 = yi.roundToInt()
            for (x in 0 until w) {
                var m = Double.MAX_VALUE
                for (y in max(0, c0 - 2)..min(h - 1, c0 + 2)) if (norm[y * w + x] < m) m = norm[y * w + x]
                colEnergy[x] += (min(norm[ya * w + x], norm[yb * w + x]) - m).coerceIn(0.0, 40.0)
            }
        }
        for (x in 0 until w) colEnergy[x] /= 7.0
        val wx = bestWindow(colEnergy, span, 0, w - span) ?: return null
        val kx = ((wx.index - phase) / s).roundToInt()
        val leftGuess = phase + kx * s
        val y0 = rowOffset.toInt().coerceIn(0, h - 1)
        val y1 = (rowOffset + 8 * s).toInt().coerceIn(y0 + 1, h)
        val colProf = meanOverRows(norm, w, y0, y1)
        val colScan = valleyScan(
            colProf, s, s, 0.25,
            max(0, (leftGuess - s / 3.0).toInt()),
            min(w - 1, (leftGuess + s / 3.0 + span).toInt()).toDouble(),
        )
        val left = if (colScan != null) refine(colProf, s, colScan.offset).second else leftGuess

        val contrast = min(wy.inside / (wy.outside + 2.0), wx.inside / (wx.outside + 2.0))
        val conf = min(1.0, min(first.score, rowScan.score) / 12.0) *
            min(first.fraction, rowScan.fraction) * min(1.0, contrast / 3.0)
        val geometry = BoardGeometry((left * d).toFloat(), (rowOffset * d).toFloat(), (s * d).toFloat())
        if (geometry.left < -d || geometry.top < -d || geometry.right > frame.width + d || geometry.bottom > frame.height + d) return null
        return LocateResult(geometry, conf.coerceIn(0.0, 1.0))
    }

    private class Window(val index: Int, val inside: Double, val outside: Double)

    private fun bestWindow(e: DoubleArray, span: Int, lo: Int, hi: Int): Window? {
        if (hi < lo || span <= 0) return null
        val pre = DoubleArray(e.size + 1)
        for (i in e.indices) pre[i + 1] = pre[i] + e[i]
        var bestK = lo
        var bestSum = -1.0
        for (o in lo..hi) {
            val sum = pre[o + span] - pre[o]
            if (sum > bestSum) { bestSum = sum; bestK = o }
        }
        return Window(bestK, bestSum / span, (pre[e.size] - bestSum) / max(1, e.size - span))
    }

    private fun meanOverRows(a: DoubleArray, w: Int, from: Int, to: Int): DoubleArray {
        val out = DoubleArray(w)
        for (y in from until to) for (x in 0 until w) out[x] += a[y * w + x]
        val n = max(1, to - from).toDouble()
        for (x in 0 until w) out[x] /= n
        return out
    }

    /** Robust dynamic range (5th..95th percentile, at least 6 luma units). */
    private fun range(p: DoubleArray): Pair<Double, Double> {
        val s = p.sortedArray()
        val lo = s[((s.size - 1) * 0.05).toInt()]
        val hi = s[((s.size - 1) * 0.95).toInt()]
        return lo to max(hi - lo, 6.0)
    }

    private fun minFilter(p: DoubleArray, r: Int = 2): DoubleArray {
        val n = p.size
        return DoubleArray(n) { i ->
            var m = p[i]
            for (j in max(0, i - r)..min(n - 1, i + r)) if (p[j] < m) m = p[j]
            m
        }
    }

    /** Depth of a NARROW dip at [pos]: lower of the values 0.2 cell to each side minus the local minimum. */
    private fun depth(p: DoubleArray, pm: DoubleArray, pos: Double, s: Double): Double {
        val n = p.size
        val a = (pos - 0.2 * s).roundToInt().coerceIn(0, n - 1)
        val b = (pos + 0.2 * s).roundToInt().coerceIn(0, n - 1)
        val c = pos.roundToInt().coerceIn(0, n - 1)
        return min(p[a], p[b]) - pm[c]
    }

    private fun valleyScan(p: DoubleArray, sMin: Double, sMax: Double, sStep: Double, minOff: Int, maxEnd: Double): Scan? {
        val pm = minFilter(p)
        var best: Scan? = null
        var bestScore = -1e18
        val depths = DoubleArray(7)
        var s = sMin
        while (s <= sMax + 1e-9) {
            val hiOff = (maxEnd - 8.0 * s).toInt()
            var off = minOff
            while (off <= hiOff) {
                var cnt = 0
                for (i in 1..7) {
                    val dep = depth(p, pm, off + i * s, s).coerceIn(0.0, 40.0)
                    depths[i - 1] = dep
                    if (dep > 4.0) cnt++
                }
                depths.sort()
                val score = depths[3] * (cnt / 7.0)
                if (score > bestScore) {
                    bestScore = score
                    best = Scan(s, off.toDouble(), score, cnt / 7.0)
                }
                off++
            }
            s += sStep
        }
        return best
    }

    /** Sub-pixel: centroid of each interior dip, then least squares position = offset + i * cell. */
    private fun refine(p: DoubleArray, cell: Double, offset: Double): Pair<Double, Double> {
        val n = p.size
        val idx = ArrayList<Double>()
        val pos = ArrayList<Double>()
        for (i in 1..7) {
            val c = (offset + i * cell).roundToInt()
            val lo = max(0, c - 3)
            val hi = min(n - 1, c + 3)
            val a = (offset + i * cell - 0.2 * cell).roundToInt().coerceIn(0, n - 1)
            val b = (offset + i * cell + 0.2 * cell).roundToInt().coerceIn(0, n - 1)
            val base = min(p[a], p[b])
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
        val cnt = idx.size.toDouble()
        val mx = idx.sum() / cnt
        val my = pos.sum() / cnt
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
