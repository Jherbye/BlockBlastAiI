package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class DetectedPiece(
    val slot: Int,
    val piece: Piece,
    val centerX: Float,
    val confidence: Double,
    /** Mean colour of the piece (palette cue for the cell reader). */
    val rgb: DoubleArray,
)

data class PiecesReading(
    val pieces: List<DetectedPiece>,
    val cellPx: Double,
    val confidence: Double,
    val notes: List<String>,
    /** Slots that look empty but contain weak foreground energy: a piece may have been missed. */
    val suspectSlots: List<Int>,
)

/**
 * Theme-independent tray reading from SHAPE, not colour.
 *  - The tray band lies below the detected board (relative to the board's cell size) and above the bottom 8% of the
 *    screen (ad area). No absolute coordinates, no fixed colours.
 *  - Background = per-row median colour (handles gradients/panels); falls back to the screen-edge colour when pieces
 *    cover most of a row. Foreground = colour distance from that background, thresholded by Otsu with floors relative
 *    to the measured background noise (so brightness/theme changes do not matter).
 *  - Loose mask (dilated) joins the cells of one piece; a stricter mask gives the bounding box and the cell samples,
 *    so soft shadows do not enlarge pieces.
 *  - One tray cell size per scan: every blob dimension must be a whole number (1..5) of cells; ties prefer
 *    [expectedTrayScale] x board cell. Each cell of each blob is then sampled -> the individual occupied squares.
 *  - Empty slots are cross-checked for weak foreground energy (a missed piece lowers confidence instead of being
 *    silently dropped).
 */
class PieceExtractor(private val expectedTrayScale: Double = 0.5) {

    private class Blob(val x0: Int, val y0: Int, val w: Int, val h: Int, val count: Int)

