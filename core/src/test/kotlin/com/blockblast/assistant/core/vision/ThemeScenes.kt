package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Procedural, SYNTHETIC Block Blast-like scenes with randomised themes, used to measure how the recognizer copes with
 * themes it was not tuned on. They are NOT real screenshots and say nothing about accuracy on the real game.
 *
 * Varied per scene: light/dark, background (solid / gradient / noise), board panel (visible or not), gap width and
 * polarity, empty-cell style (flat / gradient / textured / inset), block palette and style (flat / gradient / textured /
 * bevel / outline / decal), corner radius, tray piece scale and shadows, brightness and contrast (display brightness),
 * pixel noise, resolution and aspect ratio (display scaling), header clutter and an ad banner. Kinds: "friendly",
 * "normal", "lowcontrast" (blocks nearly as bright as empty cells, told apart by hue/bevel/outline) and "samecolor"
 * (blocks the same colour as empty cells, told apart only by bevel/outline/decal).
 */
object ThemeScenes {
    class Scene(
        val frame: PixelFrame,
        val geometry: BoardGeometry,
        val bits: Long,
        val pieces: List<Piece?>,
        val kind: String,
        val description: String,
    )

    private val SHAPES = listOf(
        arrayOf("#"), arrayOf("##"), arrayOf("###"), arrayOf("####"), arrayOf("#####"),
        arrayOf("#", "#"), arrayOf("#", "#", "#"), arrayOf("#", "#", "#", "#"), arrayOf("##", "##"),
        arrayOf("#.", "#.", "##"), arrayOf(".#", ".#", "##"), arrayOf("###", ".#."), arrayOf("##.", ".##"),
        arrayOf(".##", "##."), arrayOf("###", "###"), arrayOf("###", "###", "###"), arrayOf("#.", "##"), arrayOf("##", "#."),
    )
    private val SIZES = listOf(540 to 1200, 720 to 1600, 720 to 1560, 1080 to 1920, 1080 to 2340)

    private fun hsv(h: Double, s: Double, v: Double): DoubleArray {
        val hh = ((h % 1.0) + 1.0) % 1.0 * 6.0
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val rgb = when (i % 6) { 0 -> doubleArrayOf(v, t, p); 1 -> doubleArrayOf(q, v, p); 2 -> doubleArrayOf(p, v, t)
            3 -> doubleArrayOf(p, q, v); 4 -> doubleArrayOf(t, p, v); else -> doubleArrayOf(v, p, q) }
        return DoubleArray(3) { rgb[it] * 255.0 }
    }

    private class Theme(
        val dark: Boolean, val bg1: DoubleArray, val bg2: DoubleArray, val bgKind: Int, val panel: DoubleArray,
        val panelVisible: Boolean, val empty: DoubleArray, val emptyStyle: Int, val gap: Double,
        val palette: List<DoubleArray>, val blockStyle: Int, val radius: Double, val outline: Double,
        val outlineColor: DoubleArray, val trayScale: Double, val trayShadow: Boolean,
        val brightness: Double, val contrast: Double, val noise: Double,
    )

    private fun u(r: Random, a: Double, b: Double) = a + r.nextDouble() * (b - a)
    private fun clamp255(v: Double) = if (v < 0.0) 0.0 else if (v > 255.0) 255.0 else v

    private fun makeTheme(r: Random, kind: String): Theme {
        val dark = r.nextBoolean()
        val h0 = r.nextDouble()
        val bg1 = if (dark) hsv(h0, u(r, .2, .7), u(r, .18, .5)) else hsv(h0, u(r, .05, .4), u(r, .8, .97))
        val panel = if (dark) hsv(h0 + u(r, -.05, .05), u(r, .2, .6), u(r, .1, .3)) else hsv(h0 + u(r, -.05, .05), u(r, .05, .3), u(r, .65, .9))
        val lift = if (dark) u(r, 6.0, 28.0) else u(r, 8.0, 30.0)
        val empty = DoubleArray(3) { clamp255(panel[it] + lift) }
        val friendly = kind == "friendly"
        val nb = 3 + r.nextInt(4)
        var palette = (0 until nb).map { val h = r.nextDouble(); if (dark) hsv(h, u(r, .45, .9), u(r, .7, .98)) else hsv(h, u(r, .4, .9), u(r, .5, .85)) }
        var blockStyle = r.nextInt(7)
        if (friendly) blockStyle = intArrayOf(0, 1, 3, 5, 2)[r.nextInt(5)]
        if (kind == "lowcontrast") {
            val eL = (empty[0] + empty[1] + empty[2]) / 3.0
            palette = (0 until nb).map {
                val c = hsv(r.nextDouble(), u(r, .25, .55), 0.5)
                val m = max((c[0] + c[1] + c[2]) / 3.0, 1.0)
                DoubleArray(3) { k -> clamp255(c[k] * (eL / m) + u(r, -6.0, 6.0)) }
            }
            blockStyle = intArrayOf(3, 4, 6)[r.nextInt(3)]
        }
        if (kind == "samecolor") { palette = List(3) { empty.copyOf() }; blockStyle = intArrayOf(4, 6)[r.nextInt(2)] }
        val bright = if (!friendly && r.nextDouble() < .6) u(r, .55, 1.15) else 1.0
        val contrast = if (!friendly && r.nextDouble() < .4) u(r, .8, 1.1) else 1.0
        return Theme(
            dark, bg1, DoubleArray(3) { clamp255(bg1[it] + u(r, -35.0, 35.0)) }, if (friendly) 0 else r.nextInt(3), panel,
            friendly || r.nextDouble() < .85, empty, if (friendly) r.nextInt(3) else r.nextInt(4),
            if (friendly) u(r, .04, .08) else u(r, .03, .09), palette, blockStyle, u(r, 0.0, .3), u(r, .03, .08),
            DoubleArray(3) { clamp255(r.nextDouble() * 255 * 0.6 + (if (dark) 30 else 0)) }, u(r, .4, .65), r.nextDouble() < .6,
            bright, contrast, if (friendly) 0.0 else doubleArrayOf(0.0, 0.0, 1.5, 3.0)[r.nextInt(4)],
        )
    }

