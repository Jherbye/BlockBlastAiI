package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.vision.BoardGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Evidence behind a detected grid; every field is a separate signal that feeds [confidence]. */
data class GridEvidence(
    val geometry: BoardGeometry,
    val confidence: Double,
    /** Interior lattice lines (of 7) whose edge energy clearly exceeds the energy between lines, per axis. */
    val supportX: Int,
    val supportY: Int,
    /** Edge-energy contrast of lattice lines vs. mid-cell positions, 0..1. */
    val contrast: Double,
    /** 1 = identical horizontal and vertical pitch, 0 = >5% different. */
    val pitchAgreement: Double,
    /** Whether the periodicity (autocorrelation) hint agrees with the lattice pitch. */
    val periodicityAgrees: Boolean,
    /** Lattice strength inside the board relative to one cell outside (1 = lattice stops at the board). */
    val extentScore: Double,
    val notes: List<String>,
)

/**
 * Finds the 8x8 board from GEOMETRY ONLY: a periodic lattice of edge energy with pitch s. It does not look at colours,
 * so it works with dark or light gaps, outlines, bevels, gradients or textured backgrounds. A lattice line is any
 * place where edge energy per pixel is clearly higher than half a cell away, regardless of edge polarity.
 *
 * Signals combined: (1) comb score of the column edge profile, (2) per-row lattice energy fixing the board's rows,
 * (3) comb score of the row edge profile, (4) sub-pixel regression of line centroids on both axes with a shared pitch,
 * (5) pitch agreement between axes, (6) inside/outside extent test, (7) autocorrelation periodicity hint.
 */
