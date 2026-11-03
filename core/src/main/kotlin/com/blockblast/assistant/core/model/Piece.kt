package com.blockblast.assistant.core.model

/** A grid coordinate. [x] is the column (left to right), [y] is the row (top to bottom). */
data class Cell(val x: Int, val y: Int)

/**
 * A block shape as a set of relative cells, normalized so the top-left of its bounding box is (0, 0).
 * Two pieces with the same shape are equal regardless of the coordinates they were built from.
 * Pieces are immutable. Rotation is not modelled: the assistant only places shapes as observed on screen.
 */
class Piece(cells: Collection<Cell>) {
    val cells: List<Cell>
    val width: Int
    val height: Int

    init {
        require(cells.isNotEmpty()) { "A piece needs at least one cell" }
        val minX = cells.minOf { it.x }
        val minY = cells.minOf { it.y }
        this.cells = cells
            .map { Cell(it.x - minX, it.y - minY) }
            .distinct()
            .sortedWith(compareBy({ it.y }, { it.x }))
        width = this.cells.maxOf { it.x } + 1
        height = this.cells.maxOf { it.y } + 1
    }

    val size: Int get() = cells.size

    /** masks[y * 8 + x] = bitboard of this piece with its top-left at (x, y), or 0 if it does not fit. */
    private val masks: LongArray by lazy {
        LongArray(Board.SIZE * Board.SIZE) { index ->
            val ox = index % Board.SIZE
            val oy = index / Board.SIZE
            var mask = 0L
            var fits = true
            for (c in this.cells) {
                val cx = ox + c.x
                val cy = oy + c.y
                if (cx >= Board.SIZE || cy >= Board.SIZE) {
                    fits = false
                    break
                }
                mask = mask or (1L shl (cy * Board.SIZE + cx))
            }
            if (fits) mask else 0L
        }
    }

    /** Bitboard for this piece placed with its top-left at (x, y); 0L if any cell would be off the board. */
    fun maskAt(x: Int, y: Int): Long {
        if (x < 0 || y < 0 || x >= Board.SIZE || y >= Board.SIZE) return 0L
        return masks[y * Board.SIZE + x]
    }

    override fun equals(other: Any?): Boolean = other is Piece && other.cells == cells
    override fun hashCode(): Int = cells.hashCode()
    override fun toString(): String = "Piece(${cells.joinToString { "${it.x},${it.y}" }})"

    companion object {
        /** Build a piece from rows of text where '#' is a filled cell, e.g. `Piece.parse("##", "#.")`. */
        fun parse(vararg rows: String): Piece {
            val cells = rows.flatMapIndexed { y, row ->
                row.mapIndexedNotNull { x, ch -> if (ch == '#') Cell(x, y) else null }
            }
            return Piece(cells)
        }
    }
}
