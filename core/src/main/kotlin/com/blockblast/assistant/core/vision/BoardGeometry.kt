package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Board
import kotlin.math.abs

data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Where the 8x8 grid sits in screen pixels. All overlay drawing and all recognition use the same geometry,
 * so highlights are aligned with whatever was actually recognized (or manually calibrated).
 */
data class BoardGeometry(val left: Float, val top: Float, val cellSize: Float) {
    val right: Float get() = left + Board.SIZE * cellSize
    val bottom: Float get() = top + Board.SIZE * cellSize

    fun cellRect(x: Int, y: Int): FloatRect = FloatRect(
        left + x * cellSize, top + y * cellSize,
        left + (x + 1) * cellSize, top + (y + 1) * cellSize,
    )

    fun isSameAs(other: BoardGeometry, tolerance: Float = 0.12f): Boolean =
        abs(left - other.left) <= tolerance * cellSize &&
            abs(top - other.top) <= tolerance * cellSize &&
            abs(cellSize - other.cellSize) <= tolerance * cellSize
}
