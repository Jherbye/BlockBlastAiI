package com.blockblast.assistant.app

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import com.blockblast.assistant.core.vision.PixelFrame
import java.nio.ByteOrder

/**
 * Observes the screen through MediaProjection. Frames are only converted to memory for analysis; nothing is saved
 * to disk. A VirtualDisplay only delivers a frame when the screen changes, so the last frame is cached.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    private val dpi: Int,
    handler: Handler,
) {
    private var reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null
    private var cached: PixelFrame? = null

    init {
        display = projection.createVirtualDisplay(
            "blockblast-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    @Synchronized
    fun latestFrame(): PixelFrame? {
        val image = reader.acquireLatestImage()
        if (image != null) {
            try {
                cached = convert(image)
            } finally {
                image.close()
            }
        }
        return cached
    }

    /** Forget the cached frame so the next [latestFrame] only returns something captured afterwards. */
    @Synchronized
    fun invalidate() {
        cached = null
    }

    /** Android 14+ forbids creating a second VirtualDisplay from one projection, so resize the existing one. */
    @Synchronized
    fun resize(width: Int, height: Int) {
        val old = reader
        val fresh = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display?.resize(width, height, dpi)
        display?.surface = fresh.surface
        reader = fresh
        old.close()
        cached = null
    }

    @Synchronized
    fun release() {
        display?.release()
        display = null
        reader.close()
    }

    private fun convert(image: Image): PixelFrame {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val strideInts = plane.rowStride / 4
        val ints = plane.buffer.order(ByteOrder.nativeOrder()).asIntBuffer()
        val out = IntArray(w * h)
        val row = IntArray(maxOf(strideInts, w))
        for (y in 0 until h) {
            ints.position(y * strideInts)
            val n = minOf(w, ints.remaining())
            ints.get(row, 0, n)
            val base = y * w
            for (x in 0 until n) {
                val p = row[x] // bytes R,G,B,A read little-endian => 0xAABBGGRR
                out[base + x] = (0xFF shl 24) or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p shr 16) and 0xFF)
            }
        }
        return PixelFrame(w, h, out)
    }
}
