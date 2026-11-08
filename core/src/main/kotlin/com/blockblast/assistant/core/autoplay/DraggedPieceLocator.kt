package com.blockblast.assistant.core.autoplay

import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.PixelFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the piece that is being dragged (finger still down) by comparing the screen BEFORE the drag with the screen
 * NOW. Many games draw the dragged piece well above the finger, so the finger position says little about where the
 * piece will land; this measures where it really is.
 *
 * A candidate is a changed-pixel blob whose size is a whole piece at one of the given cell sizes (board cell size, tray
 * cell size) and whose individual cells match the piece shape exactly. Candidates at the spot the piece was picked up
 * from (it has vanished from the tray) or far from the finger are ignored. Returns null when nothing plausible is found:
 * the caller must then cancel the drag rather than guess. Known limit: a piece hovering over blocks of its own colour
 * produces no change there and may not be found (-> cancel).
 */
object DraggedPieceLocator {
    class Found(val left: Float, val top: Float, val right: Float, val bottom: Float, val cellPx: Double) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private const val CHANGE_THRESHOLD = 70

    fun locate(
        before: PixelFrame, now: PixelFrame, piece: Piece, cellSizes: List<Double>,
        fingerX: Float, fingerY: Float, ignoreX: Float, ignoreY: Float, ignoreRadius: Float, maxFingerDistance: Float,
    ): Found? {
        if (before.width != now.width || before.height != now.height || cellSizes.isEmpty()) return null
        val c0 = cellSizes.minOrNull() ?: return null
        val step = max(2, (c0 / 12.0).toInt())
        val gw = (before.width + step - 1) / step
        val gh = (before.height + step - 1) / step
        val mask = Array(gh) { BooleanArray(gw) }
        for (y in 0 until gh) for (x in 0 until gw) {
            val px = min(before.width - 1, x * step)
            val py = min(before.height - 1, y * step)
            val a = before.pixel(px, py)
            val b = now.pixel(px, py)
            val d = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
            mask[y][x] = d > CHANGE_THRESHOLD
        }
        val dil = Array(gh) { BooleanArray(gw) }
        for (y in 0 until gh) for (x in 0 until gw) if (mask[y][x]) {
            for (dy in -1..1) for (dx in -1..1) {
                val ny = y + dy; val nx = x + dx
                if (ny in 0 until gh && nx in 0 until gw) dil[ny][nx] = true
            }
        }
        val shape = piece.cells.map { it.x to it.y }.toHashSet()
        val pw = piece.width
        val ph = piece.height
        val seen = Array(gh) { BooleanArray(gw) }
        val stack = IntArray(gw * gh)
        var best: Found? = null
        var bestDist = Double.MAX_VALUE
        for (sy in 0 until gh) for (sx in 0 until gw) {
            if (!dil[sy][sx] || seen[sy][sx]) continue
            var sp = 0
            stack[sp++] = sy * gw + sx
            seen[sy][sx] = true
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1; var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % gw; val cy = cur / gw
                if (mask[cy][cx]) { count++; minX = min(minX, cx); maxX = max(maxX, cx); minY = min(minY, cy); maxY = max(maxY, cy) }
                for (k in 0 until 4) {
                    val nx = cx + DX[k]; val ny = cy + DY[k]
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    if (dil[ny][nx] && !seen[ny][nx]) { seen[ny][nx] = true; stack[sp++] = ny * gw + nx }
                }
            }
            if (count < 4 || maxX < 0) continue
            val left = minX * step; val right = (maxX + 1) * step
            val top = minY * step; val bottom = (maxY + 1) * step
            val centerX = (left + right) / 2f; val centerY = (top + bottom) / 2f
            if (hypot((centerX - ignoreX).toDouble(), (centerY - ignoreY).toDouble()) <= ignoreRadius) continue
            val distToFinger = hypot((centerX - fingerX).toDouble(), (centerY - fingerY).toDouble())
            if (distToFinger > maxFingerDistance) continue
            for (c in cellSizes) {
                if (abs((right - left) - pw * c) > 0.3 * c || abs((bottom - top) - ph * c) > 0.3 * c) continue
                val cw = (right - left).toDouble() / pw
                val ch = (bottom - top).toDouble() / ph
                var ok = true
                for (j in 0 until ph) for (i in 0 until pw) {
                    val gx = ((left + (i + 0.5) * cw) / step).toInt().coerceIn(0, gw - 1)
                    val gy = ((top + (j + 0.5) * ch) / step).toInt().coerceIn(0, gh - 1)
                    val rr = max(1, (0.2 * min(cw, ch) / step).toInt())
                    var on = 0; var tot = 0
                    for (dy in -rr..rr) for (dx in -rr..rr) {
                        val ny = gy + dy; val nx = gx + dx
                        if (ny in 0 until gh && nx in 0 until gw) { tot++; if (mask[ny][nx]) on++ }
                    }
                    val filled = tot > 0 && on.toDouble() / tot >= 0.5
                    if (filled != ((i to j) in shape)) ok = false
                }
                if (ok && distToFinger < bestDist) {
                    bestDist = distToFinger
                    best = Found(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), c)
                }
            }
        }
        return best
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)
}
