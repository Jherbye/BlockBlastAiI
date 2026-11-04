package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class OccupancySource { MODEL, UNSUPERVISED }

class BoardCells(
    val occupied: BooleanArray,
    /** Probability-like confidence that each cell's label is right (0..1). */
    val confidence: DoubleArray,
    val source: OccupancySource,
    /** How sure we are which side of the split is "occupied" (1 for model-driven results). */
    val orientationConfidence: Double,
    /** Signals that formed a valid split, e.g. "colour", "texture". Empty = board looks uniform. */
    val signals: List<String>,
    val conflict: Boolean,
    val uniform: Boolean,
    val notes: List<String>,
    /** The learned theme model matched (almost) no empty cell: the theme probably changed and the model must be dropped. */
    val modelRejected: Boolean = false,
) {
    val bits: Long get() = occupied.indices.fold(0L) { acc, i -> if (occupied[i]) acc or (1L shl i) else acc }
    val minConfidence: Double get() = confidence.min()
}

/**
 * Decides which cells are occupied WITHOUT knowing the theme.
 *
 * Unsupervised mode: several independent signals (colour, texture, relief/outline, contrast to the lattice lines) each
 * try to split the 64 cells into two groups; valid splits are aligned and fused per cell. Which group means "occupied"
 * is a separate decision made by evidence voting (minority size, chroma, texture, tray colours, lattice contrast).
 * Weak or contradictory evidence lowers confidence and ends up as "needs confirmation" instead of a guess.
 *
 * Model mode: once a [ThemeModel] has learned what empty cells look like, each cell is judged against it.
 */
class CellAnalyzer {

    private class Part(
        val name: String, val label: IntArray, val margin: DoubleArray, val sep: Double, val q: Double,
    ) {
        val weight: Double get() = min(q, 6.0) / 6.0
    }

    fun analyze(f: CellFeatures, model: ThemeModel?, trayColors: List<DoubleArray>): BoardCells {
        val notes = ArrayList<String>()
        val unsup = unsupervised(f, trayColors, notes)
        if (model == null || !model.isReady) return unsup

        val n = f.n
        val occ = BooleanArray(n)
        val conf = DoubleArray(n)
        var matchedEmpty = 0
        for (i in 0 until n) {
            val j = model.judge(f.vec(i))
            occ[i] = j.occupied
            conf[i] = j.confidence
            if (!j.occupied) matchedEmpty++
        }
        // a legal board always has empty cells; if almost none match what empty looked like, the theme changed
        if (matchedEmpty < 3) {
            val u = unsup
            return BoardCells(u.occupied, u.confidence, u.source, min(u.orientationConfidence, 0.4), u.signals, u.conflict, u.uniform,
                u.notes + "the learned theme no longer matches the screen (theme changed?): please confirm the board", modelRejected = true)
        }
        val modelNotes = ArrayList<String>()
        // cross-check with the unsupervised split
        if (unsup.signals.isNotEmpty() && !unsup.uniform) {
            val agree = (0 until n).count { occ[it] == unsup.occupied[it] }
            val a = max(agree, n - agree) / n.toDouble()
            if (a < 0.85) {
                modelNotes.add("learned theme model and live split disagree on ${n - agree} cells")
                for (i in 0 until n) if (occ[i] != unsup.occupied[i]) conf[i] = min(conf[i], 0.45)
            }
        }
        if (matchedEmpty == 0) modelNotes.add("no cell matches the learned empty appearance (theme changed?)")
        return BoardCells(occ, conf, OccupancySource.MODEL, 1.0, unsup.signals, unsup.conflict, false, modelNotes)
    }

    // ---- unsupervised --------------------------------------------------------------------------------------------

