package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.PixelFrame
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Float RGB colour 0..255. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(o: Rgb, t: Float) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    fun scale(k: Float) = Rgb(r * k, g * k, b * k)
    fun lift(d: Float) = Rgb(r + d, g + d, b + d)
    fun packed(): Int = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    private fun c(v: Float) = v.coerceIn(0f, 255f).toInt()
    companion object {
        fun of(r: Int, g: Int, b: Int) = Rgb(r.toFloat(), g.toFloat(), b.toFloat())
        fun hsv(h: Float, s: Float, v: Float): Rgb {
            val hh = ((h % 1f) + 1f) % 1f * 6f
            val i = hh.toInt()
            val f = hh - i
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
            return Rgb(r * 255f, g * 255f, b * 255f)
        }
    }
}

enum class BgKind { SOLID, VGRAD, NOISE, STRIPES, DOTS }
enum class EmptyKind { FLAT, GRAD, BEVEL, CHECKER, NOISE }
enum class BlockKind { FLAT, GRAD, BEVEL, GLOSS, STRIPES, NOISE }

/** A complete visual theme. Everything the recognizer must NOT depend on is a field here. */
data class ThemeSpec(
    val name: String,
    val bgKind: BgKind, val bgA: Rgb, val bgB: Rgb,
    val panelColor: Rgb, val panelAlpha: Float, val panelPad: Float,
    val gapFrac: Float, val gapColor: Rgb, val cellRadius: Float,
    val emptyKind: EmptyKind, val emptyA: Rgb, val emptyB: Rgb,
    val blockKind: BlockKind, val palette: List<Rgb>,
    val outlineFrac: Float, val outlineColor: Rgb?, val shadow: Float,
    val trayPanel: Rgb?, val trayScale: Float, val trayGapFrac: Float,
    val boardWidthFrac: Float = 0.88f, val boardTopFrac: Float = 0.24f,
    val header: Boolean = true, val banner: Boolean = true,
)

/** Display / capture perturbations applied after rendering. */
data class Perturb(
    val gain: Float = 1f, val gamma: Float = 1f, val noise: Float = 0f, val blur: Int = 0,
    val resample: Float = 1f,
) {
    fun label() = "gain=$gain gamma=$gamma noise=$noise blur=$blur scale=$resample"
}

class Scene(val bits: Long, val pieces: List<Piece?>, val seed: Long)

object ThemeRenderer {

    fun render(t: ThemeSpec, scene: Scene, w: Int = 1080, h: Int = 2400, p: Perturb = Perturb()): PixelFrame {
        var f = renderRaw(t, scene, w, h)
        if (p.resample != 1f) f = resample(f, p.resample)
        if (p.blur > 0) f = boxBlur(f, p.blur)
        if (p.gain != 1f || p.gamma != 1f || p.noise > 0f) f = tone(f, p, scene.seed)
        return f
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var n = x * 374761393 + y * 668265263 + s * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }

