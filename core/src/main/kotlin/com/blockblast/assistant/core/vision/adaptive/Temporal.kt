package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.RulesEngine
import com.blockblast.assistant.core.vision.BoardGeometry

/**
 * Temporal comparison of consecutive screenshots, per cell. A cell whose pixels did not change and whose label did not
 * change gains confidence with every frame; a cell whose pixels did NOT change but whose label flipped is a recognition
 * inconsistency and is capped. Cells whose pixels changed restart their history.
 */
class TemporalFusion {
    private var prevLab: Array<DoubleArray>? = null
    private var prevOcc: BooleanArray? = null
    private var prevConf: DoubleArray? = null
    private var prevGeometry: BoardGeometry? = null
    private val stable = IntArray(Board.SIZE * Board.SIZE)

    fun reset() {
        prevLab = null; prevOcc = null; prevConf = null; prevGeometry = null; stable.fill(0)
    }

    /** Fraction of cells whose pixels changed since the previous frame (null if there is no comparable frame). */
    var lastChangedFraction: Double? = null; private set
    var lastInconsistent: Int = 0; private set

    fun apply(g: BoardGeometry, f: CellFeatures, cells: BoardCells): BoardCells {
        val n = f.n
        val pl = prevLab; val po = prevOcc; val pc = prevConf; val pg = prevGeometry
        val conf = cells.confidence.copyOf()
        lastInconsistent = 0
        if (pl == null || po == null || pc == null || pg == null || !pg.isSameAs(g, 0.12f)) {
            lastChangedFraction = null
            stable.fill(0)
        } else {
            var changed = 0
            for (i in 0 until n) {
                val moved = CellFeatures.dist(pl[i], f.lab[i]) > CHANGE_DE
                if (moved) {
                    changed++; stable[i] = 0
                } else if (po[i] == cells.occupied[i]) {
                    stable[i] = minOf(stable[i] + 1, 4)
                    if (conf[i] >= 0.5 && pc[i] >= 0.5) conf[i] = 1.0 - (1.0 - conf[i]) * Math.pow(0.75, stable[i].toDouble())
                } else {
                    lastInconsistent++
                    conf[i] = minOf(conf[i], 0.35)
                    stable[i] = 0
                }
            }
            lastChangedFraction = changed / n.toDouble()
        }
        prevLab = Array(n) { f.lab[it].copyOf() }
        prevOcc = cells.occupied.copyOf()
        prevConf = conf.copyOf()
        prevGeometry = g
        val notes = if (lastInconsistent > 0) cells.notes + "$lastInconsistent cells changed label although their pixels did not" else cells.notes
        return BoardCells(cells.occupied, conf, cells.source, cells.orientationConfidence, cells.signals, cells.conflict, cells.uniform, notes, cells.modelRejected)
    }

    private companion object {
        const val CHANGE_DE = 3.0
    }
}

/** Can the move rules turn [prev] into [next] by placing some of the previously visible pieces (up to 3 moves)? */
object TransitionValidator {
    private val rules = RulesEngine()

    fun explains(prev: Board, pieces: List<Piece>, next: Board): Boolean {
        if (prev == next) return true
        return dfs(prev, pieces, next, BooleanArray(pieces.size))
    }

    private fun dfs(b: Board, pieces: List<Piece>, target: Board, used: BooleanArray): Boolean {
        for (k in pieces.indices) {
            if (used[k]) continue
            val p = pieces[k]
            for (y in 0..Board.SIZE - p.height) for (x in 0..Board.SIZE - p.width) {
                val r = rules.place(b, p, x, y) ?: continue
                if (r.board == target) return true
                used[k] = true
                val ok = used.count { it } < pieces.size && dfs(r.board, pieces, target, used)
                used[k] = false
                if (ok) return true
            }
        }
        return false
    }
}
