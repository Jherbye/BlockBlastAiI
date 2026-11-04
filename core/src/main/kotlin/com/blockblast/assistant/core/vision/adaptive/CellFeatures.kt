package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.vision.BoardGeometry
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Appearance measurements of each of the 64 board cells. Every value is a colour DIFFERENCE or a ratio inside the
 * cell's own neighbourhood, so nothing here depends on absolute theme colours.
 */
class CellFeatures(val cellPitch: Double) {
    val n = Board.SIZE * Board.SIZE
    val lab = Array(n) { DoubleArray(3) }
    /** RMS colour deviation inside the cell's inner region (texture / gradient / gloss). */
    val sd = DoubleArray(n)
    /** Mean edge strength inside the cell, normalised per cell (so it does not depend on resolution). */
    val gd = DoubleArray(n)
    /** Colour difference between inner region and the ring around it (outline / bevel / shadow). */
    val ring = DoubleArray(n)
    /** Top-vs-bottom plus left-vs-right lightness difference inside the cell (bevel / gloss relief). */
    val relief = DoubleArray(n)
    /** Colour difference between cell interior and the lattice lines surrounding it. */
    val backdrop = DoubleArray(n)
    val chroma = DoubleArray(n)

    /** Model feature vector: Lab, texture, edge density, ring contrast, relief. */
    fun vec(i: Int): DoubleArray =
        doubleArrayOf(lab[i][0], lab[i][1], lab[i][2], sd[i], gd[i], 0.7 * ring[i], 0.7 * relief[i])

    companion object {
        fun extract(img: LabImage, g: BoardGeometry): CellFeatures {
            val d = img.scale.toDouble()
            val s = g.cellSize / d
            val left = g.left / d
            val top = g.top / d
            val f = CellFeatures(s)
            val m = DoubleArray(3)
            val mo = DoubleArray(3)
            val tmp = DoubleArray(3)
            val aI = (0.56 * s) * (0.56 * s)
            val aO = (0.88 * s) * (0.88 * s)
            for (cy in 0 until Board.SIZE) for (cx in 0 until Board.SIZE) {
                val i = cy * Board.SIZE + cx
                val x0 = left + cx * s
                val y0 = top + cy * s
                fun bx(a: Double) = x0 + a * s
                fun by(a: Double) = y0 + a * s
                img.meanLab(bx(0.22), by(0.22), bx(0.78), by(0.78), m)
                f.lab[i][0] = m[0]; f.lab[i][1] = m[1]; f.lab[i][2] = m[2]
                f.sd[i] = img.stdLab(bx(0.22), by(0.22), bx(0.78), by(0.78))
                f.gd[i] = img.meanGradient(bx(0.22), by(0.22), bx(0.78), by(0.78)) * s / 8.0
                f.chroma[i] = sqrt(m[1] * m[1] + m[2] * m[2])
                img.meanLab(bx(0.06), by(0.06), bx(0.94), by(0.94), mo)
                val ringMean = DoubleArray(3) { (mo[it] * aO - m[it] * aI) / (aO - aI) }
                f.ring[i] = dist(m, ringMean)
                val top1 = img.meanL(bx(0.22), by(0.12), bx(0.78), by(0.32))
                val bot1 = img.meanL(bx(0.22), by(0.68), bx(0.78), by(0.88))
                val lef1 = img.meanL(bx(0.12), by(0.22), bx(0.32), by(0.78))
                val rig1 = img.meanL(bx(0.68), by(0.22), bx(0.88), by(0.78))
                f.relief[i] = abs(top1 - bot1) + abs(lef1 - rig1)
                // lattice strips on the four sides (shared with neighbours)
                var bd = 0.0
                val sw = 0.05
                val strips = arrayOf(
                    doubleArrayOf(-sw, 0.25, sw, 0.75), doubleArrayOf(1 - sw, 0.25, 1 + sw, 0.75),
                    doubleArrayOf(0.25, -sw, 0.75, sw), doubleArrayOf(0.25, 1 - sw, 0.75, 1 + sw),
                )
                for (st in strips) {
                    img.meanLab(bx(st[0]), by(st[1]), bx(st[2]), by(st[3]), tmp)
                    bd += dist(m, tmp)
                }
                f.backdrop[i] = bd / 4.0
            }
            return f
        }

        fun dist(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (k in a.indices) s += (a[k] - b[k]) * (a[k] - b[k])
            return sqrt(s)
        }
    }
}
