package com.blockblast.assistant.core.vision.adaptive

object DevWarm {
    @JvmStatic fun main(args: Array<String>) {
        val t = ThemeLibrary.random(args[0].toLong())
        val scs = (0 until 4).map { ThemeLibrary.scene(400L + it) }
        val rec = AdaptiveRecognizer()
        val r0 = rec.recognize(ThemeRenderer.render(t, scs[0]))
        println("first: ${r0.status} ratio=${r0.trayReading?.pitchRatio} bounds=${r0.trayReading?.pieces?.map { it.bounds.right - it.bounds.left to it.bounds.bottom - it.bounds.top }} true=${t.trayScale}")
        rec.correct(scs[0].bits, scs[0].pieces)
        println("model ratio=${rec.model.trayRatio}")
        for (i in 1..3) {
            val r = rec.recognize(ThemeRenderer.render(t, scs[i]))
            println("scene $i ${r.status} ratio=${r.trayReading?.pitchRatio} cellPx=${r.trayReading?.cellPx} bounds=${r.trayReading?.pieces?.map { it.bounds.right - it.bounds.left to it.bounds.bottom - it.bounds.top }}")
        }
    }
}
