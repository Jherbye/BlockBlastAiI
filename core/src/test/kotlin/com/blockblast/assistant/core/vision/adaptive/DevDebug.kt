package com.blockblast.assistant.core.vision.adaptive

object DevDebug {
    @JvmStatic fun main(args: Array<String>) {
        val name = args[0]
        val seed = args.getOrNull(1)?.toLong() ?: 200L
        val t = ThemeLibrary.dev.firstOrNull { it.name == name } ?: ThemeLibrary.random(name.removePrefix("rand-").toLong())
        val sc = ThemeLibrary.scene(seed)
        val f = ThemeRenderer.render(t, sc)
        if (args.getOrNull(2) == "png") DevTools.save(f, "${args[3]}/dbg-$name.png", 540)
        val rec = AdaptiveRecognizer()
        val r = rec.recognize(f)
        println("status=${r.status} conf=${"%.2f".format(r.confidence)} geo=${r.geometry} geoConf=${"%.2f".format(r.geometryConfidence)}")
        println("notes=${r.notes}")
        val c = r.cells
        if (c != null) {
            println("signals=${c.signals} orient=${"%.2f".format(c.orientationConfidence)} conflict=${c.conflict} uniform=${c.uniform} source=${c.source}")
            for (y in 0 until 8) {
                val truth = (0 until 8).joinToString("") { if ((sc.bits ushr (y * 8 + it)) and 1L == 1L) "#" else "." }
                val read = (0 until 8).joinToString("") { if (c.occupied[y * 8 + it]) "#" else "." }
                val cf = (0 until 8).joinToString(" ") { "%.2f".format(c.confidence[y * 8 + it]) }
                println("$truth  $read  $cf")
            }
        }
        println("truth pieces: " + sc.pieces.mapIndexed { i, p -> "$i:${p?.cells?.joinToString("") { "(${it.x},${it.y})" }}" })
        println("read pieces:  " + r.pieces.map { "${it.slot}:${it.piece.cells.joinToString("") { c -> "(${c.x},${c.y})" }} conf=${"%.2f".format(it.confidence)} ${it.notes}" })
        println("tray: ratio=${r.trayReading?.pitchRatio} amb=${r.trayReading?.pitchAmbiguous} true=${t.trayScale}")
    }
}
