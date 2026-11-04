package com.blockblast.assistant.core.vision.adaptive

/** Lists READY-but-wrong frames of a seed range (cold and warm) with the reason fields. */
object DevSealed {
    private fun s(c: List<com.blockblast.assistant.core.model.Cell>) = c.joinToString("") { "(${it.x},${it.y})" }
    @JvmStatic fun main(args: Array<String>) {
        val from = args[0].toLong(); val to = args[1].toLong()
        for (seed in from until to) {
            val t = ThemeLibrary.random(seed)
            fun report(tag: String, sc: Scene, r: AdaptiveResult) {
                val truth = sc.pieces.mapIndexedNotNull { i, p -> p?.let { i to it } }
                val got = r.pieces.associate { it.slot to it.piece }
                val boardOk = r.bits == sc.bits
                val piecesOk = truth.all { (i, p) -> got[i] == p } && got.size == truth.size
                if (r.ok && !(boardOk && piecesOk)) {
                    println("seed=$seed $tag READY-wrong conf=${"%.2f".format(r.confidence)} boardOk=$boardOk piecesOk=$piecesOk theme=${t.name} trayScale=${t.trayScale}")
                    if (!piecesOk) {
                        println("   truth: " + truth.map { (i, p) -> "$i:" + s(p.cells) })
                        println("   read:  " + r.pieces.map { "${it.slot}:" + s(it.piece.cells) + " c=${"%.2f".format(it.confidence)} ${it.notes}" })
                        println("   tray: ratio=${r.trayReading?.pitchRatio} amb=${r.trayReading?.pitchAmbiguous} notes=${r.notes}")
                    }
                    if (!boardOk) println("   board diff cells=${java.lang.Long.bitCount(r.bits!! xor sc.bits)} signals=${r.cells?.signals} orient=${r.cells?.orientationConfidence}")
                }
            }
            for (k in 0 until 2) { val sc = ThemeLibrary.scene(300L + k); report("cold$k", sc, AdaptiveRecognizer().recognize(ThemeRenderer.render(t, sc))) }
            val scs = (0 until 4).map { ThemeLibrary.scene(400L + it) }
            val rec = AdaptiveRecognizer()
            rec.recognize(ThemeRenderer.render(t, scs[0])); rec.correct(scs[0].bits, scs[0].pieces)
            scs.drop(1).forEachIndexed { i, sc -> report("warm$i", sc, rec.recognize(ThemeRenderer.render(t, sc))) }
        }
    }
}