    private fun unsupervised(f: CellFeatures, trayColors: List<DoubleArray>, notes: MutableList<String>): BoardCells {
        val n = f.n
        val parts = ArrayList<Part>()
        colourPart(f)?.let { parts.add(it) }
        part("texture", Array(n) { doubleArrayOf(f.sd[it], f.gd[it]) }, 3.0, 1.0)?.let { parts.add(it) }
        part("relief", Array(n) { doubleArrayOf(f.relief[it], f.ring[it]) }, 3.0, 1.0)?.let { parts.add(it) }
        part("lattice-contrast", Array(n) { doubleArrayOf(f.backdrop[it]) }, 4.0, 1.0)?.let { parts.add(it) }

        if (parts.isEmpty()) {
            // Uniform board: all empty (a full board cannot persist, lines clear). Confidence from the largest deviation.
            val conf = DoubleArray(n) { i -> uniformConfidence(f, i) }
            notes.add("no cell group stands out: reading the board as empty")
            return BoardCells(BooleanArray(n), conf, OccupancySource.UNSUPERVISED, 0.6, emptyList(), false, true, notes)
        }

        parts.sortByDescending { it.q * it.sep }
        val ref = parts[0]
        val flip = BooleanArray(parts.size)
        val consistency = DoubleArray(parts.size) { 1.0 }
        // a weaker signal whose flagged cells are (nearly) all flagged by the strongest signal is merely less sensitive
        val subset = BooleanArray(parts.size)
        var conflict = false
        for (k in 1 until parts.size) {
            val agree = (0 until n).count { parts[k].label[it] == ref.label[it] }
            flip[k] = agree < n / 2
            consistency[k] = max(agree, n - agree) / n.toDouble()
            val a = (0 until n).filter { ref.label[it] == 1 }.toSet()
            val bSet = (0 until n).filter { (parts[k].label[it] == 1) != flip[k] }.toSet()
            val inter = a.intersect(bSet).size
            subset[k] = bSet.isNotEmpty() && inter >= 0.85 * bSet.size
            if (!subset[k] && consistency[k] < 0.8 && parts[k].weight >= 0.5 * ref.weight) {
                conflict = true
                notes.add("signals '${ref.name}' and '${parts[k].name}' split the cells differently")
            }
        }
        val score = DoubleArray(n)
        val wcell = DoubleArray(n)
        for (k in parts.indices) {
            val w = parts[k].weight * if (k == 0 || subset[k]) 1.0 else consistency[k] * consistency[k]
            for (i in 0 until n) {
                val o = (parts[k].label[i] == 1) != flip[k]
                // weaker, subset-consistent signal not flagging a cell that the strongest signal flags: neutral
                if (k > 0 && subset[k] && !o && ref.label[i] == 1) continue
                score[i] += w * (if (o) 1.0 else -1.0) * parts[k].margin[i]
                wcell[i] += w
            }
        }
        val group1 = BooleanArray(n) { wcell[it] > 0 && score[it] >= 0 }
        val base = 0.55 + 0.45 * ref.weight
        val conf = DoubleArray(n) { i ->
            var c = if (wcell[i] > 0) (abs(score[i] / wcell[i]) * 1.15).coerceIn(0.0, 1.0) * base else 0.0
            if (conflict) c *= 0.6
            c
        }

        val (occIsGroup1, orientConf) = orient(f, group1, trayColors, notes)
        val occupied = BooleanArray(n) { if (occIsGroup1) group1[it] else !group1[it] }
        return BoardCells(occupied, conf, OccupancySource.UNSUPERVISED, orientConf, parts.map { it.name }, conflict, false, notes)
    }

    private fun uniformConfidence(f: CellFeatures, i: Int): Double {
        val n = f.n
        fun med(a: DoubleArray) = a.sortedArray()[n / 2]
        val mL = med(DoubleArray(n) { f.lab[it][0] })
        val mA = med(DoubleArray(n) { f.lab[it][1] })
        val mB = med(DoubleArray(n) { f.lab[it][2] })
        val dev = sqrt((f.lab[i][0] - mL).let { it * it } + (f.lab[i][1] - mA).let { it * it } + (f.lab[i][2] - mB).let { it * it })
        val texDev = max(f.sd[i] - med(f.sd), 0.0)
        val relDev = max(f.relief[i] - med(f.relief), 0.0)
        val worst = max(dev / 6.0, max(texDev / 3.0, relDev / 3.0))
        return (1.0 - worst).coerceIn(0.0, 1.0) * 0.9
    }

    /** Colour split, trying raw, parity-normalised (checkerboards) and plane-detrended (gradients) versions. */
    private fun colourPart(f: CellFeatures): Part? {
        val n = f.n
        val raw = Array(n) { f.lab[it].copyOf() }
        val variants = listOf(raw, parityNormalised(raw), planeDetrended(raw))
        var best: Part? = null
        for (v in variants) {
            val p = part("colour", v, 6.0, 1.5) ?: continue
            if (best == null || p.q * p.sep > best.q * best.sep) best = p
        }
        return best
    }

    /** Centre of the densest group of points (the "most common appearance"), robust to many differently coloured outliers. */
    private fun modeCentre(pts: List<DoubleArray>, radius: Double): DoubleArray {
        var best = 0
        var bestCount = -1
        for (i in pts.indices) {
            val c = pts.count { CellFeatures.dist(it, pts[i]) <= radius }
            if (c > bestCount) { bestCount = c; best = i }
        }
        val near = pts.filter { CellFeatures.dist(it, pts[best]) <= radius }
        return DoubleArray(pts[0].size) { k -> near.sumOf { it[k] } / near.size }
    }

