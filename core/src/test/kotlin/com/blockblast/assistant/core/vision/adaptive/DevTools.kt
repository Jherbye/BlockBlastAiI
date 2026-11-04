package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.vision.PixelFrame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

object DevTools {
    fun save(f: PixelFrame, path: String, maxW: Int = 540) {
        val k = maxOf(1, f.width / maxW)
        val img = BufferedImage(f.width / k, f.height / k, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, f.pixel(x * k, y * k))
        ImageIO.write(img, "png", File(path))
    }

    @JvmStatic fun main(args: Array<String>) {
        val out = args.getOrNull(0) ?: "."
        val themes = ThemeLibrary.dev + (1L..6L).map { ThemeLibrary.random(it) }
        if (args.getOrNull(1) == "png") {
            for (t in themes) save(ThemeRenderer.render(t, ThemeLibrary.scene(42)), "$out/${t.name}.png", 360)
            return
        }
        var ok = 0
        for (t in themes) {
            val sc = ThemeLibrary.scene(7)
            val f = ThemeRenderer.render(t, sc)
            val ev = GridDetector().detect(LabImage.of(f))
            val cell = 1080 * t.boardWidthFrac / 8
            val left = (1080 - 1080 * t.boardWidthFrac) / 2
            val top = 2400 * t.boardTopFrac
            if (ev == null) { println("%-22s NONE".format(t.name)); continue }
            val g = ev.geometry
            val err = maxOf(abs(g.left - left), abs(g.top - top)) / cell
            val good = err < 0.08 && abs(g.cellSize - cell) / cell < 0.01
            if (good) ok++
            println("%-22s %s conf=%.2f err=%.3f cell=%.1f/%.1f sup=%d,%d ctr=%.2f ext=%.2f %s".format(t.name, if (good) "OK  " else "FAIL", ev.confidence, err, g.cellSize, cell, ev.supportX, ev.supportY, ev.contrast, ev.extentScore, ev.notes))
        }
        println("ok $ok / ${themes.size}")
    }
}
