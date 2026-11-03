package com.blockblast.assistant.core.model

/**
 * Immutable 8x8 occupancy grid stored as a 64-bit bitboard. Bit index = y * 8 + x.
 * Because instances are immutable and tiny, search states can be copied freely and compared by value.
 */
class Board private constructor(val bits: Long) {

    fun isOccupied(x: Int, y: Int): Boolean {
        require(x in 0 until SIZE && y in 0 until SIZE) { "($x,$y) is outside the board" }
        return ((bits ushr (y * SIZE + x)) and 1L) == 1L
    }

    val occupiedCount: Int get() = java.lang.Long.bitCount(bits)
    val emptyCount: Int get() = SIZE * SIZE - occupiedCount

    /** Empty cells whose four neighbours (or board edges) are all blocked: unusable by any piece. */
    fun isolatedEmptyCellCount(): Int {
        val e = bits.inv()
        val hasEmptyNeighbour =
            ((e shl 1) and NOT_COL0) or ((e ushr 1) and NOT_COL7) or (e shl SIZE) or (e ushr SIZE)
        return java.lang.Long.bitCount(e and hasEmptyNeighbour.inv())
    }

    /** Number of 4-connected regions of empty cells. */
    fun emptyRegionCount(): Int {
        val e = bits.inv()
        var rest = e
        var regions = 0
        while (rest != 0L) {
            val seed = rest and -rest
            rest = rest and fill(seed, e).inv()
            regions++
        }
        return regions
    }

    /** Rows as text, top to bottom, '#' occupied and '.' empty. */
    fun toRows(): List<String> = (0 until SIZE).map { y ->
        buildString { for (x in 0 until SIZE) append(if (isOccupied(x, y)) '#' else '.') }
    }

    override fun equals(other: Any?): Boolean = other is Board && other.bits == bits
    override fun hashCode(): Int = bits.hashCode()
    override fun toString(): String = toRows().joinToString("\n")

    companion object {
        const val SIZE = 8

        val EMPTY = Board(0L)

        val ROW_MASKS: LongArray = LongArray(SIZE) { y -> 0xFFL shl (y * SIZE) }
        val COL_MASKS: LongArray = LongArray(SIZE) { x ->
            var m = 0L
            for (y in 0 until SIZE) m = m or (1L shl (y * SIZE + x))
            m
        }

        private val NOT_COL0: Long = COL_MASKS[0].inv()
        private val NOT_COL7: Long = COL_MASKS[SIZE - 1].inv()

        fun ofBits(bits: Long): Board = Board(bits)

        /** Build from exactly 8 strings of 8 characters; '#' is occupied, anything else is empty. */
        fun fromRows(vararg rows: String): Board {
            require(rows.size == SIZE) { "Expected $SIZE rows, got ${rows.size}" }
            var bits = 0L
            rows.forEachIndexed { y, row ->
                require(row.length == SIZE) { "Row $y must have $SIZE characters" }
                row.forEachIndexed { x, ch -> if (ch == '#') bits = bits or (1L shl (y * SIZE + x)) }
            }
            return Board(bits)
        }

        private fun fill(seed: Long, region: Long): Long {
            var r = seed
            while (true) {
                val grown = (r or ((r shl 1) and NOT_COL0) or ((r ushr 1) and NOT_COL7) or
                    (r shl SIZE) or (r ushr SIZE)) and region
                if (grown == r) return r
                r = grown
            }
        }
    }
}
