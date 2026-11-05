package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Board
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class BoardReading(
    val bits: Long,
    /** Confidence per cell, index = y * 8 + x. */
    val cellConfidence: DoubleArray,
    /** Second-lowest cell confidence: one doubtful cell may be tolerated by the UI, two may not. */
    val confidence: Double,
    val notes: List<String>,
)

/**
 * Theme-independent occupancy reading. No colour, texture or background is hard-coded.
 *
 * Per cell: mean RGB, texture (luminance std), edge density, and ring contrast (inner area vs the cell's outer rim).
 * Idea: cells that are EMPTY share one dominant "look" (whatever the theme); occupied cells are the ones that differ
 * from it in colour, texture, edge or outline. Steps:
 *  1. Find up to three dominant looks (clusters of near-identical cells, distances in units of a "noticeable" difference).
 *  2. Decide which look is the empty one from independent cues: closeness to the grid-gap colour, low texture,
 *     distance from the colours of the tray pieces (which are always blocks), cluster size, and a learned empty look
 *     from earlier confirmed scans / manual corrections. A weak winner lowers confidence.
 *  3. Every other cell is occupied; per-cell confidence is its margin from the decision threshold.
 * A board whose cells all share one look is read as EMPTY only if the cues agree (full boards cannot persist in the
 * game); otherwise it is reported as ambiguous with low confidence. It never silently guesses.
 */
class CellReader {

