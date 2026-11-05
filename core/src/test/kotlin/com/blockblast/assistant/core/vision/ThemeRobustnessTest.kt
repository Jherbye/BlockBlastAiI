package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Theme robustness measurement on procedurally generated (synthetic) themes the recognizer was not tuned on.
 * Reports, per kind: geometry accuracy, board-cell accuracy, piece-shape accuracy, acceptance rate (coverage), mean
 * confidence, and - most importantly - how often an ACCEPTED reading is wrong ("confidently wrong").
 * The report is written to core/build/reports/theme-robustness.txt.
 *
 * The assertions are deliberately about SAFETY (accepted readings must be right, hard themes must be flagged rather
 * than guessed) and a modest coverage floor; they are not a claim of accuracy on real Block Blast themes.
 */
class ThemeRobustnessTest {
    private class Tally {
        var n = 0; var geomOk = 0; var cells = 0; var cellErr = 0; var pieceOk = 0; var accepted = 0
        var confSum = 0.0; var wrongAccepted = 0; var acceptedCells = 0; var acceptedCellErr = 0
    }

    private fun shapeOf(p: Piece) = p.cells.map { it.x to it.y }

    @Test fun measureAcrossThemes() {
        val plan = listOf("friendly" to 14, "normal" to 24, "lowcontrast" to 8, "samecolor" to 6)
        val tallies = LinkedHashMap<String, Tally>()
        for ((kind, count) in plan) {
            val t = tallies.getOrPut(kind) { Tally() }
            for (i in 0 until count) {
                val scene = ThemeScenes.make(1000L * kind.hashCode() + i, kind)
                val result = RecognitionPipeline().recognize(scene.frame)
                t.n++
                val g = result.geometry
                val geomOk = g != null && abs(g.left - scene.geometry.left) < 0.15 * scene.geometry.cellSize &&
                    abs(g.top - scene.geometry.top) < 0.15 * scene.geometry.cellSize &&
                    abs(g.cellSize - scene.geometry.cellSize) < 0.03 * scene.geometry.cellSize
                val bits = result.board?.bits
                val wrong = if (bits == null) 64 else java.lang.Long.bitCount(bits xor scene.bits)
                val got = result.pieces?.pieces?.associate { it.slot to shapeOf(it.piece) } ?: emptyMap()
                val piecesOk = (0 until 3).all { sl ->
                    val truth = scene.pieces[sl]
                    if (truth == null) sl !in got else got[sl] == shapeOf(truth)
                }
                if (geomOk) t.geomOk++
                t.cells += 64; t.cellErr += wrong
                if (piecesOk) t.pieceOk++
                t.confSum += result.confidence
                if (result.ok) {
                    t.accepted++
                    t.acceptedCells += 64; t.acceptedCellErr += wrong
                    if (wrong > 0 || !piecesOk || !geomOk) t.wrongAccepted++
                }
            }
        }
        val report = StringBuilder("Theme robustness (SYNTHETIC themes, not real gameplay)\n")
        var accepted = 0; var wrongAccepted = 0; var all = 0; var acCells = 0; var acErr = 0
        for ((kind, t) in tallies) {
            report.append(
                "%-12s n=%2d geometry=%.2f cellAcc=%.4f pieceAcc=%.2f accepted=%.2f meanConf=%.2f acceptedWrong=%d/%d\n".format(
                    kind, t.n, t.geomOk.toDouble() / t.n, 1.0 - t.cellErr.toDouble() / t.cells, t.pieceOk.toDouble() / t.n,
                    t.accepted.toDouble() / t.n, t.confSum / t.n, t.wrongAccepted, t.accepted,
                ),
            )
            accepted += t.accepted; wrongAccepted += t.wrongAccepted; all += t.n; acCells += t.acceptedCells; acErr += t.acceptedCellErr
        }
        report.append("overall: scenes=$all accepted=$accepted acceptedButWrong=$wrongAccepted\n")
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/theme-robustness.txt").writeText(report.toString())

        assertTrue("too few scenes accepted: $accepted of $all", accepted >= all * 0.15)
        assertTrue("accepted readings are too often wrong: $wrongAccepted of $accepted", wrongAccepted <= accepted * 0.25)
        val acceptedCellAcc = if (acCells == 0) 1.0 else 1.0 - acErr.toDouble() / acCells
        assertTrue("accepted cell accuracy $acceptedCellAcc", acceptedCellAcc >= 0.98)
    }
}
