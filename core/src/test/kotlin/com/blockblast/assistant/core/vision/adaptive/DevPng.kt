package com.blockblast.assistant.core.vision.adaptive

object DevPng {
    @JvmStatic fun main(args: Array<String>) {
        val t = ThemeLibrary.random(args[0].toLong())
        val sc = ThemeLibrary.scene(args[1].toLong())
        val f = ThemeRenderer.render(t, sc)
        val r = AdaptiveRecognizer().recognize(f)
        val g = r.geometry!!
        println("geo=$g tray=${r.trayReading?.pieces?.map { it.bounds }} frame=${f.width}x${f.height}")
        DevTools.save(f, args[2], 1080)
    }
}
