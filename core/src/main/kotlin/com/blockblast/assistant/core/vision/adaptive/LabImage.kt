package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.vision.PixelFrame
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Summed-area table: O(1) sums/means over any axis-aligned rectangle of a plane. */
class Integral(val w: Int, val h: Int, private val t: DoubleArray) {
    /** Sum over [x0, x1) x [y0, y1), clamped to the plane. */
    fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val a = x0.coerceIn(0, w)
        val b = x1.coerceIn(0, w)
        val c = y0.coerceIn(0, h)
        val d = y1.coerceIn(0, h)
        if (b <= a || d <= c) return 0.0
        val s = w + 1
        return t[d * s + b] - t[c * s + b] - t[d * s + a] + t[c * s + a]
    }

    companion object {
        fun of(w: Int, h: Int, value: (Int) -> Double): Integral {
            val s = w + 1
            val t = DoubleArray(s * (h + 1))
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    row += value(y * w + x)
                    t[(y + 1) * s + x + 1] = t[y * s + x + 1] + row
                }
            }
            return Integral(w, h, t)
        }
    }
}

/**
 * Perceptual working copy of a frame: area-averaged down-scale, CIE Lab planes, gradient planes and lazily built
 * integral images. All vision thresholds in the adaptive package are expressed as COLOUR DIFFERENCES (Lab delta-E) or as
 * ratios, never as absolute RGB values, which is what makes them theme independent.
 *
 * Coordinates: pixel i of this image spans [i, i+1) in "working units"; multiply by [scale] for full-resolution pixels.
 */
class LabImage private constructor(
    val width: Int,
    val height: Int,
    val scale: Int,
    val L: FloatArray,
    val A: FloatArray,
    val B: FloatArray,
) {
    /** |d/dx| and |d/dy| as Lab delta-E per two pixels (central difference). */
    val gx = FloatArray(width * height)
    val gy = FloatArray(width * height)

    init {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val xa = i - (if (x > 0) 1 else 0)
            val xb = i + (if (x < width - 1) 1 else 0)
            val ya = i - (if (y > 0) width else 0)
            val yb = i + (if (y < height - 1) width else 0)
            gx[i] = de(xa, xb).toFloat() / 2f
            gy[i] = de(ya, yb).toFloat() / 2f
        }
    }

    private fun de(i: Int, j: Int): Double {
        val dl = (L[i] - L[j]).toDouble()
        val da = (A[i] - A[j]).toDouble()
        val db = (B[i] - B[j]).toDouble()
        return sqrt(dl * dl + da * da + db * db)
    }

    val iL: Integral by lazy { Integral.of(width, height) { L[it].toDouble() } }
    val iA: Integral by lazy { Integral.of(width, height) { A[it].toDouble() } }
    val iB: Integral by lazy { Integral.of(width, height) { B[it].toDouble() } }
    val iSS: Integral by lazy { Integral.of(width, height) { (L[it] * L[it] + A[it] * A[it] + B[it] * B[it]).toDouble() } }
    val iG: Integral by lazy { Integral.of(width, height) { sqrt((gx[it] * gx[it] + gy[it] * gy[it]).toDouble()) } }
    val iGX: Integral by lazy { Integral.of(width, height) { gx[it].toDouble() } }
    val iGY: Integral by lazy { Integral.of(width, height) { gy[it].toDouble() } }

    /** Mean Lab colour of a rectangle given in working units (rounded to whole pixels, at least 1 px). */
    fun meanLab(x0: Double, y0: Double, x1: Double, y1: Double, out: DoubleArray) {
        val (a, b, c, d) = box(x0, y0, x1, y1)
        val n = ((b - a) * (d - c)).toDouble().coerceAtLeast(1.0)
        out[0] = iL.sum(a, c, b, d) / n
        out[1] = iA.sum(a, c, b, d) / n
        out[2] = iB.sum(a, c, b, d) / n
    }

    /** RMS colour deviation (Lab units) inside a rectangle: a texture measure. */
    fun stdLab(x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val (a, b, c, d) = box(x0, y0, x1, y1)
        val n = ((b - a) * (d - c)).toDouble().coerceAtLeast(1.0)
        val mL = iL.sum(a, c, b, d) / n
        val mA = iA.sum(a, c, b, d) / n
        val mB = iB.sum(a, c, b, d) / n
        val v = iSS.sum(a, c, b, d) / n - (mL * mL + mA * mA + mB * mB)
        return sqrt(max(0.0, v))
    }

    fun meanGradient(x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val (a, b, c, d) = box(x0, y0, x1, y1)
        val n = ((b - a) * (d - c)).toDouble().coerceAtLeast(1.0)
        return iG.sum(a, c, b, d) / n
    }

    fun meanL(x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val (a, b, c, d) = box(x0, y0, x1, y1)
        val n = ((b - a) * (d - c)).toDouble().coerceAtLeast(1.0)
        return iL.sum(a, c, b, d) / n
    }

    private data class Box(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun box(x0: Double, y0: Double, x1: Double, y1: Double): Box {
        var a = x0.roundToInt().coerceIn(0, width - 1)
        var b = x1.roundToInt().coerceIn(1, width)
        var c = y0.roundToInt().coerceIn(0, height - 1)
        var d = y1.roundToInt().coerceIn(1, height)
        if (b <= a) b = min(width, a + 1).also { if (it == a) a = max(0, a - 1) }
        if (d <= c) d = min(height, c + 1).also { if (it == c) c = max(0, c - 1) }
        return Box(a, b, c, d)
    }

    companion object {
        private val LIN = FloatArray(256) { v ->
            val c = v / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }

        /** Working scale so that the working image is about [targetWidth] pixels wide. */
        fun scaleFor(frameWidth: Int, targetWidth: Int = 540): Int = max(1, (frameWidth / targetWidth.toDouble()).roundToInt())

        fun of(frame: PixelFrame, scale: Int = scaleFor(frame.width)): LabImage {
            val d = max(1, scale)
            val w = max(1, frame.width / d)
            val h = max(1, frame.height / d)
            val L = FloatArray(w * h)
            val A = FloatArray(w * h)
            val B = FloatArray(w * h)
            val area = d * d
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until d) {
                    val base = (y * d + dy) * frame.width + x * d
                    for (dx in 0 until d) {
                        val p = frame.argb[base + dx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                lab(r / area, g / area, b / area, L, A, B, y * w + x)
            }
            return LabImage(w, h, d, L, A, B)
        }

        private fun lab(r: Int, g: Int, b: Int, L: FloatArray, A: FloatArray, B: FloatArray, i: Int) {
            val rl = LIN[r]
            val gl = LIN[g]
            val bl = LIN[b]
            val x = (0.4124564f * rl + 0.3575761f * gl + 0.1804375f * bl) / 0.95047f
            val y = 0.2126729f * rl + 0.7151522f * gl + 0.0721750f * bl
            val z = (0.0193339f * rl + 0.1191920f * gl + 0.9503041f * bl) / 1.08883f
            val fx = f(x)
            val fy = f(y)
            val fz = f(z)
            L[i] = 116f * fy - 16f
            A[i] = 500f * (fx - fy)
            B[i] = 200f * (fy - fz)
        }

        private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
    }
}
