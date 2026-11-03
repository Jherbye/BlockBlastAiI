package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DetectedPiece(val slot: Int, val piece: Piece, val centerX: Float, val confidence: Double)

data class PiecesReading(
    val pieces: List<DetectedPiece>,
    val cellPx: Double,
    val confidence: Double,
    val notes: List<String>,
)

/**
 * Reads the pieces in the tray below the board, from their SHAPE (foreground vs. tray background), not their colour.
 *
 * Steps: foreground mask (differs from the median tray background in brightness or colourfulness) -> connected
 * blobs -> one common tray cell size chosen so every blob is a whole number (1..5) of cells, preferring the size
 * closest to [expectedTrayScale] x board cell -> sample each cell of each blob's bounding box.
 *
 * ASSUMPTIONS (unvalidated on real screenshots): pieces are drawn in the band from 0.4 to 6.2 board-cells below
 * the board, are edge-connected, are at most 5 cells wide/tall, and the tray cells are roughly 0.3-0.95 of the board
 * cell size.
 */
class PieceExtractor(private val expectedTrayScale: Double = 0.6) {

    private class Blob(val x0: Int, val y0: Int, val w: Int, val h: Int, val count: Int)

    fun extract(frame: PixelFrame, g: BoardGeometry, sensitivity: Double = 0.5): PiecesReading? {
        val s = g.cellSize.toDouble()
        val top = (g.bottom + 0.4 * s).toInt()
        val bottom = min(frame.height.toDouble(), g.bottom + 6.2 * s).toInt()
        if (bottom - top < s) return null
        val step = max(1, (s / 30.0).toInt())
        val gw = frame.width / step
        val gh = (bottom - top) / step
        if (gw < 8 || gh < 4) return null

        val lum = IntArray(gw * gh)
        val sat = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val p = frame.pixel(gx * step, top + gy * step)
            lum[gy * gw + gx] = Px.luma(p)
            sat[gy * gw + gx] = Px.saturation(p)
        }
        val bgL = median(lum)
        val bgS = median(sat)
        val lumThr = 40.0 * (1.5 - sensitivity.coerceIn(0.0, 1.0)) / 1.0
        val mask = BooleanArray(gw * gh) { i -> abs(lum[i] - bgL) > lumThr || (sat[i] - bgS) > 70 }

        val dil = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (!mask[gy * gw + gx]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = gx + dx
                val ny = gy + dy
                if (nx in 0 until gw && ny in 0 until gh) dil[ny * gw + nx] = true
            }
        }

        val blobs = findBlobs(mask, dil, gw, gh, step, top, s)
        val notes = ArrayList<String>()
        if (blobs.isEmpty()) return PiecesReading(emptyList(), 0.0, 0.0, listOf("no pieces found in tray region"))

        var kept = blobs
        var confidenceCap = 1.0
        if (blobs.size > 3) {
            notes.add("found ${blobs.size} blobs; expected at most 3")
            kept = blobs.sortedByDescending { it.count }.take(3)
            confidenceCap = 0.3
        }

        val (c, fitErr) = chooseCellSize(kept, s)
        val fitConf = (1.0 - fitErr / 0.15).coerceIn(0.0, 1.0)
        if (fitErr >= 0.15) notes.add("tray cell size fit is poor (err=${"%.2f".format(fitErr)})")

        val detected = ArrayList<DetectedPiece>()
        var minConf = min(fitConf, confidenceCap)
        for (b in kept) {
            val a = (b.w / c).roundToInt().coerceIn(1, 5)
            val bb = (b.h / c).roundToInt().coerceIn(1, 5)
            val cw = b.w.toDouble() / a
            val ch = b.h.toDouble() / bb
            val cells = ArrayList<Cell>()
            var margin = 1.0
            for (j in 0 until bb) for (i in 0 until a) {
                val cxp = b.x0 + (i + 0.5) * cw
                val cyp = b.y0 + (j + 0.5) * ch
                val rr = max(1, (0.25 * min(cw, ch) / step).toInt())
                val gcx = ((cxp / step).toInt()).coerceIn(0, gw - 1)
                val gcy = (((cyp - top) / step).toInt()).coerceIn(0, gh - 1)
                var on = 0
                var tot = 0
                for (dy in -rr..rr) for (dx in -rr..rr) {
                    val nx = gcx + dx
                    val ny = gcy + dy
                    if (nx in 0 until gw && ny in 0 until gh) {
                        tot++
                        if (mask[ny * gw + nx]) on++
                    }
                }
                val frac = if (tot == 0) 0.0 else on.toDouble() / tot
                if (frac >= 0.5) cells.add(Cell(i, j))
                margin = min(margin, abs(frac - 0.5) * 2.0)
            }
            if (cells.isEmpty()) continue
            val centerX = (b.x0 + b.w / 2.0).toFloat()
            val slot = min(2, (centerX / (frame.width / 3.0)).toInt())
            minConf = min(minConf, margin)
            detected.add(DetectedPiece(slot, Piece(cells), centerX, margin))
        }
        detected.sortBy { it.centerX }
        return PiecesReading(detected, c, if (detected.isEmpty()) 0.0 else minConf, notes)
    }

    private fun chooseCellSize(blobs: List<Blob>, boardCell: Double): Pair<Double, Double> {
        var bestFitC = -1.0
        var bestFitDist = Double.MAX_VALUE
        var bestFitErr = 0.0
        var fallbackC = boardCell * expectedTrayScale
        var fallbackErr = Double.MAX_VALUE
        var c = 0.30 * boardCell
        while (c <= 0.95 * boardCell) {
            var err = 0.0
            for (b in blobs) err += dimErr(b.w / c) + dimErr(b.h / c)
            err /= (2.0 * blobs.size)
            if (err < fallbackErr) {
                fallbackErr = err
                fallbackC = c
            }
            if (err < 0.15) {
                val dist = abs(c / boardCell - expectedTrayScale)
                if (dist < bestFitDist) {
                    bestFitDist = dist
                    bestFitC = c
                    bestFitErr = err
                }
            }
            c += 0.5
        }
        return if (bestFitC > 0) bestFitC to bestFitErr else fallbackC to fallbackErr
    }

    private fun dimErr(v: Double): Double {
        val r = v.roundToInt()
        return if (r < 1 || r > 5) 9.0 else abs(v - r)
    }

    private fun findBlobs(mask: BooleanArray, dil: BooleanArray, gw: Int, gh: Int, step: Int, top: Int, s: Double): List<Blob> {
        val label = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val blobs = ArrayList<Blob>()
        val minCells = max(2.0, (0.2 * s / step) * (0.2 * s / step)).toInt()
        for (start in 0 until gw * gh) {
            if (!dil[start] || label[start]) continue
            var sp = 0
            stack[sp++] = start
            label[start] = true
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % gw
                val cy = cur / gw
                if (mask[cur]) {
                    count++
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                }
                for (k in 0 until 4) {
                    val nx = cx + DX[k]
                    val ny = cy + DY[k]
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val ni = ny * gw + nx
                    if (dil[ni] && !label[ni]) {
                        label[ni] = true
                        stack[sp++] = ni
                    }
                }
            }
            if (count < minCells || maxX < 0) continue
            val w = (maxX - minX + 1) * step
            val h = (maxY - minY + 1) * step
            if (w < 0.3 * s || h < 0.3 * s || w > 5.3 * s || h > 5.3 * s) continue
            blobs.add(Blob(minX * step, top + minY * step, w, h, count))
        }
        return blobs
    }

    private fun median(a: IntArray): Int {
        val c = a.copyOf()
        c.sort()
        return c[c.size / 2]
    }

    private companion object {
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}
