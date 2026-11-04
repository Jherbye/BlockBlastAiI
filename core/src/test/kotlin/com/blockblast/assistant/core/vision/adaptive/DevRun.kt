package com.blockblast.assistant.core.vision.adaptive

object DevRun {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "cold"
        val themes = ThemeLibrary.dev + (1L..12L).map { ThemeLibrary.random(it) }
        val verbose = args.getOrNull(1) == "v"
        val all = ArrayList<Sample>()
        for (t in themes) {
            val ss = if (mode == "warm") Benchmark.warm("w", t, (0L..3L).map { ThemeLibrary.scene(100 + it) })
            else (0L..2L).map { Benchmark.cold("c", t, ThemeLibrary.scene(200 + it)) }
            all.addAll(ss)
            val a = Benchmark.Agg(t.name, ss)
            println("%-20s cell=%s exact=%s piece=%s ready=%s unsafe=%d  conf=%s/%s  legacy=%s".format(t.name, a.pct(a.cellAcc), a.pct(a.boardExact), a.pct(a.pieceAcc), a.pct(a.ready), a.unsafe, a.confRight?.let { "%.2f".format(it) }, a.confWrong?.let { "%.2f".format(it) }, a.pct(a.legacyCell)))
            if (verbose) ss.forEach { println("    ${it.status} geo=${it.geometryOk} cells=${it.cellsRight} piecesExact=${it.piecesExact} conf=${"%.2f".format(it.confidence)}") }
        }
        println(Benchmark.table(listOf(Benchmark.Agg("ALL", all)), mode != "warm"))
    }
}
