package com.blockblast.assistant.core.vision

/** An immutable-by-convention ARGB frame (0xAARRGGBB per pixel, row-major). Android-free so vision is testable on the JVM. */
class PixelFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "Frame must be non-empty" }
        require(argb.size == width * height) { "Pixel buffer size does not match ${width}x$height" }
    }

    fun pixel(x: Int, y: Int): Int = argb[y * width + x]
    fun luma(x: Int, y: Int): Int = Px.luma(argb[y * width + x])
}

object Px {
    fun r(p: Int): Int = (p shr 16) and 0xFF
    fun g(p: Int): Int = (p shr 8) and 0xFF
    fun b(p: Int): Int = p and 0xFF
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun luma(p: Int): Int = (r(p) * 77 + g(p) * 150 + b(p) * 29) shr 8

    /** Crude colourfulness: max channel minus min channel (0..255). */
    fun saturation(p: Int): Int {
        val r = r(p)
        val g = g(p)
        val b = b(p)
        return maxOf(r, g, b) - minOf(r, g, b)
    }
}