    /** Aligns the two checkerboard parity classes by their most common appearance, so alternating empty shades vanish. */
    private fun parityNormalised(v: Array<DoubleArray>): Array<DoubleArray> {
        val out = Array(v.size) { v[it].copyOf() }
        fun par(i: Int) = ((i % Board.SIZE) + (i / Board.SIZE)) % 2
        val m0 = modeCentre(v.indices.filter { par(it) == 0 }.map { v[it] }, 3.0)
        val m1 = modeCentre(v.indices.filter { par(it) == 1 }.map { v[it] }, 3.0)
        for (i in v.indices) if (par(i) == 1) for (k in m0.indices) out[i][k] = v[i][k] - (m1[k] - m0[k])
        return out
    }

    /** Robust (IRLS, Tukey) plane fit per channel removed from the data, so smooth lighting gradients do not split the board. */
    private fun planeDetrended(v: Array<DoubleArray>): Array<DoubleArray> {
        val d = v[0].size
        val out = Array(v.size) { v[it].copyOf() }
        for (k in 0 until d) {
            val w = DoubleArray(v.size) { 1.0 }
            var coef = doubleArrayOf(0.0, 0.0, 0.0)
            repeat(6) {
                coef = solvePlane(v, k, w)
                val res = DoubleArray(v.size) { i -> v[i][k] - (coef[0] + coef[1] * (i % Board.SIZE) + coef[2] * (i / Board.SIZE)) }
                val mad = max(1.0, res.map { abs(it) }.sorted()[res.size / 2] * 1.4826)
                for (i in res.indices) {
                    val u = res[i] / (4.685 * mad)
                    w[i] = if (abs(u) < 1) (1 - u * u).let { it * it } else 0.0
                }
            }
            val centre = coef[0] + coef[1] * 3.5 + coef[2] * 3.5
            for (i in v.indices) out[i][k] = v[i][k] - (coef[1] * (i % Board.SIZE - 3.5) + coef[2] * (i / Board.SIZE - 3.5)) - 0.0 * centre
        }
        return out
    }