    /** Per-cell feature vector [R, G, B, texture, edge, ring] for the 64 cells. */
    fun features(frame: PixelFrame, g: BoardGeometry): Array<DoubleArray> {
        val s = g.cellSize.toDouble()
        val out = Array(64) { DoubleArray(6) }
        val st = max(1, (0.28 * s / 5.0).toInt())
        val ri = 0.36 * s
        val ro = 0.5 * s * 0.97
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val px = g.left + (cx + 0.5) * s
            val py = g.top + (cy + 0.5) * s
            val r = 0.28 * s
            val xs = ArrayList<Int>()
            var x = (px - r).toInt()
            while (x <= (px + r).toInt()) { xs.add(x.coerceIn(0, frame.width - 1)); x += st }
            val ys = ArrayList<Int>()
            var y = (py - r).toInt()
            while (y <= (py + r).toInt()) { ys.add(y.coerceIn(0, frame.height - 1)); y += st }
            var sr = 0.0; var sg = 0.0; var sb = 0.0
            val lum = Array(ys.size) { DoubleArray(xs.size) }
            for (j in ys.indices) for (i in xs.indices) {
                val p = frame.pixel(xs[i], ys[j])
                sr += Px.r(p); sg += Px.g(p); sb += Px.b(p)
                lum[j][i] = Px.luma(p).toDouble()
            }
            val n = (xs.size * ys.size).toDouble()
            val mr = sr / n; val mg = sg / n; val mb = sb / n
            var lm = 0.0
            for (row in lum) for (v in row) lm += v
            lm /= n
            var varSum = 0.0
            for (row in lum) for (v in row) varSum += (v - lm) * (v - lm)
            val texture = sqrt(varSum / n)
            var dy = 0.0; var dyN = 0
            for (j in 1 until ys.size) for (i in xs.indices) { dy += abs(lum[j][i] - lum[j - 1][i]); dyN++ }
            var dx = 0.0; var dxN = 0
            for (j in ys.indices) for (i in 1 until xs.size) { dx += abs(lum[j][i] - lum[j][i - 1]); dxN++ }
            val edge = (if (dyN > 0) dy / dyN else 0.0) + (if (dxN > 0) dx / dxN else 0.0)
            var rr = 0.0; var rg = 0.0; var rb = 0.0; var rn = 0
            var ang = 0
            while (ang < 360) {
                for (rad in doubleArrayOf(ri, (ri + ro) / 2.0, ro)) {
                    val qx = (px + rad * cos(Math.toRadians(ang.toDouble()))).toInt().coerceIn(0, frame.width - 1)
                    val qy = (py + rad * sin(Math.toRadians(ang.toDouble()))).toInt().coerceIn(0, frame.height - 1)
                    val p = frame.pixel(qx, qy)
                    rr += Px.r(p); rg += Px.g(p); rb += Px.b(p); rn++
                }
                ang += 20
            }
            val ring = abs(mr - rr / rn) + abs(mg - rg / rn) + abs(mb - rb / rn)
            val f = out[cy * 8 + cx]
            f[0] = mr; f[1] = mg; f[2] = mb; f[3] = texture; f[4] = edge; f[5] = ring
        }
        return out
    }

    /** Median colour on the interior grid lines (the gaps between cells). */
    private fun gapColor(frame: PixelFrame, g: BoardGeometry): DoubleArray {
        val s = g.cellSize.toDouble()
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun add(x: Double, y: Double) {
            val p = frame.pixel(x.toInt().coerceIn(0, frame.width - 1), y.toInt().coerceIn(0, frame.height - 1))
            rs.add(Px.r(p)); gs.add(Px.g(p)); bs.add(Px.b(p))
        }
        for (k in 1..7) for (j in 0 until 8) for (dv in doubleArrayOf(-0.15, 0.0, 0.15)) {
            add(g.left + k * s, g.top + (j + 0.5 + dv) * s)
            add(g.left + (j + 0.5 + dv) * s, g.top + k * s)
        }
        fun med(l: ArrayList<Int>): Double { l.sort(); return l[l.size / 2].toDouble() }
        return doubleArrayOf(med(rs), med(gs), med(bs))
    }

    /**
     * @param palette mean RGB of each detected tray piece (cue: blocks look like tray pieces), may be empty
     * @param emptyLook learned empty look (6-vector from [learnEmptyLook]) or null
     */
    fun read(frame: PixelFrame, g: BoardGeometry, palette: List<DoubleArray>, emptyLook: DoubleArray?): BoardReading {
        val f = features(frame, g)
        val n = 64
        val d = Array(n) { i -> DoubleArray(n) { j -> cellDistance(f[i], f[j]) } }
        val remaining = BooleanArray(n) { true }
        val clusters = ArrayList<Pair<BooleanArray, DoubleArray>>()
        for (iter in 0 until 3) {
            var bestI = -1
            var bestCnt = 0
            for (i in 0 until n) {
                if (!remaining[i]) continue
                var cnt = 0
                for (j in 0 until n) if (remaining[j] && d[i][j] < RAD) cnt++
                if (cnt > bestCnt) { bestCnt = cnt; bestI = i }
            }
            if (bestI < 0) break
            var members = BooleanArray(n) { remaining[it] && d[bestI][it] < RAD }
            var center = meanOf(f, members)
            members = BooleanArray(n) { remaining[it] && cellDistance(f[it], center) < RAD }
            if (members.none { it }) break
            center = meanOf(f, members)
            clusters.add(members to center)
            for (i in 0 until n) if (members[i]) remaining[i] = false
        }
        if (clusters.isEmpty()) return BoardReading(0L, DoubleArray(n), 0.0, listOf("no cells analysed"))

        val gap = gapColor(frame, g)
        class Scored(val total: Double, val gapPart: Double, val palPart: Double?)
        fun score(members: BooleanArray, c: DoubleArray): Scored {
            val gapPart = -(abs(c[0] - gap[0]) + abs(c[1] - gap[1]) + abs(c[2] - gap[2])) / 120.0
            val texPart = -(c[3] / 3.0 + c[4] / 2.5 + c[5] / 12.0) / 6.0
            val sizePart = min(members.count { it } / 64.0, 0.7)
            var total = 0.8 * gapPart + 0.5 * texPart + 0.4 * sizePart
            var palPart: Double? = null
            if (palette.isNotEmpty()) {
                var m = Double.MAX_VALUE
                for (p in palette) m = min(m, abs(c[0] - p[0]) + abs(c[1] - p[1]) + abs(c[2] - p[2]))
                palPart = min(m / 150.0, 1.0)
                total += 1.0 * palPart
            }
            if (emptyLook != null) total += 1.5 * (-cellDistance(c, emptyLook) / 4.0)
            return Scored(total, gapPart, palPart)
        }
        val scored = clusters.map { score(it.first, it.second) }
        val order = scored.indices.sortedByDescending { scored[it].total }
        val chosen = order[0]
        val orient: Double? = if (order.size > 1) min(1.0, (scored[order[0]].total - scored[order[1]].total) / 0.35) else null
        val members = clusters[chosen].first
        val center = clusters[chosen].second

        val dist = DoubleArray(n) { cellDistance(f[it], center) }
        var inSum = 0.0
        var inCnt = 0
        for (i in 0 until n) if (members[i]) { inSum += dist[i]; inCnt++ }
        val sig = max(RAD * 0.5, if (inCnt > 0) inSum / inCnt else 0.0)
        val threshold = max(2.6, 3.0 * sig)
        val occupied = BooleanArray(n) { dist[it] > threshold }
        val notes = ArrayList<String>()
        val conf = DoubleArray(n)
        if (occupied.any { it }) {
            val o = orient ?: 0.8
            for (i in 0 until n) conf[i] = (abs(dist[i] - threshold) / (0.5 * threshold)).coerceIn(0.0, 1.0) * min(1.0, 0.4 + o)
            if (occupied.count { it } > 0.8 * n) {
                for (i in 0 until n) conf[i] *= 0.3
                notes.add("most cells differ from the chosen empty look")
            }
            if (o < 0.5) notes.add("empty look chosen with weak evidence")
        } else {
            val sc = scored[chosen]
            val looksEmpty = sc.gapPart > -0.45 && (sc.palPart == null || sc.palPart > 0.25)
            var c = if (looksEmpty) 0.8 else 0.15
            if (emptyLook != null && looksEmpty) c = (1.0 - cellDistance(center, emptyLook) / 4.0).coerceIn(0.0, 1.0)
            for (i in 0 until n) conf[i] = c
            notes.add(if (looksEmpty) "uniform board read as empty" else "ambiguous: every cell looks the same")
        }
        var bits = 0L
        for (i in 0 until n) if (occupied[i]) bits = bits or (1L shl i)
        val sorted = conf.sortedArray()
        return BoardReading(bits, conf, sorted[1], notes)
    }

    /** Learn the empty look (mean feature vector of the cells the user confirmed as empty). */
    fun learnEmptyLook(frame: PixelFrame, g: BoardGeometry, bits: Long): DoubleArray? {
        val f = features(frame, g)
        val sum = DoubleArray(6)
        var cnt = 0
        for (i in 0 until 64) if ((bits ushr i) and 1L == 0L) {
            for (k in 0 until 6) sum[k] += f[i][k]
            cnt++
        }
        if (cnt < 4) return null
        return DoubleArray(6) { sum[it] / cnt }
    }

    private fun meanOf(f: Array<DoubleArray>, members: BooleanArray): DoubleArray {
        val sum = DoubleArray(6)
        var cnt = 0
        for (i in f.indices) if (members[i]) { for (k in 0 until 6) sum[k] += f[i][k]; cnt++ }
        return DoubleArray(6) { sum[it] / max(1, cnt) }
    }

    companion object {
        const val RAD = 1.6
        private val UNITS = doubleArrayOf(30.0, 30.0, 30.0, 3.0, 2.5, 12.0)

        /** Sum over features of the difference in units of a "noticeable" difference (each capped at 4). */
        fun cellDistance(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (k in 0 until 6) s += min(abs(a[k] - b[k]) / UNITS[k], 4.0)
            return s
        }
    }
}
