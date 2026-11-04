package com.blockblast.assistant.core.vision.adaptive

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Cluster summary in the model feature space. */
class Proto(val centre: DoubleArray, val spread: Double, val count: Int)

/** Result of judging one cell against the learned theme. */
class ModelJudgement(val occupied: Boolean, val confidence: Double, val zEmpty: Double)

/**
 * What empty and occupied cells LOOK LIKE in the current theme, learned at run time from states the user confirmed or
 * that were read with high confidence. Empty appearance is treated as a one-class model: anything clearly outside the
 * learned empty envelope is occupied, which also covers block colours never seen before. Nothing is preset, so unseen
 * themes only cost one confirmation (or a few confident frames) before recognition becomes model-driven.
 */
class ThemeModel {
    private val emptySamples = ArrayDeque<DoubleArray>()
    private val occupiedSamples = ArrayDeque<DoubleArray>()
    var emptyProtos: List<Proto> = emptyList(); private set
    var occupiedProtos: List<Proto> = emptyList(); private set
    var cellPitch: Double = 0.0; private set
    /** Tray cell size / board cell size, learned from confirmed pieces. */
    var trayRatio: Double? = null
    var learnedFrom: Int = 0; private set

    val isReady: Boolean get() = emptyProtos.isNotEmpty()

    fun reset() {
        emptySamples.clear(); occupiedSamples.clear()
        emptyProtos = emptyList(); occupiedProtos = emptyList()
        trayRatio = null; learnedFrom = 0; cellPitch = 0.0
    }

    /** Learn from a labelled board. [bits] is trusted (user-confirmed or high-confidence). */
    fun learn(f: CellFeatures, bits: Long) {
        if (cellPitch > 0 && kotlin.math.abs(f.cellPitch - cellPitch) / cellPitch > 0.15) reset()
        cellPitch = f.cellPitch
        for (i in 0 until f.n) {
            val v = f.vec(i)
            if ((bits ushr i) and 1L == 1L) push(occupiedSamples, v, 240) else push(emptySamples, v, 240)
        }
        emptyProtos = cluster(emptySamples.toList(), 6.0, 4)
        occupiedProtos = cluster(occupiedSamples.toList(), 9.0, 10)
        learnedFrom++
    }

    private fun push(q: ArrayDeque<DoubleArray>, v: DoubleArray, cap: Int) {
        q.addLast(v)
        while (q.size > cap) q.removeFirst()
    }

    /** Leader clustering followed by one centroid refinement pass. */
    private fun cluster(pts: List<DoubleArray>, radius: Double, maxProtos: Int): List<Proto> {
        if (pts.isEmpty()) return emptyList()
        val centres = ArrayList<DoubleArray>()
        for (p in pts) {
            val near = centres.minByOrNull { CellFeatures.dist(it, p) }
            if (near == null || CellFeatures.dist(near, p) > radius) centres.add(p.copyOf())
            if (centres.size > maxProtos * 3) break
        }
        repeat(2) {
            val sum = Array(centres.size) { DoubleArray(pts[0].size) }
            val cnt = IntArray(centres.size)
            for (p in pts) {
                val k = centres.indices.minByOrNull { CellFeatures.dist(centres[it], p) }!!
                for (d in p.indices) sum[k][d] += p[d]
                cnt[k]++
            }
            for (k in centres.indices) if (cnt[k] > 0) for (d in sum[k].indices) centres[k][d] = sum[k][d] / cnt[k]
        }
        val dsum = DoubleArray(centres.size)
        val cnt = IntArray(centres.size)
        for (p in pts) {
            val k = centres.indices.minByOrNull { CellFeatures.dist(centres[it], p) }!!
            dsum[k] += CellFeatures.dist(centres[k], p)
            cnt[k]++
        }
        return centres.indices.filter { cnt[it] > 0 }
            .map { Proto(centres[it], dsum[it] / cnt[it], cnt[it]) }
            .sortedByDescending { it.count }.take(maxProtos)
    }

    fun judge(v: DoubleArray): ModelJudgement {
        val ze = zOf(v, emptyProtos, 5.0, 3.0)
        val zo = if (occupiedProtos.isEmpty()) Double.MAX_VALUE else zOf(v, occupiedProtos, 6.0, 3.0)
        val occupied = ze > 1.0
        var conf = if (!occupied) 1.0 - 0.5 * ze.pow(2) else 1.0 - 0.5 / ze.pow(1.5)
        if (!occupied && zo < 1.0 && ze > 0.6) conf -= 0.2
        if (occupied && zo < 1.0) conf = max(conf, 0.9)
        return ModelJudgement(occupied, conf.coerceIn(0.0, 1.0), ze)
    }

    private fun zOf(v: DoubleArray, protos: List<Proto>, floor: Double, mult: Double): Double {
        var best = Double.MAX_VALUE
        for (p in protos) {
            val z = CellFeatures.dist(p.centre, v) / max(floor, mult * p.spread)
            best = min(best, z)
        }
        return best
    }
}
