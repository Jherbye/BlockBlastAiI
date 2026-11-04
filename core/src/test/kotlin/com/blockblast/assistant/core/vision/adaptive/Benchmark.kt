package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.PixelFrame
import com.blockblast.assistant.core.vision.RecognitionPipeline
import kotlin.math.abs

/** One measured recognition attempt. */
class Sample(
    val group: String, val theme: String, val status: RecognitionStatus, val geometryOk: Boolean,
    val cellsRight: Int, val boardExact: Boolean, val piecesRight: Int, val piecesTotal: Int, val piecesExact: Boolean,
    val confidence: Double, val legacyCellsRight: Int, val legacyBoardExact: Boolean, val legacyPiecesExact: Boolean, val legacyOk: Boolean,
) {
    val ready get() = status == RecognitionStatus.READY
    val wrong get() = !(boardExact && piecesExact)
}

object Benchmark {
    fun truthPieces(sc: Scene): List<Pair<Int, Piece>> = sc.pieces.mapIndexedNotNull { i, p -> p?.let { i to it } }

    fun measure(group: String, t: ThemeSpec, sc: Scene, frame: PixelFrame, res: AdaptiveResult, withLegacy: Boolean = true): Sample {
        val cellW = frame.width * t.boardWidthFrac / 8.0
        val left = (frame.width - frame.width * t.boardWidthFrac) / 2.0
        val top = frame.height * t.boardTopFrac
        val g = res.geometry
        val geoOk = g != null && maxOf(abs(g.left.toDouble() - left), abs(g.top.toDouble() - top)) / cellW < 0.15 && abs(g.cellSize.toDouble() - cellW) / cellW < 0.03
        val bits = res.bits
        val right = if (geoOk && bits != null) (0 until 64).count { ((bits xor sc.bits) ushr it) and 1L == 0L } else 0
        val truth = truthPieces(sc)
        val got = res.pieces.associate { it.slot to it.piece }
        val pr = truth.count { (s, p) -> got[s] == p }
        val pExact = pr == truth.size && got.size == truth.size
        var lcr = 0; var lbe = false; var lpe = false; var lok = false
        if (withLegacy) {
            val lr = RecognitionPipeline().recognize(frame)
            val lb = lr.board?.bits
            if (lb != null) { lcr = (0 until 64).count { ((lb xor sc.bits) ushr it) and 1L == 0L }; lbe = lb == sc.bits }
            val lgot = lr.pieces?.pieces?.associate { it.slot to it.piece } ?: emptyMap()
            lpe = truth.all { (s, p) -> lgot[s] == p } && lgot.size == truth.size
            lok = lr.ok
        }
        return Sample(group, t.name, res.status, geoOk, right, geoOk && bits == sc.bits, pr, truth.size, pExact, res.confidence, lcr, lbe, lpe, lok)
    }

    /** Cold start: a brand-new recognizer sees one frame of an unseen theme. */
    fun cold(group: String, t: ThemeSpec, sc: Scene, p: Perturb = Perturb(), w: Int = 1080, h: Int = 2400, legacy: Boolean = true): Sample {
        val f = ThemeRenderer.render(t, sc, w, h, p)
        return measure(group, t, sc, f, AdaptiveRecognizer().recognize(f), legacy)
    }

    /** Warm: the user corrects the first frame once (teaching the theme model); we then measure further frames. */
    fun warm(group: String, t: ThemeSpec, scenes: List<Scene>, p: Perturb = Perturb(), legacy: Boolean = false): List<Sample> {
        val rec = AdaptiveRecognizer()
        val first = scenes[0]
        val f0 = ThemeRenderer.render(t, first, 1080, 2400, p)
        rec.recognize(f0)
        rec.correct(first.bits, first.pieces)
        return scenes.drop(1).map { sc ->
            val f = ThemeRenderer.render(t, sc, 1080, 2400, p)
            measure(group, t, sc, f, rec.recognize(f), legacy)
        }
    }

    class Agg(val name: String, val s: List<Sample>) {
        fun pct(x: Double) = "%.1f%%".format(100 * x)
        val n get() = s.size
        val cellAcc get() = s.sumOf { it.cellsRight } / (64.0 * n)
        val boardExact get() = s.count { it.boardExact } / n.toDouble()
        val pieceAcc get() = s.sumOf { it.piecesRight }.toDouble() / s.sumOf { it.piecesTotal }.coerceAtLeast(1)
        val ready get() = s.count { it.ready } / n.toDouble()
        val unsafe get() = s.count { it.ready && it.wrong }
        val confRight get() = s.filter { !it.wrong }.map { it.confidence }.averageOrNull()
        val confWrong get() = s.filter { it.wrong }.map { it.confidence }.averageOrNull()
        val legacyCell get() = s.sumOf { it.legacyCellsRight } / (64.0 * n)
        val legacyExact get() = s.count { it.legacyBoardExact && it.legacyPiecesExact } / n.toDouble()
        val legacyUnsafe get() = s.count { it.legacyOk && !(it.legacyBoardExact && it.legacyPiecesExact) }
        private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
        fun row(legacy: Boolean = true): String {
            val cw = confWrong?.let { "%.2f".format(it) } ?: "-"
            val cr = confRight?.let { "%.2f".format(it) } ?: "-"
            val base = "| $name | $n | ${pct(cellAcc)} | ${pct(boardExact)} | ${pct(pieceAcc)} | ${pct(ready)} | $unsafe | $cr / $cw |"
            return if (legacy) "$base ${pct(legacyCell)} | ${pct(legacyExact)} | $legacyUnsafe |" else "$base"
        }
    }

    fun table(rows: List<Agg>, legacy: Boolean = true): String {
        val head = "| Condition | Frames | Cell acc. | Board exact | Piece acc. | READY | READY-but-wrong | Conf. right/wrong |" +
            (if (legacy) " Legacy cell acc. | Legacy exact | Legacy accepted-but-wrong |" else "")
        val sep = "|---|---|---|---|---|---|---|---|" + (if (legacy) "---|---|---|" else "")
        return (listOf(head, sep) + rows.map { it.row(legacy) }).joinToString("\n")
    }
}
