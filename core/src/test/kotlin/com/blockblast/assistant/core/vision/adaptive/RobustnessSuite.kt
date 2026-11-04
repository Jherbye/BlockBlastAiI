package com.blockblast.assistant.core.vision.adaptive

import java.io.File

/**
 * Theme-robustness benchmark over SYNTHETIC themes (see docs/ROBUSTNESS_REPORT.md for what that does and does not prove).
 *
 * Seed ranges: development themes + random seeds 1..12 were used while building the recognizer; "held-out" seeds
 * 1000.. were first evaluated after the algorithms were frozen for that round; "sealed" seeds 5000.. are evaluated once
 * per release candidate only.
 */
object RobustnessSuite {
    private val cond = ThemeLibrary.dev
    private fun scenes(base: Long, n: Int) = (0 until n).map { ThemeLibrary.scene(base + it) }

    private fun coldGroup(name: String, themes: List<ThemeSpec>, scenesPer: Int, p: Perturb = Perturb(), w: Int = 1080, h: Int = 2400, legacy: Boolean = true): Benchmark.Agg =
        Benchmark.Agg(name, themes.flatMap { t -> scenes(300, scenesPer).map { Benchmark.cold(name, t, it, p, w, h, legacy) } })

    private fun warmGroup(name: String, themes: List<ThemeSpec>, p: Perturb = Perturb()): Benchmark.Agg =
        Benchmark.Agg(name, themes.flatMap { t -> Benchmark.warm(name, t, scenes(400, 4), p) })

    fun run(quick: Boolean, sealed: Boolean = false, sealStart: Long = 5000L): String {
        val sb = StringBuilder()
        val per = if (quick) 1 else 2
        val dev = cond
        val heldOut = (1000L until 1000L + (if (quick) 8 else 40)).map { ThemeLibrary.random(it) }
        val light = dev.filter { it.name == "light-pastel" }
        val dark = dev.filter { it.name != "light-pastel" }
        val similar = dev.filter { it.name in setOf("low-contrast", "single-color-blocks", "no-gap-outline") }
        val texture = dev.filter { it.name in setOf("textured", "gradient-board", "chocolate-gloss", "stripes-on-dots", "checkerboard") }
        val bgs = dev.filter { it.name in setOf("transparent-panel", "stripes-on-dots", "neon-black", "chocolate-gloss") }
        val subset = if (quick) dev.take(4) else dev

        sb.appendLine("### A. Cold start (new recognizer, one frame, theme never seen)\n")
        val a = listOf(
            coldGroup("Development themes (12)", dev, per),
            coldGroup("Light theme", light, 3),
            coldGroup("Dark themes", dark, 1),
            coldGroup("Occupied/empty similar colours", similar, 3),
            coldGroup("Gradient / textured / patterned boards", texture, 2),
            coldGroup("Transparent, striped, noisy backgrounds", bgs, 2),
            coldGroup("HELD-OUT random themes (${heldOut.size})", heldOut, 2),
        )
        sb.appendLine(Benchmark.table(a)).appendLine()

        sb.appendLine("### B. Capture / display perturbations (cold start, development themes)\n")
        val perts = listOf(
            "Brightness x0.6" to Perturb(gain = 0.6f), "Brightness x0.8" to Perturb(gain = 0.8f), "Brightness x1.25 (clipped)" to Perturb(gain = 1.25f),
            "Gamma 0.7" to Perturb(gamma = 0.7f), "Gamma 1.5" to Perturb(gamma = 1.5f),
            "Sensor noise sigma 6" to Perturb(noise = 6f), "Blur (3x3 box)" to Perturb(blur = 1),
            "Display scaling 0.67x (bilinear)" to Perturb(resample = 0.67f), "Display scaling 0.85x (bilinear)" to Perturb(resample = 0.85f),
        )
        val b = ArrayList<Benchmark.Agg>()
        for ((n, p) in perts) b.add(coldGroup(n, subset, 1, p, legacy = !quick))
        b.add(coldGroup("Native 720x1560", subset, 1, w = 720, h = 1560, legacy = !quick))
        b.add(coldGroup("Native 1440x3120", subset, 1, w = 1440, h = 3120, legacy = !quick))
        b.add(coldGroup("Native 1080x2160 (18:9)", subset, 1, w = 1080, h = 2160, legacy = !quick))
        sb.appendLine(Benchmark.table(b, !quick)).appendLine()

        sb.appendLine("### C. Warm (user corrected the first frame once; 3 further frames of the same theme)\n")
        val c = listOf(
            warmGroup("Development themes", dev),
            warmGroup("HELD-OUT random themes (${heldOut.size})", heldOut),
            warmGroup("Dev themes + brightness x0.7 + noise", dev, Perturb(gain = 0.7f, noise = 4f)),
        )
        sb.appendLine(Benchmark.table(c, false)).appendLine()

        if (sealed) {
            val seal = (sealStart until sealStart + 40L).map { ThemeLibrary.random(it) }
            sb.appendLine("### D. SEALED random themes (single evaluation)\n")
            sb.appendLine(Benchmark.table(listOf(coldGroup("Sealed cold (${seal.size})", seal, 2), warmGroup("Sealed warm (${seal.size})", seal)))).appendLine()
        }
        return sb.toString()
    }

    @JvmStatic fun main(args: Array<String>) {
        val quick = args.contains("quick")
        val sealStart = args.firstOrNull { it.startsWith("seal=") }?.removePrefix("seal=")?.toLong() ?: 5000L
        val out = run(quick, args.contains("sealed"), sealStart)
        println(out)
        args.firstOrNull { it.startsWith("out=") }?.let { File(it.removePrefix("out=")).writeText(out) }
    }
}