    private class Canvas(val w: Int, val h: Int) {
        val px = FloatArray(w * h * 3)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: DoubleArray) {
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
                val i = (y * w + x) * 3; px[i] = c[0].toFloat(); px[i + 1] = c[1].toFloat(); px[i + 2] = c[2].toFloat()
            }
        }
        fun blit(patch: FloatArray, mask: BooleanArray, n: Int, x0: Int, y0: Int, alpha: Double) {
            for (y in 0 until n) for (x in 0 until n) {
                val cx = x0 + x; val cy = y0 + y
                if (!mask[y * n + x] || cx < 0 || cy < 0 || cx >= w || cy >= h) continue
                val i = (cy * w + cx) * 3; val j = (y * n + x) * 3
                for (k in 0 until 3) px[i + k] = (px[i + k] * (1 - alpha) + patch[j + k] * alpha).toFloat()
            }
        }
    }

    private fun roundedMask(n: Int, radius: Double): BooleanArray {
        val r = radius * n
        return BooleanArray(n * n) { idx ->
            val x = idx % n + 0.5; val y = idx / n + 0.5
            val cx = x.coerceIn(r, n - r); val cy = y.coerceIn(r, n - r)
            (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r + 1e-6
        }
    }

    /** style: 0 flat, 1 gradient, 2 textured, 3 bevel (or inset for empty cells), 4 outline, 5 decal, 6 bevel + decal. */
    private fun tile(n: Int, base: DoubleArray, style: Int, t: Theme, r: Random, empty: Boolean): Pair<FloatArray, BooleanArray> {
        val img = FloatArray(n * n * 3)
        val bw = max(1, (n * 0.1).toInt())
        val ow = max(1, (n * t.outline).toInt())
        for (y in 0 until n) for (x in 0 until n) {
            val yy = if (n > 1) y.toDouble() / (n - 1) else 0.0
            var f = 1.0
            if (style == 1) f = 1.15 - 0.3 * yy
            val noise = if (style == 2) r.nextGaussian() * 7.0 else 0.0
            var add = 0.0
            val bevelLike = (!empty && (style == 3 || style == 6)) || (empty && style == 3)
            if (bevelLike) {
                val sign = if (empty) -1.0 else 1.0
                if (y < bw) add += 28 * sign
                if (x < bw) add += 20 * sign
                if (y >= n - bw) add -= 28 * sign
                if (x >= n - bw) add -= 20 * sign
            }
            for (k in 0 until 3) img[(y * n + x) * 3 + k] = clamp255(base[k] * f + noise + add).toFloat()
            if (!empty && style == 4 && (y < ow || x < ow || y >= n - ow || x >= n - ow)) for (k in 0 until 3) img[(y * n + x) * 3 + k] = t.outlineColor[k].toFloat()
            if (!empty && (style == 5 || style == 6)) {
                val dx = x - n / 2.0; val dy = y - n / 2.0
                if (dx * dx + dy * dy < (n * 0.22) * (n * 0.22)) for (k in 0 until 3) {
                    val tgt = if (t.dark) 255.0 else 40.0
                    img[(y * n + x) * 3 + k] = (img[(y * n + x) * 3 + k] * 0.8 + tgt * 0.2).toFloat()
                }
            }
        }
        return img to roundedMask(n, if (empty) t.radius * 0.8 else t.radius)
    }

    fun make(seed: Long, kind: String): Scene {
        val r = Random(seed)
        val t = makeTheme(r, kind)
        val (w, h) = SIZES[r.nextInt(SIZES.size)]
        val cv = Canvas(w, h)
        // background
        for (y in 0 until h) {
            val f = y.toDouble() / h
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                for (k in 0 until 3) cv.px[i + k] = (if (t.bgKind == 1) t.bg1[k] * (1 - f) + t.bg2[k] * f else t.bg1[k]).toFloat()
            }
        }
        if (t.bgKind == 2) {
            val gw = w / 16 + 1; val gh = h / 16 + 1
            val lo = DoubleArray(gw * gh) { r.nextGaussian() * 10 }
            for (y in 0 until h) for (x in 0 until w) {
                val v = lo[(y / 16) * gw + x / 16] + r.nextGaussian() * 4
                val i = (y * w + x) * 3; for (k in 0 until 3) cv.px[i + k] += v.toFloat()
            }
        }
        for (i in 0 until 2 + r.nextInt(4)) {  // header clutter
            val x = (u(r, .05, .8) * w).toInt(); val y = (u(r, .05, .17) * h).toInt()
            cv.fill(x, y, x + (u(r, .05, .3) * w).toInt(), y + (u(r, .015, .05) * h).toInt(), DoubleArray(3) { r.nextDouble() * 255 })
        }
        // board layout
        val s = min(w * u(r, .84, .9) / 8.0, 0.8 * h / 12.6)
        val left = (w - 8 * s) / 2 + u(r, -.01, .01) * w
        val hi = (0.92 * h - 12.6 * s) / h
        val top = h * u(r, min(.18, hi * 0.7), min(.3, hi))
        val pad = w * 0.012
        if (t.panelVisible) cv.fill((left - pad).toInt(), (top - pad).toInt(), (left + 8 * s + pad).toInt(), (top + 8 * s + pad).toInt(), t.panel)
        val gap = max(2, (s * t.gap).toInt())
        var bits = 0L
        val density = u(r, .05, .6)
        for (i in 0 until 64) if (r.nextDouble() < density) bits = bits or (1L shl i)
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val x0 = (left + cx * s + gap / 2.0).roundToInt(); val y0 = (top + cy * s + gap / 2.0).roundToInt(); val n = (s - gap).roundToInt()
            val occupied = (bits ushr (cy * 8 + cx)) and 1L == 1L
            val (p, m) = if (occupied) tile(n, t.palette[r.nextInt(t.palette.size)], t.blockStyle, t, r, false) else tile(n, t.empty, t.emptyStyle, t, r, true)
            cv.blit(p, m, n, x0, y0, 1.0)
        }
        // tray
        val pieces = ArrayList<Piece?>()
        for (slot in 0 until 3) pieces.add(if (r.nextDouble() < .12) null else Piece.parse(*SHAPES[r.nextInt(SHAPES.size)]))
        if (pieces.all { it == null }) pieces[1] = Piece.parse("#")
        val bottom = top + 8 * s
        val c = s * t.trayScale
        val cyTray = bottom + s * u(r, 2.0, 2.8)
        for (slot in 0 until 3) {
            val pc = pieces[slot] ?: continue
            val col = t.palette[r.nextInt(t.palette.size)]
            val x0 = w * (2 * slot + 1) / 6.0 - pc.width * c / 2; val y0 = cyTray - pc.height * c / 2
            for (cell in pc.cells) {
                val xx = (x0 + cell.x * c).roundToInt(); val yy = (y0 + cell.y * c).roundToInt()
                val n = c.roundToInt() - max(1, (c * 0.04).toInt())
                if (t.trayShadow) cv.blit(FloatArray(n * n * 3), roundedMask(n, t.radius), n, xx + (c * .06).toInt(), yy + (c * .08).toInt(), 0.3)
                val (p, m) = tile(n, col, t.blockStyle, t, r, false)
                cv.blit(p, m, n, xx, yy, 1.0)
            }
        }
        // ad banner
        val by = (h * 0.93).toInt()
        cv.fill((w * .12).toInt(), by, (w * .88).toInt(), h, DoubleArray(3) { r.nextDouble() * 255 })
        for (i in 0 until 8) {
            val x = (u(r, .15, .8) * w).toInt(); val y = by + (u(r, 0.0, .04) * h).toInt()
            cv.fill(x, y, x + (.03 * w).toInt(), y + (.02 * h).toInt(), DoubleArray(3) { r.nextDouble() * 255 })
        }
        // display brightness / contrast / sensor noise
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val ch = IntArray(3) { k ->
                var v = cv.px[i * 3 + k].toDouble()
                if (t.noise > 0) v += r.nextGaussian() * t.noise
                v = ((v - 128) * t.contrast + 128) * t.brightness
                clamp255(v).toInt()
            }
            out[i] = Px.rgb(ch[0], ch[1], ch[2])
        }
        val desc = "${if (t.dark) "dark" else "light"} bg=${t.bgKind} panel=${t.panelVisible} emptyStyle=${t.emptyStyle} blockStyle=${t.blockStyle} " +
            "brightness=${"%.2f".format(t.brightness)} ${w}x$h"
        return Scene(PixelFrame(w, h, out), BoardGeometry(left.toFloat(), top.toFloat(), s.toFloat()), bits, pieces, kind, desc)
    }
}