    private fun renderRaw(t: ThemeSpec, scene: Scene, w: Int, h: Int): PixelFrame {
        val px = IntArray(w * h)
        val img = Array(3) { FloatArray(w * h) }
        fun put(i: Int, c: Rgb) { img[0][i] = c.r; img[1][i] = c.g; img[2][i] = c.b }
        fun get(i: Int) = Rgb(img[0][i], img[1][i], img[2][i])
        // background
        for (y in 0 until h) for (x in 0 until w) {
            val ty = y.toFloat() / h
            val c = when (t.bgKind) {
                BgKind.SOLID -> t.bgA
                BgKind.VGRAD -> t.bgA.mix(t.bgB, ty)
                BgKind.NOISE -> t.bgA.mix(t.bgB, hash(x / 3, y / 3, 7) * 0.9f)
                BgKind.STRIPES -> if (((x + y) / (w / 18)) % 2 == 0) t.bgA else t.bgA.mix(t.bgB, 0.6f)
                BgKind.DOTS -> {
                    val cx = (x % (w / 12)) - w / 24
                    val cy = (y % (w / 12)) - w / 24
                    if (cx * cx + cy * cy < (w / 60) * (w / 60)) t.bgB else t.bgA.mix(t.bgB, ty * 0.5f)
                }
            }
            put(y * w + x, c)
        }
        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, c: Rgb, alpha: Float = 1f) {
            for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
                val i = y * w + x
                put(i, get(i).mix(c, alpha))
            }
        }
        if (t.header) fillRect(0, 0, w, (h * 0.07f).toInt(), t.bgB.lift(60f).mix(Rgb.of(240, 240, 240), 0.4f))

        val board = (w * t.boardWidthFrac)
        val cell = board / 8f
        val bl = (w - board) / 2f
        val bt = h * t.boardTopFrac
        val pad = t.panelPad * cell
        // board panel (possibly translucent)
        roundRect(img, w, h, bl - pad, bt - pad, bl + board + pad, bt + board + pad, cell * 0.25f, t.panelColor, t.panelAlpha)

        val gapPx = t.gapFrac * cell
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val x0 = bl + cx * cell + gapPx / 2
            val y0 = bt + cy * cell + gapPx / 2
            val size = cell - gapPx
            // gap tint under every cell: gives dark/light gaps or none
            val occ = (scene.bits ushr (cy * 8 + cx)) and 1L == 1L
            if (t.gapFrac > 0f) rectFill(img, w, h, bl + cx * cell, bt + cy * cell, bl + (cx + 1) * cell, bt + (cy + 1) * cell, t.gapColor, t.panelAlpha.coerceAtLeast(0.6f))
            if (!occ) {
                val col = emptyColor(t, cx, cy)
                drawCell(img, w, h, x0, y0, size, t.cellRadius * size, { u, v -> emptyFill(t, col, u, v, cx, cy) }, null, 0f, 0f, 1f)
            } else {
                val base = blockColor(t, scene.seed, cx, cy)
                drawCell(img, w, h, x0, y0, size, t.cellRadius * size, { u, v -> blockFill(t, base, u, v, cx * 8 + cy) }, t.outlineColor, t.outlineFrac * size, t.shadow, 1f)
            }
        }
        // tray
        val tcell = cell * t.trayScale
        val trayTop = bt + board + cell * 0.9f
        if (t.trayPanel != null) roundRect(img, w, h, bl - pad, trayTop, bl + board + pad, trayTop + cell * 4.6f, cell * 0.3f, t.trayPanel, 0.85f)
        scene.pieces.forEachIndexed { slot, piece ->
            if (piece == null) return@forEachIndexed
            val cxm = bl + board * (2 * slot + 1) / 6f
            val cym = trayTop + cell * 2.3f
            val x0 = cxm - piece.width * tcell / 2f
            val y0 = cym - piece.height * tcell / 2f
            val base = t.palette[(slot * 2 + 1 + (scene.seed % 3).toInt()).mod(t.palette.size)]
            val gp = t.trayGapFrac * tcell
            for (c in piece.cells) {
                drawCell(img, w, h, x0 + c.x * tcell + gp / 2, y0 + c.y * tcell + gp / 2, tcell - gp, t.cellRadius * (tcell - gp),
                    { u, v -> blockFill(t, base, u, v, c.x + 5 * c.y) }, t.outlineColor, t.outlineFrac * (tcell - gp), t.shadow, 1f)
            }
        }
        if (t.banner) fillRect(0, (h * 0.93f).toInt(), w, h, Rgb.of(235, 235, 235))
        for (i in 0 until w * h) px[i] = Rgb(img[0][i], img[1][i], img[2][i]).packed()
        return PixelFrame(w, h, px)
    }

    private fun emptyColor(t: ThemeSpec, cx: Int, cy: Int): Rgb =
        if (t.emptyKind == EmptyKind.CHECKER && (cx + cy) % 2 == 1) t.emptyB else t.emptyA

    private fun emptyFill(t: ThemeSpec, base: Rgb, u: Float, v: Float, cx: Int, cy: Int): Rgb = when (t.emptyKind) {
        EmptyKind.FLAT, EmptyKind.CHECKER -> base
        EmptyKind.GRAD -> base.mix(t.emptyB, v * 0.8f)
        EmptyKind.BEVEL -> base.lift((0.5f - v) * 10f + (0.5f - u) * 4f)
        EmptyKind.NOISE -> base.lift((hash((u * 40).toInt(), (v * 40).toInt(), cx * 8 + cy) - 0.5f) * 14f)
    }

    private fun blockColor(t: ThemeSpec, seed: Long, cx: Int, cy: Int): Rgb {
        val k = (hash(cx, cy, seed.toInt()) * t.palette.size).toInt().coerceAtMost(t.palette.size - 1)
        return t.palette[k]
    }

    private fun blockFill(t: ThemeSpec, base: Rgb, u: Float, v: Float, salt: Int): Rgb = when (t.blockKind) {
        BlockKind.FLAT -> base
        BlockKind.GRAD -> base.lift(25f).mix(base.scale(0.7f), v)
        BlockKind.BEVEL -> {
            val edge = min(min(u, 1 - u), min(v, 1 - v))
            var c = base
            if (edge < 0.16f) c = if (u + v < 1f) base.lift(40f) else base.scale(0.7f)
            c
        }
        BlockKind.GLOSS -> {
            var c = base.lift(10f).mix(base.scale(0.75f), v)
            if (v < 0.4f && u > 0.12f && u < 0.88f) c = c.mix(Rgb.of(255, 255, 255), 0.35f * (1f - v / 0.4f))
            c
        }
        BlockKind.STRIPES -> if (((u + v) * 5f).toInt() % 2 == 0) base else base.scale(0.82f)
        BlockKind.NOISE -> base.lift((hash((u * 30).toInt(), (v * 30).toInt(), salt) - 0.5f) * 40f)
    }

    // ---- primitives ----------------------------------------------------------------------------------------------

    private fun rectFill(img: Array<FloatArray>, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, c: Rgb, a: Float) {
        for (y in max(0, y0.toInt()) until min(h, y1.toInt() + 1)) for (x in max(0, x0.toInt()) until min(w, x1.toInt() + 1)) {
            if (x + 0.5f < x0 || x + 0.5f > x1 || y + 0.5f < y0 || y + 0.5f > y1) continue
            blend(img, y * w + x, c, a)
        }
    }

    private fun blend(img: Array<FloatArray>, i: Int, c: Rgb, a: Float) {
        img[0][i] += (c.r - img[0][i]) * a
        img[1][i] += (c.g - img[1][i]) * a
        img[2][i] += (c.b - img[2][i]) * a
    }

    private fun sdRound(px: Float, py: Float, x0: Float, y0: Float, size: Float, r: Float): Float {
        val cx = x0 + size / 2
        val cy = y0 + size / 2
        val qx = abs(px - cx) - (size / 2 - r)
        val qy = abs(py - cy) - (size / 2 - r)
        return sqrt(max(qx, 0f).pow(2) + max(qy, 0f).pow(2)) + min(max(qx, qy), 0f) - r
    }

    private fun roundRect(img: Array<FloatArray>, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, c: Rgb, a: Float) {
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val hx = (x1 - x0) / 2
        val hy = (y1 - y0) / 2
        for (y in max(0, y0.toInt() - 1) until min(h, y1.toInt() + 2)) for (x in max(0, x0.toInt() - 1) until min(w, x1.toInt() + 2)) {
            val qx = abs(x + 0.5f - cx) - (hx - r)
            val qy = abs(y + 0.5f - cy) - (hy - r)
            val sd = sqrt(max(qx, 0f).pow(2) + max(qy, 0f).pow(2)) + min(max(qx, qy), 0f) - r
            val cov = (0.5f - sd).coerceIn(0f, 1f)
            if (cov > 0f) blend(img, y * w + x, c, a * cov)
        }
    }

    private fun drawCell(
        img: Array<FloatArray>, w: Int, h: Int, x0: Float, y0: Float, size: Float, radius: Float,
        fill: (Float, Float) -> Rgb, outline: Rgb?, outlineW: Float, shadow: Float, alpha: Float,
    ) {
        val sh = if (shadow > 0f) size * 0.07f else 0f
        val ext = sh * 2.5f + 2f
        for (y in max(0, (y0 - ext).toInt()) until min(h, (y0 + size + ext).toInt() + 1)) {
            for (x in max(0, (x0 - ext).toInt()) until min(w, (x0 + size + ext).toInt() + 1)) {
                val i = y * w + x
                val pxc = x + 0.5f
                val pyc = y + 0.5f
                if (shadow > 0f) {
                    val sds = sdRound(pxc - sh * 0.5f, pyc - sh, x0, y0, size, radius)
                    val sc = (1f - sds / (sh * 1.5f + 1f)).coerceIn(0f, 1f)
                    if (sc > 0f && sds > 0f) blend(img, i, Rgb.of(0, 0, 0), shadow * sc * 0.5f)
                }
                val sd = sdRound(pxc, pyc, x0, y0, size, radius)
                val cov = (0.5f - sd).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val u = ((pxc - x0) / size).coerceIn(0f, 1f)
                val v = ((pyc - y0) / size).coerceIn(0f, 1f)
                var c = fill(u, v)
                if (outline != null && outlineW > 0f && sd > -outlineW) c = c.mix(outline, ((sd + outlineW) / 1.0f).coerceIn(0f, 1f))
                blend(img, i, c, cov * alpha)
            }
        }
    }

    // ---- perturbations -------------------------------------------------------------------------------------------

    private fun resample(f: PixelFrame, k: Float): PixelFrame {
        val nw = (f.width * k).toInt()
        val nh = (f.height * k).toInt()
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val sx = ((x + 0.5f) / k - 0.5f).coerceIn(0f, f.width - 1.001f)
            val sy = ((y + 0.5f) / k - 0.5f).coerceIn(0f, f.height - 1.001f)
            val x0 = sx.toInt(); val y0 = sy.toInt(); val fx = sx - x0; val fy = sy - y0
            fun ch(shift: Int): Int {
                val a = (f.pixel(x0, y0) shr shift) and 255; val b = (f.pixel(x0 + 1, y0) shr shift) and 255
                val c = (f.pixel(x0, y0 + 1) shr shift) and 255; val d = (f.pixel(x0 + 1, y0 + 1) shr shift) and 255
                return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy).toInt()
            }
            out[y * nw + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return PixelFrame(nw, nh, out)
    }

    private fun boxBlur(f: PixelFrame, r: Int): PixelFrame {
        val out = IntArray(f.width * f.height)
        val tmp = Array(3) { FloatArray(f.width * f.height) }
        for (y in 0 until f.height) for (x in 0 until f.width) {
            var a = 0f; var b = 0f; var c = 0f; var n = 0
            for (dx in -r..r) { val xx = (x + dx).coerceIn(0, f.width - 1); val p = f.pixel(xx, y); a += (p shr 16) and 255; b += (p shr 8) and 255; c += p and 255; n++ }
            val i = y * f.width + x; tmp[0][i] = a / n; tmp[1][i] = b / n; tmp[2][i] = c / n
        }
        for (y in 0 until f.height) for (x in 0 until f.width) {
            var a = 0f; var b = 0f; var c = 0f; var n = 0
            for (dy in -r..r) { val i = (y + dy).coerceIn(0, f.height - 1) * f.width + x; a += tmp[0][i]; b += tmp[1][i]; c += tmp[2][i]; n++ }
            out[y * f.width + x] = Rgb(a / n, b / n, c / n).packed()
        }
        return PixelFrame(f.width, f.height, out)
    }

    private fun tone(f: PixelFrame, p: Perturb, seed: Long): PixelFrame {
        val rnd = Random(seed * 31 + 5)
        val out = IntArray(f.width * f.height)
        for (i in out.indices) {
            val q = f.argb[i]
            fun ch(v: Int): Float {
                var x = (v / 255f).pow(p.gamma) * 255f * p.gain
                if (p.noise > 0f) x += (rnd.nextGaussian() * p.noise).toFloat()
                return x
            }
            out[i] = Rgb(ch((q shr 16) and 255), ch((q shr 8) and 255), ch(q and 255)).packed()
        }
        return PixelFrame(f.width, f.height, out)
    }
}
