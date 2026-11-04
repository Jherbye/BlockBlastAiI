package com.blockblast.assistant.core.vision.adaptive

object DevReasons {
    @JvmStatic fun main(args: Array<String>) {
        val warm = args.getOrNull(0) == "warm"
        val counts = HashMap<String, Int>()
        var total = 0; var notReady = 0
        for (seed in 1000L until 1024L) {
            val t = ThemeLibrary.random(seed)
            val scenes = (0 until 3).map { ThemeLibrary.scene(300L + it) }
            val rec = AdaptiveRecognizer()
            if (warm) { val f0 = ThemeRenderer.render(t, scenes[0]); rec.recognize(f0); rec.correct(scenes[0].bits, scenes[0].pieces) }
            for (sc in scenes.drop(if (warm) 1 else 0)) {
                val r = rec.recognize(ThemeRenderer.render(t, sc))
                total++
                if (r.status != RecognitionStatus.READY) {
                    notReady++
                    val c = r.cells
                    val reasons = ArrayList<String>()
                    reasons.add(r.status.name)
                    if (c != null) {
                        if (c.minConfidence < 0.55) reasons.add("cellConf<.55")
                        if (c.source == OccupancySource.UNSUPERVISED && c.orientationConfidence < 0.55) reasons.add("orient<.55")
                        if (c.conflict) reasons.add("signalConflict")
                        if (c.uniform) reasons.add("uniform")
                    }
                    r.pieces.filter { it.confidence < 0.55 }.forEach { reasons.addAll(it.notes.map { n -> "piece:" + n.take(40) }.ifEmpty { listOf("piece:lowconf") }) }
                    r.trayReading?.notes?.forEach { reasons.add("tray:" + it.take(45)) }
                    if (r.notes.any { it.contains("complete row") }) reasons.add("fullLine")
                    reasons.forEach { counts.merge(it, 1, Int::plus) }
                    if (args.getOrNull(1) == "v") println("${t.name} seed=${sc.seed} ${reasons} conf=${"%.2f".format(r.confidence)}")
                }
            }
        }
        println("notReady $notReady / $total")
        counts.entries.sortedByDescending { it.value }.forEach { println("  ${it.value}  ${it.key}") }
    }
}
