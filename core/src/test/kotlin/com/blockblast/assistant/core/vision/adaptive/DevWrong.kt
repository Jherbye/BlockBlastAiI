package com.blockblast.assistant.core.vision.adaptive

object DevWrong {
    @JvmStatic fun main(args: Array<String>) {
        val w = args.getOrNull(0)?.toInt() ?: 1080
        val h = args.getOrNull(1)?.toInt() ?: 2160
        for (t in ThemeLibrary.dev) {
            val sc = ThemeLibrary.scene(300)
            val f = ThemeRenderer.render(t, sc, w, h)
            val r = AdaptiveRecognizer().recognize(f)
            val truth = sc.pieces.filterNotNull()
            val ok = r.pieceList == truth && r.bits == sc.bits
            println("${t.name}: ${r.status} conf=${"%.2f".format(r.confidence)} match=$ok trayScale=${t.trayScale}")
            if (r.ok && !ok) {
                println("  truth: " + truth.map { p -> p.cells.joinToString("") { "(${it.x},${it.y})" } })
                println("  read:  " + r.pieces.map { p -> "${p.slot}:" + p.piece.cells.joinToString("") { "(${it.x},${it.y})" } + " conf=${"%.2f".format(p.confidence)} ${p.notes}" })
                println("  tray ratio=${r.trayReading?.pitchRatio} amb=${r.trayReading?.pitchAmbiguous} notes=${r.notes}")
            }
        }
    }
}