    fun extract(frame: PixelFrame, g: BoardGeometry): PiecesReading? {
        val s = g.cellSize.toDouble()
        val top = (g.bottom + 0.25 * s).toInt()
        val bot = min(0.92 * frame.height, g.bottom + 6.5 * s).toInt()
        if (bot - top < s) return null
        val step = max(1, (s / 30.0).toInt())
        val gw = (frame.width + step - 1) / step
        val gh = (bot - top + step - 1) / step
        if (gw < 8 || gh < 4) return null

        val sr = Array(gh) { IntArray(gw) }
        val sg = Array(gh) { IntArray(gw) }
        val sb = Array(gh) { IntArray(gw) }
        for (y in 0 until gh) for (x in 0 until gw) {
            val p = frame.pixel(min(frame.width - 1, x * step), min(frame.height - 1, top + y * step))
            sr[y][x] = Px.r(p); sg[y][x] = Px.g(p); sb[y][x] = Px.b(p)
        }
        var dist = distanceMap(sr, sg, sb, gw, gh, false)
        var thr = threshold(dist, gw, gh)
        if (fraction(dist, thr) > 0.40) {
            dist = distanceMap(sr, sg, sb, gw, gh, true)
            thr = threshold(dist, gw, gh)
        }
        val loose = Array(gh) { y -> BooleanArray(gw) { x -> dist[y][x] > thr } }
        val looseVals = ArrayList<Double>()
        for (y in 0 until gh) for (x in 0 until gw) if (loose[y][x]) looseVals.add(dist[y][x])
        if (looseVals.size < 4) return PiecesReading(emptyList(), 0.0, 0.0, listOf("no foreground in the tray band"), emptyList())
        looseVals.sort()
        val strictThr = max(thr, 0.5 * looseVals[looseVals.size / 2])
        val strict = Array(gh) { y -> BooleanArray(gw) { x -> dist[y][x] > strictThr } }
        val dil = Array(gh) { BooleanArray(gw) }
        for (y in 0 until gh) for (x in 0 until gw) if (loose[y][x]) {
            for (dy in -1..1) for (dx in -1..1) {
                val ny = y + dy; val nx = x + dx
                if (ny in 0 until gh && nx in 0 until gw) dil[ny][nx] = true
            }
        }

        val blobs = findBlobs(strict, dil, gw, gh, step, top, s)
        val notes = ArrayList<String>()
        if (blobs.isEmpty()) return PiecesReading(emptyList(), 0.0, 0.0, listOf("no pieces found in the tray band"), suspects(dist, thr, gw, gh, emptySet()))
        var kept = blobs
        var cap = 1.0
        if (blobs.size > 3) {
            notes.add("found ${blobs.size} blobs; expected at most 3")
            kept = blobs.sortedByDescending { it.count }.take(3)
            cap = 0.3
        }
        val (c, fitErr) = chooseCellSize(kept, s)
        val fitConf = (1.0 - fitErr / 0.30).coerceIn(0.0, 1.0)
        if (fitErr >= FIT_LIMIT) notes.add("tray cell size fit is poor (err=${"%.2f".format(fitErr)})")

        val detected = ArrayList<DetectedPiece>()
        var minConf = min(fitConf, cap)
        for (b in kept) {
            val a = floor(b.w / c + 0.5).toInt().coerceIn(1, 5)
            val bb = floor(b.h / c + 0.5).toInt().coerceIn(1, 5)
            val cw = b.w.toDouble() / a
            val ch = b.h.toDouble() / bb
            val cells = ArrayList<Cell>()
            var margin = 1.0
            for (j in 0 until bb) for (i in 0 until a) {
                val cxp = b.x0 + (i + 0.5) * cw
                val cyp = b.y0 + (j + 0.5) * ch
                val rr = max(1, (0.25 * min(cw, ch) / step).toInt())
                val gcx = (cxp / step).toInt().coerceIn(0, gw - 1)
                val gcy = ((cyp - top) / step).toInt().coerceIn(0, gh - 1)
                var on = 0
                var tot = 0
                for (dy in -rr..rr) for (dx in -rr..rr) {
                    val ny = gcy + dy; val nx = gcx + dx
                    if (ny in 0 until gh && nx in 0 until gw) { tot++; if (strict[ny][nx]) on++ }
                }
                val frac = if (tot == 0) 0.0 else on.toDouble() / tot
                if (frac >= 0.5) cells.add(Cell(i, j))
                margin = min(margin, abs(frac - 0.5) * 2.0)
            }
            if (cells.isEmpty()) continue
            // mean colour of the strict foreground inside the blob
            val gy0 = ((b.y0 - top) / step).coerceIn(0, gh - 1)
            val gy1 = ((b.y0 - top + b.h) / step).coerceIn(0, gh - 1)
            val gx0 = (b.x0 / step).coerceIn(0, gw - 1)
            val gx1 = ((b.x0 + b.w) / step).coerceIn(0, gw - 1)
            var rs = 0.0; var gs = 0.0; var bs = 0.0; var cnt = 0
            for (y in gy0..gy1) for (x in gx0..gx1) if (strict[y][x]) { rs += sr[y][x]; gs += sg[y][x]; bs += sb[y][x]; cnt++ }
            val rgb = if (cnt > 0) doubleArrayOf(rs / cnt, gs / cnt, bs / cnt) else doubleArrayOf(0.0, 0.0, 0.0)
            val centerX = (b.x0 + b.w / 2.0).toFloat()
            val slot = min(2, (centerX / (frame.width / 3.0)).toInt())
            minConf = min(minConf, margin)
            detected.add(DetectedPiece(slot, Piece(cells), centerX, margin, rgb))
        }
        detected.sortBy { it.centerX }
        val suspect = suspects(dist, thr, gw, gh, detected.map { it.slot }.toSet())
        var conf = if (detected.isEmpty()) 0.0 else minConf
        if (suspect.isNotEmpty()) {
            conf = min(conf, 0.3)
            notes.add("slot(s) ${suspect.joinToString()} look empty but contain faint shapes: a piece may be missed")
        }
        return PiecesReading(detected, c, conf, notes, suspect)
    }

    private fun suspects(dist: Array<DoubleArray>, thr: Double, gw: Int, gh: Int, taken: Set<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (sl in 0 until 3) {
            if (sl in taken) continue
            val x0 = sl * gw / 3
            val x1 = (sl + 1) * gw / 3
            var weak = 0
            var tot = 0
            for (y in 0 until gh) for (x in x0 until x1) { tot++; if (dist[y][x] > 0.5 * thr) weak++ }
            if (tot > 0 && weak.toDouble() / tot > 0.004) out.add(sl)
        }
        return out
    }

    private fun distanceMap(sr: Array<IntArray>, sg: Array<IntArray>, sb: Array<IntArray>, gw: Int, gh: Int, edgeBg: Boolean): Array<DoubleArray> {
        val edgeN = max(1, gw / 25)
        return Array(gh) { y ->
            val rv = ArrayList<Int>(); val gv = ArrayList<Int>(); val bv = ArrayList<Int>()
            if (edgeBg) {
                for (x in 0 until edgeN) { rv.add(sr[y][x]); gv.add(sg[y][x]); bv.add(sb[y][x]) }
                for (x in gw - edgeN until gw) { rv.add(sr[y][x]); gv.add(sg[y][x]); bv.add(sb[y][x]) }
            } else {
                for (x in 0 until gw) { rv.add(sr[y][x]); gv.add(sg[y][x]); bv.add(sb[y][x]) }
            }
            rv.sort(); gv.sort(); bv.sort()
            val mr = rv[rv.size / 2]; val mg = gv[gv.size / 2]; val mb = bv[bv.size / 2]
            DoubleArray(gw) { x -> (abs(sr[y][x] - mr) + abs(sg[y][x] - mg) + abs(sb[y][x] - mb)).toDouble() }
        }
    }