class GridDetector(
    private val minBoardFraction: Double = 0.55,
    private val maxBoardFraction: Double = 0.99,
) {
    private class Comb(val pitch: Double, val offset: Double, val score: Double, val support: Int, val contrast: Double)

    fun detect(img: LabImage): GridEvidence? {
        val w = img.width
        val h = img.height
        if (w < 80 || h < 80) return null
        val sMin = minBoardFraction * w / 8.0
        val sMax = maxBoardFraction * w / 8.0

        // 1. column lattice: slide a band over the frame, keep the best comb
        val bandH = max(8, (0.26 * h).toInt())
        val step = max(1, (0.04 * h).toInt())
        var best: Comb? = null
        var bestTop = 0
        var top = (0.03 * h).toInt()
        while (top + bandH <= (0.88 * h).toInt()) {
            val p = colProfile(img, top, top + bandH)
            val c = bestComb(p, sMin, sMax, 0.2, -0.02, w.toDouble(), null, null)
            if (c != null && (best == null || c.score > best.score)) {
                best = c
                bestTop = top
            }
            top += step
        }
        val colComb = best ?: return null
        if (colComb.score <= 0.0) return null
        val colProf = colProfile(img, bestTop, bestTop + bandH)
        val notes = ArrayList<String>()

        // 2. which rows hold the vertical lattice lines?
        val s0 = colComb.pitch
        val ox0 = colComb.offset
        val rowEnergy = rowLatticeEnergy(img, s0, ox0)
        val span = (8 * s0).toInt()
        val lo = 0
        val hi = h - span
        if (hi < lo) return null
        val prefix = DoubleArray(h + 1)
        for (y in 0 until h) prefix[y + 1] = prefix[y] + rowEnergy[y]
        var off0 = lo
        var bestSum = -1.0
        for (o in lo..hi) {
            val sum = prefix[o + span] - prefix[o]
            if (sum > bestSum) {
                bestSum = sum
                off0 = o
            }
        }

        // 3. row lattice near off0 (pitch may differ by up to 4% from the column pitch)
        val x0 = ox0 + s0
        val x1 = ox0 + 7 * s0
        val rowProf = rowProfile(img, x0, x1)
        val rowComb = bestComb(rowProf, s0 * 0.96, s0 * 1.04, 0.1, off0 - 0.6 * s0, min(h.toDouble(), off0 + 0.6 * s0 + 8.0 * s0 * 1.04), off0 - 0.6 * s0, off0 + 0.6 * s0)
            ?: return null
        if (rowComb.score <= 0.0) return null

        // 4. sub-pixel regression, common pitch
        val fx = fitLines(colProf, s0, ox0)
        val fy = fitLines(rowProf, rowComb.pitch, rowComb.offset)
        val sx = fx?.pitch ?: s0
        val sy = fy?.pitch ?: rowComb.pitch
        val nx = fx?.n ?: 0
        val ny = fy?.n ?: 0
        val pitchDiff = abs(sx - sy) / ((sx + sy) / 2)
        val agreement = (1.0 - (pitchDiff - 0.01) / 0.04).coerceIn(0.0, 1.0)
        val s = if (nx + ny > 0) (sx * nx + sy * ny) / (nx + ny).toDouble() else (s0 + rowComb.pitch) / 2
        val ox = fx?.let { meanOffset(it, s) } ?: ox0
        val oy = fy?.let { meanOffset(it, s) } ?: rowComb.offset
        if (pitchDiff > 0.05) notes.add("horizontal/vertical pitch disagree by ${"%.1f".format(pitchDiff * 100)}%")

        val d = img.scale
        val geometry = BoardGeometry((ox * d).toFloat(), (oy * d).toFloat(), (s * d).toFloat())
        if (geometry.left < -2 * d || geometry.top < -2 * d || geometry.right > img.width * d + 2 * d || geometry.bottom > img.height * d + 2 * d) {
            return null
        }

        // 5. extent: does the lattice stop at the board?
        val extent = extentScore(img, s, ox, oy)
        // 6. periodicity hint
        val periodic = periodicityAgrees(colProf, s)
        if (!periodic) notes.add("periodicity hint disagrees with lattice pitch")

        val strength = (min(colComb.score, rowComb.score) / 0.45).coerceIn(0.0, 1.0)
        val support = min(colComb.support, rowComb.support) / 7.0
        val contrastN = (min(colComb.contrast, rowComb.contrast) / 0.35).coerceIn(0.0, 1.0)
        var conf = (0.45 * strength + 0.25 * support + 0.15 * agreement + 0.15 * contrastN) * (0.7 + 0.3 * extent)
        if (!periodic) conf *= 0.85
        if (strength < 0.15) notes.add("lattice is very faint")
        return GridEvidence(
            geometry, conf.coerceIn(0.0, 1.0), colComb.support, rowComb.support,
            min(colComb.contrast, rowComb.contrast), agreement, periodic, extent, notes,
        )
    }

    // ---- profiles ------------------------------------------------------------------------------------------------

    private fun colProfile(img: LabImage, y0: Int, y1: Int): DoubleArray {
        val n = (y1 - y0).toDouble()
        return DoubleArray(img.width) { x -> img.iGX.sum(x, y0, x + 1, y1) / n }
    }

    private fun rowProfile(img: LabImage, xa: Double, xb: Double): DoubleArray {
        val a = max(0, xa.toInt())
        val b = min(img.width, xb.toInt())
        val n = max(1, b - a).toDouble()
        return DoubleArray(img.height) { y -> img.iGY.sum(a, y, b, y + 1) / n }
    }

    /** Per row: how much more edge energy sits on the 7 interior vertical lattice lines than between them. */
    private fun rowLatticeEnergy(img: LabImage, s: Double, o: Double): DoubleArray {
        val hw = max(1.0, 0.11 * s)
        val mhw = max(1.0, 0.16 * s)
        val out = DoubleArray(img.height)
        for (y in 0 until img.height) {
            var tot = 0.0
            for (i in 1..7) {
                val c = o + i * s
                val line = win(img, c - hw, c + hw, y) / (2 * hw)
                val m1 = o + (i - 0.5) * s
                val m2 = o + (i + 0.5) * s
                val ref = 0.5 * (win(img, m1 - mhw, m1 + mhw, y) / (2 * mhw) + win(img, m2 - mhw, m2 + mhw, y) / (2 * mhw))
                tot += (line - ref).coerceIn(0.0, 20.0)
            }
            out[y] = tot / 7.0
        }
        return out
    }

    private fun win(img: LabImage, a: Double, b: Double, y: Int): Double =
        img.iGX.sum(a.toInt(), y, b.toInt().coerceAtLeast(a.toInt() + 1), y + 1)

    // ---- comb search ---------------------------------------------------------------------------------------------

    private fun bestComb(
        p: DoubleArray, sMin: Double, sMax: Double, sStep: Double, minOff: Double, maxEnd: Double,
        offLo: Double?, offHi: Double?,
    ): Comb? {
        val n = p.size
        val c = DoubleArray(n + 1)
        for (i in 0 until n) c[i + 1] = c[i] + p[i]
        var best: Comb? = null
        var s = sMin
        while (s <= sMax + 1e-9) {
            val lo = offLo ?: minOff
            val hi = min(offHi ?: Double.MAX_VALUE, maxEnd - 8.0 * s)
            var off = lo
            while (off <= hi) {
                val r = combScore(p, c, s, off)
                if (r != null && (best == null || r.score > best.score)) best = r
                off += 1.0
            }
            s += sStep
        }
        return best
    }

    private fun cum(c: DoubleArray, p: DoubleArray, u: Double): Double {
        val n = p.size
        if (u <= 0) return 0.0
        if (u >= n) return c[n]
        val i = u.toInt()
        return c[i] + (u - i) * p[i]
    }

    private fun density(c: DoubleArray, p: DoubleArray, centre: Double, half: Double): Double =
        (cum(c, p, centre + half) - cum(c, p, centre - half)) / (2 * half)

    private fun combScore(p: DoubleArray, c: DoubleArray, s: Double, o: Double): Comb? {
        val n = p.size
        if (o < -0.02 * s || o + 8 * s > n + 0.02 * s) return null
        val hw = max(1.0, 0.11 * s)
        val mhw = max(1.0, 0.16 * s)
        val mid = DoubleArray(8) { density(c, p, o + (it + 0.5) * s, mhw) }
        var num = 0.0
        var den = 0.0
        var support = 0
        for (i in 1..7) {
            val line = density(c, p, o + i * s, hw)
            val ref = 0.5 * (mid[i - 1] + mid[i])
            num += max(0.0, line - ref)
            den += line + ref
            if (line > 1.3 * ref + FLOOR) support++
        }
        den += 7 * FLOOR
        val contrast = num / den
        // penalty if the lattice continues one cell outside the board on either side
        var pen = 0.0
        for (i in intArrayOf(-1, 9)) {
            val cc = o + i * s
            if (cc - hw < 0 || cc + hw > n) continue
            val line = density(c, p, cc, hw)
            val ref = if (i < 0) mid[0] else mid[7]
            pen += 0.25 * max(0.0, line - ref) / (den / 7)
        }
        val abs = num / 7.0
        if (abs < MIN_ABS_CONTRAST) return Comb(s, o, 0.0, support, contrast)
        val score = contrast * (support / 7.0) - pen
        return Comb(s, o, score, support, contrast)
    }

    // ---- sub-pixel regression ------------------------------------------------------------------------------------

    private class LineFit(val pitch: Double, val intercept: Double, val n: Int, val idx: IntArray, val pos: DoubleArray)

    private fun meanOffset(f: LineFit, s: Double): Double {
        var sum = 0.0
        for (k in f.idx.indices) sum += f.pos[k] - f.idx[k] * s
        return sum / f.idx.size
    }

    private fun fitLines(p: DoubleArray, s: Double, o: Double): LineFit? {
        val n = p.size
        val idx = ArrayList<Int>()
        val pos = ArrayList<Double>()
        val half = 0.2 * s
        val c = DoubleArray(n + 1)
        for (i in 0 until n) c[i + 1] = c[i] + p[i]
        for (i in 0..8) {
            val centre = o + i * s
            val a = max(0, (centre - half).toInt())
            val b = min(n - 1, (centre + half).toInt())
            if (b - a < 2) continue
            val refL = density(c, p, o + (i - 0.5) * s, 0.16 * s)
            val refR = density(c, p, o + (i + 0.5) * s, 0.16 * s)
            val base = 0.5 * (refL + refR)
            var wSum = 0.0
            var xSum = 0.0
            for (x in a..b) {
                val wgt = max(0.0, p[x] - base)
                wSum += wgt
                xSum += (x + 0.5) * wgt
            }
            if (wSum < (b - a + 1) * (base * 0.3 + FLOOR)) continue
            idx.add(i)
            pos.add(xSum / wSum)
        }
        if (idx.size < 5) return null
        var ii = idx.toIntArray()
        var pp = pos.toDoubleArray()
        var slope = s
        var icpt = o
        repeat(2) {
            val m = ii.size.toDouble()
            val mx = ii.sum() / m
            val my = pp.sum() / m
            var sxx = 0.0
            var sxy = 0.0
            for (k in ii.indices) {
                sxx += (ii[k] - mx) * (ii[k] - mx)
                sxy += (ii[k] - mx) * (pp[k] - my)
            }
            if (sxx <= 0.0) return null
            slope = sxy / sxx
            icpt = my - slope * mx
            val keep = ii.indices.filter { abs(pp[it] - (icpt + slope * ii[it])) < 0.06 * s }
            if (keep.size in 5 until ii.size) {
                ii = keep.map { ii[it] }.toIntArray()
                pp = keep.map { pp[it] }.toDoubleArray()
            }
        }
        if (abs(slope - s) > 0.05 * s) return null
        return LineFit(slope, icpt, ii.size, ii, pp)
    }

    // ---- extent and periodicity ----------------------------------------------------------------------------------

    /** 1 when the lattice is present inside the board but not one cell beyond its top/bottom edge. */
    private fun extentScore(img: LabImage, s: Double, ox: Double, oy: Double): Double {
        fun latticeAt(yTop: Double): Double {
            val y0 = yTop.toInt().coerceIn(0, img.height - 1)
            val y1 = (yTop + 0.8 * s).toInt().coerceIn(y0 + 1, img.height)
            val hw = max(1.0, 0.11 * s)
            val mhw = max(1.0, 0.16 * s)
            var tot = 0.0
            for (i in 1..7) {
                val cx = ox + i * s
                val line = img.iGX.sum((cx - hw).toInt(), y0, (cx + hw).toInt(), y1) / (2 * hw * (y1 - y0))
                val m = ox + (i + 0.5) * s
                val ref = img.iGX.sum((m - mhw).toInt(), y0, (m + mhw).toInt(), y1) / (2 * mhw * (y1 - y0))
                tot += max(0.0, line - ref)
            }
            return tot / 7
        }
        val inside = latticeAt(oy + 0.1 * s)
        val above = if (oy - s > 0) latticeAt(oy - 0.9 * s) else 0.0
        val below = if (oy + 8 * s + 0.1 * s + 0.8 * s < img.height) latticeAt(oy + 8.1 * s) else 0.0
        if (inside <= 1e-6) return 0.0
        val outside = max(above, below)
        return (1.0 - outside / inside).coerceIn(0.0, 1.0)
    }

    private fun periodicityAgrees(p: DoubleArray, s: Double): Boolean {
        val n = p.size
        val mean = p.average()
        val z = DoubleArray(n) { p[it] - mean }
        fun ac(lag: Int): Double {
            var sum = 0.0
            for (i in 0 until n - lag) sum += z[i] * z[i + lag]
            return sum / (n - lag)
        }
        val lagLo = max(2, (0.4 * s).toInt())
        val lagHi = min(n / 2, (2.4 * s).toInt())
        if (lagHi <= lagLo) return true
        var bestLag = lagLo
        var bestVal = -Double.MAX_VALUE
        for (l in lagLo..lagHi) {
            val v = ac(l)
            if (v > bestVal) {
                bestVal = v
                bestLag = l
            }
        }
        val r = bestLag / s
        return r in 0.9..1.1 || r in 1.9..2.1
    }

    private companion object {
        const val FLOOR = 0.15
        const val MIN_ABS_CONTRAST = 0.12
    }
}