    private fun solvePlane(v: Array<DoubleArray>, k: Int, w: DoubleArray): DoubleArray {
        val a = Array(3) { DoubleArray(4) }
        for (i in v.indices) {
            val r = doubleArrayOf(1.0, (i % Board.SIZE).toDouble(), (i / Board.SIZE).toDouble())
            for (p in 0 until 3) {
                for (q in 0 until 3) a[p][q] += w[i] * r[p] * r[q]
                a[p][3] += w[i] * r[p] * v[i][k]
            }
        }
        for (p in 0 until 3) a[p][p] += 1e-6
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            if (abs(a[c][c]) < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
            for (r in 0 until 3) if (r != c) {
                val m = a[r][c] / a[c][c]
                for (q in c until 4) a[r][q] -= m * a[c][q]
            }
        }
        return doubleArrayOf(a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2])
    }

    /**
     * "Tight group + everything clearly outside it". Blocks come in many colours, so the group that is NOT the empty
     * cells is typically spread out; a symmetric two-cluster criterion would reject it. Instead: find the densest
     * group of cells (seed = point with most neighbours), grow it to its natural radius, and call the split valid only
     * if the nearest outside cell is clearly further from the group centre than the group's own spread, and at
     * least [minSep] (a perceptual difference, not an absolute colour).
     */
    private fun part(name: String, pts: Array<DoubleArray>, minSep: Double, floor: Double): Part? {
        val n = pts.size
        val dim = pts[0].size
        val r0 = 0.5 * minSep
        var seed = 0
        var bestCount = -1
        var bestSpread = Double.MAX_VALUE
        for (i in 0 until n) {
            var cnt = 0
            var sum = 0.0
            for (j in 0 until n) { val d = CellFeatures.dist(pts[i], pts[j]); if (d <= r0) { cnt++; sum += d } }
            val spread = sum / cnt
            if (cnt > bestCount || (cnt == bestCount && spread < bestSpread)) { bestCount = cnt; bestSpread = spread; seed = i }
        }
        var centre = pts[seed].copyOf()
        var rad = r0
        var inGroup = BooleanArray(n)
        repeat(5) {
            val members = (0 until n).filter { CellFeatures.dist(pts[it], centre) <= rad }
            if (members.isEmpty()) return null
            centre = DoubleArray(dim) { k -> members.sumOf { pts[it][k] } / members.size }
            val spread = members.sumOf { CellFeatures.dist(pts[it], centre) } / members.size
            rad = (2.5 * spread + floor).coerceIn(r0, 0.9 * minSep + floor)
            inGroup = BooleanArray(n) { CellFeatures.dist(pts[it], centre) <= rad }
        }
        val nIn = inGroup.count { it }
        if (nIn == n || nIn == 0) return null
        val dist = DoubleArray(n) { CellFeatures.dist(pts[it], centre) }
        val tightRad = (0 until n).filter { inGroup[it] }.map { dist[it] }.sorted().let { it[((it.size - 1) * 0.9).toInt()] }
        val gapStart = (0 until n).filter { !inGroup[it] }.minOf { dist[it] }
        if (gapStart < 0.75 * minSep) return null
        if (gapStart < 1.35 * (tightRad + 0.5 * floor)) return null
        val q = gapStart / (tightRad + floor)
        val theta = 0.5 * (tightRad + gapStart)
        val label = IntArray(n) { if (inGroup[it]) 0 else 1 }
        val margin = DoubleArray(n) { i ->
            if (inGroup[i]) ((theta - dist[i]) / theta).coerceIn(0.0, 1.0)
            else ((dist[i] - theta) / theta).coerceIn(0.0, 1.0)
        }
        return Part(name, label, margin, gapStart, q)
    }

    // ---- orientation ---------------------------------------------------------------------------------------------

    /** Returns (group1 is occupied, orientation confidence 0..1). */
    private fun orient(f: CellFeatures, g1: BooleanArray, tray: List<DoubleArray>, notes: MutableList<String>): Pair<Boolean, Double> {
        val n = f.n
        fun mean(sel: Boolean, a: (Int) -> Double): Double {
            var s = 0.0; var c = 0
            for (i in 0 until n) if (g1[i] == sel) { s += a(i); c++ }
            return if (c == 0) 0.0 else s / c
        }
        val n1 = g1.count { it }
        val frac1 = n1 / n.toDouble()
        val votes = ArrayList<Pair<String, Double>>()
        votes.add("minority" to 0.3 * ((0.5 - frac1) / 0.25).coerceIn(-1.0, 1.0))
        val c1 = mean(true) { f.chroma[it] }
        val c0 = mean(false) { f.chroma[it] }
        votes.add("chroma" to 0.5 * (1.6 * (c1 - c0) / (c1 + c0 + 12.0)).coerceIn(-1.0, 1.0))
        val t1 = mean(true) { f.sd[it] + f.gd[it] + f.relief[it] }
        val t0 = mean(false) { f.sd[it] + f.gd[it] + f.relief[it] }
        votes.add("texture" to 0.4 * (2.0 * (t1 - t0) / (t1 + t0 + 3.0)).coerceIn(-1.0, 1.0))
        val b1 = mean(true) { f.backdrop[it] }
        val b0 = mean(false) { f.backdrop[it] }
        votes.add("lattice-contrast" to 0.3 * (2.0 * (b1 - b0) / (b1 + b0 + 4.0)).coerceIn(-1.0, 1.0))
        if (tray.isNotEmpty()) {
            fun matches(sel: Boolean): Int {
                val cen = DoubleArray(3) { k -> mean(sel) { f.lab[it][k] } }
                return if (tray.any { CellFeatures.dist(it, cen) < 10.0 }) 1 else 0
            }
            // also count individual cells whose colour matches a tray piece colour
            val cell1 = (0 until n).count { g1[it] && tray.any { t -> CellFeatures.dist(t, f.lab[it]) < 8.0 } }
            val cell0 = (0 until n).count { !g1[it] && tray.any { t -> CellFeatures.dist(t, f.lab[it]) < 8.0 } }
            val m1 = matches(true) + if (cell1 > 0 && cell0 == 0) 1 else 0
            val m0 = matches(false) + if (cell0 > 0 && cell1 == 0) 1 else 0
            votes.add("tray-colours" to 0.6 * (m1 - m0).coerceIn(-1, 1))
        }
        val pos = votes.sumOf { max(0.0, it.second) }
        val neg = votes.sumOf { max(0.0, -it.second) }
        val s = pos - neg
        val conf = ((abs(s) - 0.6 * min(pos, neg)) / 0.8).coerceIn(0.0, 1.0)
        if (conf < 0.55) notes.add("cannot tell which cells are blocks: " + votes.joinToString { "${it.first}=${"%.2f".format(it.second)}" })
        return (s >= 0) to conf
    }
}