    private fun fraction(dist: Array<DoubleArray>, thr: Double): Double {
        var c = 0
        var t = 0
        for (row in dist) for (v in row) { t++; if (v > thr) c++ }
        return c.toDouble() / max(1, t)
    }

    private fun threshold(dist: Array<DoubleArray>, gw: Int, gh: Int): Double {
        val all = DoubleArray(gw * gh)
        var k = 0
        for (row in dist) for (v in row) all[k++] = v
        val sorted = all.sortedArray()
        val median = sorted[sorted.size / 2]
        return max(otsu(all), max(40.0, 5.0 * median))
    }

    private fun otsu(values: DoubleArray, bins: Int = 128, vmax: Double = 765.0): Double {
        val hist = DoubleArray(bins)
        for (v in values) hist[min(bins - 1, (v / vmax * bins).toInt().coerceAtLeast(0))] += 1.0
        val total = values.size.toDouble()
        var cumW = 0.0
        var cumMu = 0.0
        val mt = hist.indices.sumOf { it * hist[it] / total }
        var best = -1.0
        var bestK = 0
        for (k in 0 until bins) {
            val p = hist[k] / total
            cumW += p
            cumMu += k * p
            val sb = (mt * cumW - cumMu) * (mt * cumW - cumMu) / (cumW * (1 - cumW) + 1e-12)
            if (sb > best) { best = sb; bestK = k }
        }
        return (bestK + 1) * vmax / bins
    }

    private fun chooseCellSize(blobs: List<Blob>, boardCell: Double): Pair<Double, Double> {
        val sizes = ArrayList<Double>()
        val errors = ArrayList<Double>()
        var c = 0.25 * boardCell
        while (c <= 0.95 * boardCell) {
            var err = 0.0
            for (b in blobs) err += dimErr(b.w / c) + dimErr(b.h / c)
            sizes.add(c)
            errors.add(err / (2.0 * blobs.size))
            c += 0.5
        }
        var minIdx = 0
        for (i in errors.indices) if (errors[i] < errors[minIdx]) minIdx = i
        val minErr = errors[minIdx]
        if (minErr >= FIT_LIMIT) return sizes[minIdx] to minErr
        var bestIdx = minIdx
        var bestDist = Double.MAX_VALUE
        for (i in sizes.indices) {
            if (errors[i] < FIT_LIMIT && errors[i] <= minErr + 0.02) {
                val dd = abs(sizes[i] / boardCell - expectedTrayScale)
                if (dd < bestDist) { bestDist = dd; bestIdx = i }
            }
        }
        return sizes[bestIdx] to errors[bestIdx]
    }

    private fun dimErr(v: Double): Double {
        val r = floor(v + 0.5).toInt()
        return if (r < 1 || r > 5) 9.0 else abs(v - r)
    }

    private fun findBlobs(strict: Array<BooleanArray>, dil: Array<BooleanArray>, gw: Int, gh: Int, step: Int, top: Int, s: Double): List<Blob> {
        val seen = Array(gh) { BooleanArray(gw) }
        val stack = IntArray(gw * gh)
        val out = ArrayList<Blob>()
        val minCells = max(2.0, (0.2 * s / step) * (0.2 * s / step)).toInt()
        for (sy in 0 until gh) for (sx in 0 until gw) {
            if (!dil[sy][sx] || seen[sy][sx]) continue
            var sp = 0
            stack[sp++] = sy * gw + sx
            seen[sy][sx] = true
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % gw; val cy = cur / gw
                if (strict[cy][cx]) {
                    count++
                    minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy)
                }
                for (k in 0 until 4) {
                    val nx = cx + DX[k]; val ny = cy + DY[k]
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    if (dil[ny][nx] && !seen[ny][nx]) { seen[ny][nx] = true; stack[sp++] = ny * gw + nx }
                }
            }
            if (count < minCells || maxX < 0) continue
            val w = (maxX - minX + 1) * step
            val h = (maxY - minY + 1) * step
            if (w < 0.3 * s || h < 0.3 * s || w > 5.3 * s || h > 5.3 * s) continue
            if (maxY >= gh - 1) continue // cut off by the bottom of the band: likely ad/UI, not a piece
            out.add(Blob(minX * step, top + minY * step, w, h, count))
        }
        return out
    }

    private companion object {
        const val FIT_LIMIT = 0.15
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)
    }
}
