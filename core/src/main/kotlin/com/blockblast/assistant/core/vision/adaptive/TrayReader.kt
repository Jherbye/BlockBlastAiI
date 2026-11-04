package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.FloatRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TrayPiece(
    val slot: Int,
    val piece: Piece,
    val confidence: Double,
    val cellConfidence: List<Double>,
    /** Full-resolution pixels. */
    val bounds: FloatRect,
    val notes: List<String>,
)

class TrayReading(
    val pieces: List<TrayPiece>,
    /** Tray cell pitch in full-resolution pixels (0 if no piece). */
    val cellPx: Double,
    val pitchRatio: Double,
    val pitchAmbiguous: Boolean,
    /** Mean Lab colour of every tray piece cell, for the orientation vote of the board analyzer. */
    val cellColours: List<DoubleArray>,
    val notes: List<String>,
)

/**
 * Finds the pieces below the board with no assumption about the tray's colour or the pieces' styling.
 *
 *  1. Per-row robust background (median colour) and noise level, so gradients and textured backgrounds are tolerated.
 *  2. Foreground = colour difference from that background above an adaptive threshold.
 *  3. Morphological close + hole fill bridges gaps between cells and fills outline-only blocks.
 *  4. Connected components = pieces. Shadows are removed by keeping only strong-difference pixels (re-closed/filled).
 *  5. One common cell pitch is chosen from three signals: whole-number fit of all piece dimensions, internal lattice lines
 *     inside the pieces, and the learned tray/board ratio. Several equally good pitches => reported as ambiguous.
 *  6. Each sub-cell of a piece's bounding grid is judged by its foreground coverage, with a margin as confidence.
 */
class TrayReader {

