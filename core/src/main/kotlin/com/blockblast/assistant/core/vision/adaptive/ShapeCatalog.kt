package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece

/**
 * Shapes commonly seen in Block Blast-style games, used only as a SOFT prior: a read shape that is connected and in the
 * catalog gets a confidence bonus, a connected shape that is not in the catalog is flagged (it may be a legitimate rare
 * piece or a misread), a disconnected shape is treated as a misread. The catalog is an unverified assumption.
 */
object ShapeCatalog {
    private val bases: List<Piece> = listOf(
        Piece.parse("#"),
        Piece.parse("##"), Piece.parse("###"), Piece.parse("####"), Piece.parse("#####"),
        Piece.parse("##", "##"), Piece.parse("###", "###", "###"), Piece.parse("###", "###"),
        Piece.parse("#.", "##"),
        Piece.parse("#.", "#.", "##"), Piece.parse("#..", "#..", "###"),
        Piece.parse("#.", "#.", "#.", "##"), Piece.parse("#...", "####"),
        Piece.parse("###", ".#."), Piece.parse("###", ".#.", ".#."),
        Piece.parse(".##", "##."),
    )

    val all: Set<Piece> by lazy {
        val out = LinkedHashSet<Piece>()
        for (b in bases) {
            var cells = b.cells
            repeat(2) {
                repeat(4) {
                    out.add(Piece(cells))
                    cells = cells.map { Cell(-it.y, it.x) } // rotate 90 degrees
                }
                cells = cells.map { Cell(-it.x, it.y) } // mirror
            }
        }
        out
    }

    fun isKnown(p: Piece): Boolean = p in all

    /** True when every cell can be reached from every other through edge-adjacent cells. */
    fun isConnected(p: Piece): Boolean {
        val set = p.cells.toHashSet()
        val seen = HashSet<Cell>()
        val stack = ArrayDeque<Cell>()
        stack.add(p.cells.first())
        seen.add(p.cells.first())
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = Cell(c.x + dx, c.y + dy)
                if (n in set && seen.add(n)) stack.add(n)
            }
        }
        return seen.size == set.size
    }
}