    private class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int, val mask: BooleanArray, val strength: Double) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    fun read(img: LabImage, g: BoardGeometry, trayRatioHint: Double?, boardLeftWork: Double, boardWidthWork: Double): TrayReading {
        val d = img.scale.toDouble()
        val s = g.cellSize / d
        val notes = ArrayList<String>()
        val y0 = max(0, (g.bottom / d + 0.3 * s).toInt())
        val y1 = min(img.height, (g.bottom / d + 5.6 * s).toInt())
        val w = img.width
        val h = y1 - y0
        if (h < 0.8 * s) return TrayReading(emptyList(), 0.0, 0.0, false, emptyList(), listOf("tray region is outside the frame"))

        // 1-2. background-relative foreground
        val delta = FloatArray(w * h)
        var mask = BooleanArray(w * h)
        val bg = Array(h) { DoubleArray(3) }
        // tray content sits under the board: ignore everything beyond 0.4 cells outside the board's horizontal span
        val xa = max(0, (boardLeftWork - 0.4 * s).toInt())
        val xb = min(w, (boardLeftWork + boardWidthWork + 0.4 * s).toInt())
        if (xb - xa < 3 * s) return TrayReading(emptyList(), 0.0, 0.0, false, emptyList(), listOf("tray region is outside the frame"))
        val modes = globalModes(img, xa, xb, y0, y1)
        val fgPrev = BooleanArray(w * h)
        repeat(2) { pass ->
            val thr = DoubleArray(h)
            for (r in 0 until h) {
                val y = y0 + r
                for (k in 0 until 3) {
                    val xs = ArrayList<Float>(xb - xa)
                    for (x in xa until xb) if (pass == 0 || !fgPrev[r * w + x]) xs.add(chan(img, k, y * w + x))
                    if (xs.isEmpty()) for (x in xa until xb) xs.add(chan(img, k, y * w + x))
                    xs.sort()
                    bg[r][k] = xs[xs.size / 2].toDouble()
                }
                // if the row median is not any dominant background colour, pieces cover most of this row: use the main background
                if (modes.isNotEmpty() && modes.none { m -> sqrt((m[0] - bg[r][0]).let { it * it } + (m[1] - bg[r][1]).let { it * it } + (m[2] - bg[r][2]).let { it * it }) <= 12.0 }) {
                    for (k in 0 until 3) bg[r][k] = modes[0][k]
                }
                val dd = FloatArray(xb - xa)
                for (x in xa until xb) {
                    val i = y * w + x
                    val dl = img.L[i] - bg[r][0]; val da = img.A[i] - bg[r][1]; val db = img.B[i] - bg[r][2]
                    dd[x - xa] = bgDistance(img, i, sqrt(dl * dl + da * da + db * db), modes).toFloat()
                }
                val sorted = dd.copyOf(); sorted.sort()
                val mad = sorted[sorted.size / 2].toDouble()
                // capped: when pieces cover most of a row the deviation estimate is dominated by the pieces themselves
                thr[r] = max(8.0, min(24.0, 4.0 * mad + 4.0))
            }
            for (r in 0 until h) for (x in xa until xb) {
                val i = (y0 + r) * w + x
                val dl = img.L[i] - bg[r][0]; val da = img.A[i] - bg[r][1]; val db = img.B[i] - bg[r][2]
                val v = bgDistance(img, i, sqrt(dl * dl + da * da + db * db), modes)
                delta[r * w + x] = v.toFloat()
                mask[r * w + x] = v > thr[r]
            }
            if (pass == 0) {
                val closed = closeAndFill(mask, w, h, max(1, (0.05 * s).roundToInt()))
                System.arraycopy(closed, 0, fgPrev, 0, closed.size)
            }
        }

        val rad = max(1, (0.09 * s).roundToInt())
        mask = closeAndFill(mask, w, h, rad)
        val rejected = ArrayList<String>()
        val barrier = IntArray(1) { Int.MAX_VALUE }
        // a sudden jump of the row background colour is a hard horizontal edge (ad banner, navigation bar): pieces may run under it
        fun rowDist(a: Int, b: Int): Double {
            val dl = bg[a][0] - bg[b][0]; val da = bg[a][1] - bg[b][1]; val db = bg[a][2] - bg[b][2]
            return sqrt(dl * dl + da * da + db * db)
        }
        val span = 8
        val steps = ArrayList<Int>()
        for (r in span until h - span) {
            if (rowDist(r, r - 1) <= 8.0) continue
            // persistent step: the rows on each side agree with each other (stripes and patterns do not)
            val before = (r - span until r).all { rowDist(it, r - 1) <= 5.0 }
            val after = (r until h).all { rowDist(it, r) <= 5.0 } // a banner stays the same colour to the end of the band
            if (before && after) steps.add(r)
        }
        val blobs = components(mask, delta, w, h, s, h, xb - xa, xa, xb, rejected, barrier)
        notes.addAll(rejected)
        if (blobs.isEmpty()) return TrayReading(emptyList(), 0.0, 0.0, false, emptyList(), listOf("no pieces found in the tray area"))

        // 4. remove shadow fringes: keep only strongly different pixels, re-close and re-fill
        val cores = blobs.map { b ->
            val strong = ArrayList<Float>()
            for (y in b.y0 until b.y1) for (x in b.x0 until b.x1) if (b.mask[y * w + x]) strong.add(delta[y * w + x])
            strong.sort()
            val p90 = strong[(strong.size * 0.9).toInt().coerceAtMost(strong.size - 1)]
            val cut = max(8.0f, 0.25f * p90)
            val core = BooleanArray(w * h)
            for (y in b.y0 until b.y1) for (x in b.x0 until b.x1) if (b.mask[y * w + x] && delta[y * w + x] > cut) core[y * w + x] = true
            val filled = closeAndFill(core, w, h, rad)
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var area = 0
            for (y in b.y0 until b.y1) for (x in b.x0 until b.x1) if (filled[y * w + x] && b.mask[y * w + x]) {
                area++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            val m = BooleanArray(w * h)
            for (y in b.y0 until b.y1) for (x in b.x0 until b.x1) m[y * w + x] = filled[y * w + x] && b.mask[y * w + x]
            Blob(minX, minY, maxX + 1, maxY + 1, area, m, p90.toDouble())
        }.filter { it.area > 0 && it.w >= 0.3 * s && it.h >= 0.3 * s }
        if (cores.size < blobs.size) {
            notes.add("${blobs.size - cores.size} shape(s) in the tray vanished after shadow removal (very low contrast?)")
            rejected.add("shape lost after shadow removal")
        }
        if (cores.isEmpty()) return TrayReading(emptyList(), 0.0, 0.0, false, emptyList(), listOf("no pieces found in the tray area"))

        var kept = cores
        var cap = if (rejected.isNotEmpty()) 0.3 else 1.0
        if (cores.size > 3) {
            notes.add("found ${cores.size} separate shapes in the tray, expected at most 3")
            kept = cores.sortedByDescending { it.area }.take(3)
            cap = 0.3
        }

        // 5. common pitch
        val pitch = choosePitch(img, kept, s, trayRatioHint, y0)
        notes.addAll(pitch.notes)
        val c = pitch.c

        val pieces = ArrayList<TrayPiece>()
        val colours = ArrayList<DoubleArray>()
        val used = HashSet<Int>()
        var dropped = false
        for (b in kept.sortedBy { it.x0 }) {
            val nx = (b.w / c).roundToInt()
            val ny = (b.h / c).roundToInt()
            val pn = ArrayList<String>()
            if (nx < 1 || ny < 1 || nx > 5 || ny > 5) {
                pn.add("shape spans ${nx}x$ny cells at the chosen pitch")
            }
            val cx = nx.coerceIn(1, 5)
            val cy = ny.coerceIn(1, 5)
            val cw = b.w.toDouble() / cx
            val ch = b.h.toDouble() / cy
            val cells = ArrayList<Cell>()
            val cellConf = ArrayList<Double>()
            for (j in 0 until cy) for (i in 0 until cx) {
                var on = 0; var tot = 0
                val xs0 = (b.x0 + (i + 0.25) * cw).toInt(); val xs1 = (b.x0 + (i + 0.75) * cw).toInt().coerceAtLeast(xs0 + 1)
                val ys0 = (b.y0 + (j + 0.25) * ch).toInt(); val ys1 = (b.y0 + (j + 0.75) * ch).toInt().coerceAtLeast(ys0 + 1)
                for (y in ys0 until ys1) for (x in xs0 until xs1) {
                    if (x !in 0 until w || y !in 0 until h) continue
                    tot++; if (b.mask[y * w + x]) on++
                }
                val frac = if (tot == 0) 0.0 else on.toDouble() / tot
                val margin = abs(frac - 0.5) * 2.0
                if (frac >= 0.5) {
                    cells.add(Cell(i, j))
                    val m = DoubleArray(3)
                    img.meanLab(b.x0 + (i + 0.3) * cw, y0 + b.y0 + (j + 0.3) * ch, b.x0 + (i + 0.7) * cw, y0 + b.y0 + (j + 0.7) * ch, m)
                    colours.add(m)
                }
                cellConf.add(0.5 + 0.5 * margin)
            }
            if (cells.isEmpty()) { notes.add("a shape in the tray yielded no cells and was ignored"); dropped = true; continue }
            val piece = Piece(cells)
            var conf = cellConf.min() * pitch.fitConfidence * cap
            // weak foreground contrast (blocks nearly the colour of what is behind them): never be confident
            val contrast = ((b.strength - 12.0) / 14.0).coerceIn(0.0, 1.0)
            if (contrast < 1.0) { conf = min(conf, 0.45 + 0.55 * contrast); pn.add("pieces barely differ from the tray background") }
            if (pitch.ambiguous) conf *= 0.6
            if (nx !in 1..5 || ny !in 1..5) conf = min(conf, 0.2)
            if (!ShapeCatalog.isConnected(piece)) { conf = min(conf, 0.2); pn.add("cells are not edge-connected") }
            else if (!ShapeCatalog.isKnown(piece)) { conf *= 0.8; pn.add("shape not in the known catalog") }
            // a piece that runs into a full-width structure (ad banner, panel edge) or the band's end may be cut off
            if (steps.any { b.y0 < it && b.y1 >= it - 2 } || (b.y0 < barrier[0] && b.y1 >= barrier[0] - 2) || b.y1 >= h - 1) { conf = min(conf, 0.3); pn.add("shape touches the edge of the tray area or a banner: it may be cut off") }
            val centre = (b.x0 + b.x1) / 2.0
            val slot = (((centre - boardLeftWork) / (boardWidthWork / 3.0)).toInt()).coerceIn(0, 2)
            if (!used.add(slot)) { conf = min(conf, 0.2); pn.add("two shapes map to the same tray slot") }
            val bounds = FloatRect((b.x0 * d).toFloat(), ((y0 + b.y0) * d).toFloat(), (b.x1 * d).toFloat(), ((y0 + b.y1) * d).toFloat())
            pieces.add(TrayPiece(slot, piece, conf.coerceIn(0.0, 1.0), cellConf, bounds, pn))
        }
        // a shape that could not be read must never leave the remaining pieces looking confident
        val final = if (dropped) pieces.map { TrayPiece(it.slot, it.piece, min(it.confidence, 0.3), it.cellConfidence, it.bounds, it.notes + "another tray shape could not be read") } else pieces
        return TrayReading(final.sortedBy { it.slot }, c * d, c / s, pitch.ambiguous, colours, notes)
    }

    /** Colours covering at least 12% of the tray band: the background palette (stripes, panels), never pieces. */
    private fun globalModes(img: LabImage, xa: Int, xb: Int, y0: Int, y1: Int): List<DoubleArray> {
        val centres = ArrayList<DoubleArray>()
        val counts = ArrayList<Int>()
        var total = 0
        var y = y0
        while (y < y1) {
            var x = xa
            while (x < xb) {
                val i = y * img.width + x
                var k = -1
                var bestD = 7.0
                for (c in centres.indices) {
                    val dl = img.L[i] - centres[c][0]; val da = img.A[i] - centres[c][1]; val db = img.B[i] - centres[c][2]
                    val dd = sqrt((dl * dl + da * da + db * db).toDouble())
                    if (dd < bestD) { bestD = dd; k = c }
                }
                if (k < 0) { if (centres.size < 40) { centres.add(doubleArrayOf(img.L[i].toDouble(), img.A[i].toDouble(), img.B[i].toDouble())); counts.add(1) } }
                else counts[k] = counts[k] + 1
                total++
                x += 2
            }
            y += 2
        }
        return centres.indices.filter { counts[it] >= 0.12 * total }.sortedByDescending { counts[it] }.map { centres[it] }
    }

    /** Distance from the background: 0 for pixels matching a dominant band colour (patterned backgrounds), else [rowDist]. */
    private fun bgDistance(img: LabImage, i: Int, rowDist: Double, modes: List<DoubleArray>): Double {
        for (m in modes) {
            val ml = img.L[i] - m[0]; val ma = img.A[i] - m[1]; val mb = img.B[i] - m[2]
            if (sqrt((ml * ml + ma * ma + mb * mb).toDouble()) < 8.0) return 0.0
        }
        return rowDist
    }

    private fun chan(img: LabImage, k: Int, i: Int): Float = when (k) { 0 -> img.L[i]; 1 -> img.A[i]; else -> img.B[i] }

    // ---- morphology / components ---------------------------------------------------------------------------------

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            var run = 0
            // horizontal pass using last-true distance
            var last = -1000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= r) tmp[y * w + x] = true }
            last = 1_000_000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= r) tmp[y * w + x] = true }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -1000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= r) out[y * w + x] = true }
            last = 1_000_000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= r) out[y * w + x] = true }
        }
        return out
    }

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(w * h) { !m[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(w * h) { !d[it] }
    }

    private fun closeAndFill(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val closed = erode(dilate(m, w, h, r), w, h, r)
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(i: Int) { if (!closed[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1); if (x < w - 1) seed(i + 1); if (y > 0) seed(i - w); if (y < h - 1) seed(i + w)
        }
        return BooleanArray(w * h) { closed[it] || !outside[it] }
    }

    private fun components(m: BooleanArray, delta: FloatArray, w: Int, h: Int, s: Double, regionH: Int, regionW: Int, xaEdge: Int, xbEdge: Int, rejected: MutableList<String>, barrierTop: IntArray = IntArray(1)): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Blob>()
        for (start in 0 until w * h) {
            if (!m[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var area = 0
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                members.add(i)
                val x = i % w; val y = i / w
                area++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (m[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (area < (0.25 * s) * (0.25 * s)) continue
            // structure that spans the whole tray band (panel rims, stripe edges, backdrops) or is sparse (texture noise) is not a piece
            if (bw >= 0.9 * regionW) {
                barrierTop[0] = min(barrierTop[0], minY)
                // a thin rim is harmless, but a wide AND tall region means the background merged with something that may be a piece
                if (bh >= 0.35 * regionH) rejected.add("tray background merged with a shape (${"%.1f".format(bw / s)}x${"%.1f".format(bh / s)} cells): a piece may be hidden")
                continue
            }
            // a solid, piece-sized-or-taller shape spanning the band height is NOT silently dropped (it may be a 5-bar in a short
            // band): it falls through to the oversize check below, which records a note so the reading abstains.
            if (bh >= 0.9 * regionH && (area < 0.6 * bw * bh || bw < 0.3 * s || bw > 5.4 * s)) continue
            // tall strip hugging the left/right edge of the band: the outside of a narrower tray panel, not a piece
            if ((minX <= 1 + xaEdge || maxX >= xbEdge - 2) && bh >= 0.7 * regionH) continue
            if (area < 0.4 * bw * bh) {
                // sparse: texture noise... unless it is piece-sized, in which case it may be a real piece damaged by noise
                if (bw.toDouble() in 0.6 * s..3.3 * s && bh.toDouble() in 0.6 * s..3.3 * s && area >= 0.12 * bw * bh) rejected.add("a piece-sized sparse shape in the tray was ignored: noise or a damaged piece?")
                continue
            }
            if (bw > 5.4 * s || bh > 5.4 * s || bh > 0.75 * regionH) {
                rejected.add("an oversized shape (${"%.1f".format(bw / s)}x${"%.1f".format(bh / s)} cells) in the tray was ignored: background or merged pieces?")
                continue
            }
            if (bw < 0.3 * s || bh < 0.3 * s) continue
            val bm = BooleanArray(w * h)
            for (i in members) bm[i] = true
            out.add(Blob(minX, minY, maxX + 1, maxY + 1, area, bm, 0.0))
        }
        return out
    }

    // ---- pitch ---------------------------------------------------------------------------------------------------

    private class PitchChoice(val c: Double, val fitConfidence: Double, val ambiguous: Boolean, val notes: List<String>)

    private fun dimErr(v: Double): Double {
        val r = v.roundToInt()
        return if (r < 1 || r > 5) 4.0 else abs(v - r)
    }

    /** Fraction of interior cell-boundary positions that show edge energy inside the blob, for pitch [c]. */
    private fun latticeSupport(img: LabImage, b: Blob, c: Double, y0: Int): Double {
        var hit = 0; var tot = 0
        val nx = (b.w / c).roundToInt(); val ny = (b.h / c).roundToInt()
        val hw = max(1.0, 0.1 * c)
        for (k in 1 until nx) {
            val x = b.x0 + k * b.w.toDouble() / nx
            val line = img.iGX.sum((x - hw).toInt(), y0 + b.y0, (x + hw).toInt() + 1, y0 + b.y1)
            val mid = img.iGX.sum((x + 0.5 * c - hw).toInt(), y0 + b.y0, (x + 0.5 * c + hw).toInt() + 1, y0 + b.y1) * 0.0
            val ref = img.iGX.sum((x - 0.5 * c - hw).toInt(), y0 + b.y0, (x - 0.5 * c + hw).toInt() + 1, y0 + b.y1)
            tot++; if (line > 1.5 * ref + 1.0 + mid) hit++
        }
        for (k in 1 until ny) {
            val y = y0 + b.y0 + k * b.h.toDouble() / ny
            val line = img.iGY.sum(b.x0, (y - hw).toInt(), b.x1, (y + hw).toInt() + 1)
            val ref = img.iGY.sum(b.x0, (y - 0.5 * c - hw).toInt(), b.x1, (y - 0.5 * c + hw).toInt() + 1)
            tot++; if (line > 1.5 * ref + 1.0) hit++
        }
        return if (tot == 0) -1.0 else hit.toDouble() / tot
    }

    private fun choosePitch(img: LabImage, blobs: List<Blob>, s: Double, hint: Double?, trayY0: Int): PitchChoice {
        // the blob masks are in tray-row coordinates; lattice support needs absolute rows
        val lo = if (hint != null) 0.85 * hint * s else 0.28 * s
        val hi = if (hint != null) 1.15 * hint * s else 0.95 * s
        val cand = ArrayList<Pair<Double, Double>>() // pitch, cost
        var c = lo
        while (c <= hi) {
            var err = 0.0
            var sup = 0.0; var supN = 0
            for (b in blobs) {
                err += dimErr(b.w / c) + dimErr(b.h / c)
                val ls = latticeSupport(img, b, c, trayY0)
                if (ls >= 0) { sup += ls; supN++ }
            }
            err /= (2.0 * blobs.size)
            val lat = if (supN == 0) 0.5 else sup / supN
            val prior = if (hint != null) abs(c / s - hint) * 0.5 else abs(c / s - 0.5) * 0.04
            cand.add(c to (err + 0.15 * (1.0 - lat) + prior))
            c += 0.25
        }
        val best = cand.minByOrNull { it.second }!!
        val fitErr = blobs.sumOf { dimErr(it.w / best.first) + dimErr(it.h / best.first) } / (2.0 * blobs.size)
        val notes = ArrayList<String>()
        val other = cand.filter { abs(it.first - best.first) > 0.12 * best.first && it.second <= best.second + 0.03 }
        val ambiguous = other.isNotEmpty() && hint == null
        if (ambiguous) notes.add("tray cell size is ambiguous (pieces show no internal cell lines)")
        if (fitErr > 0.15) notes.add("piece sizes fit the tray cell size poorly (error ${"%.2f".format(fitErr)} cells)")
        val fitConf = (1.0 - fitErr / 0.3).coerceIn(0.0, 1.0)
        return PitchChoice(best.first, fitConf, ambiguous, notes)
    }
}
