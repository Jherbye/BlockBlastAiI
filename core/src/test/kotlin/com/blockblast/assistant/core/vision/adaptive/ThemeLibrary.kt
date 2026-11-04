package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import java.util.Random

/** Hand-made development themes plus a random theme generator for held-out evaluation. */
object ThemeLibrary {
    private fun c(r: Int, g: Int, b: Int) = Rgb.of(r, g, b)
    private val CANDY = listOf(c(235, 70, 80), c(245, 160, 40), c(245, 215, 60), c(80, 200, 100), c(70, 140, 245), c(170, 100, 230), c(60, 200, 210))
    private val PASTEL = listOf(c(240, 150, 160), c(250, 200, 140), c(250, 235, 150), c(160, 220, 170), c(150, 190, 245), c(200, 170, 235))

    private fun base(name: String) = ThemeSpec(
        name, BgKind.VGRAD, c(20, 26, 60), c(34, 44, 96), c(30, 40, 90), 1f, 0.06f, 0.06f, c(22, 30, 70), 0.18f,
        EmptyKind.FLAT, c(48, 62, 120), c(48, 62, 120), BlockKind.BEVEL, CANDY, 0f, null, 0f, null, 0.5f, 0.06f,
    )

    val dev: List<ThemeSpec> by lazy {
        val b = base("x")
        listOf(
            b.copy(name = "classic-dark"),
            b.copy(name = "light-pastel", bgKind = BgKind.SOLID, bgA = c(245, 240, 250), bgB = c(230, 225, 245), panelColor = c(225, 222, 240),
                gapColor = c(205, 200, 228), emptyA = c(238, 235, 250), emptyB = c(238, 235, 250), blockKind = BlockKind.FLAT, palette = PASTEL,
                outlineFrac = 0.05f, outlineColor = c(120, 110, 150), trayPanel = c(232, 228, 245)),
            b.copy(name = "chocolate-gloss", bgKind = BgKind.NOISE, bgA = c(70, 40, 30), bgB = c(110, 70, 50), panelColor = c(60, 36, 28), gapColor = c(40, 24, 18),
                emptyKind = EmptyKind.BEVEL, emptyA = c(88, 56, 44), emptyB = c(88, 56, 44), blockKind = BlockKind.GLOSS, shadow = 0.6f),
            b.copy(name = "low-contrast", gapFrac = 0.05f, emptyA = c(44, 58, 112), emptyB = c(44, 58, 112), blockKind = BlockKind.BEVEL,
                palette = listOf(c(60, 78, 140), c(66, 74, 136), c(58, 84, 128)), outlineFrac = 0.04f, outlineColor = c(110, 130, 200)),
            b.copy(name = "no-gap-outline", gapFrac = 0f, blockKind = BlockKind.FLAT, outlineFrac = 0.06f, outlineColor = c(10, 14, 36), cellRadius = 0.05f),
            b.copy(name = "gradient-board", emptyKind = EmptyKind.GRAD, emptyA = c(60, 78, 140), emptyB = c(36, 48, 100), blockKind = BlockKind.GRAD),
            b.copy(name = "transparent-panel", bgKind = BgKind.STRIPES, bgA = c(30, 90, 80), bgB = c(80, 160, 140), panelAlpha = 0.35f, gapColor = c(10, 30, 30), blockKind = BlockKind.GLOSS),
            b.copy(name = "checkerboard", emptyKind = EmptyKind.CHECKER, emptyA = c(52, 66, 126), emptyB = c(40, 52, 106), blockKind = BlockKind.FLAT, gapFrac = 0.02f),
            b.copy(name = "neon-black", bgKind = BgKind.SOLID, bgA = c(6, 6, 10), bgB = c(6, 6, 10), panelColor = c(14, 14, 22), gapColor = c(4, 4, 8),
                emptyA = c(18, 18, 30), emptyB = c(18, 18, 30), blockKind = BlockKind.GLOSS, shadow = 0.8f, outlineFrac = 0.03f, outlineColor = c(255, 255, 255)),
            b.copy(name = "textured", emptyKind = EmptyKind.NOISE, blockKind = BlockKind.NOISE, gapFrac = 0.04f),
            b.copy(name = "stripes-on-dots", bgKind = BgKind.DOTS, bgA = c(90, 50, 110), bgB = c(130, 80, 150), panelColor = c(60, 30, 80), gapColor = c(35, 15, 50),
                emptyA = c(78, 44, 100), emptyB = c(78, 44, 100), blockKind = BlockKind.STRIPES),
            b.copy(name = "single-color-blocks", palette = listOf(c(240, 120, 40)), blockKind = BlockKind.BEVEL, trayPanel = c(24, 32, 76)),
        )
    }

    /** Random theme; [hard] > 0 increases the chance of low colour contrast between empty and occupied cells. */
    fun random(seed: Long): ThemeSpec {
        var k = 0L
        while (true) {
            val t = randomRaw(seed + 100_000L * k)
            if (piecesVisible(t)) return t.copy(name = "rand-$seed")
            k++
        }
    }

    private fun rgbDist(a: Rgb, b: Rgb) = Math.sqrt(((a.r - b.r) * (a.r - b.r) + (a.g - b.g) * (a.g - b.g) + (a.b - b.b) * (a.b - b.b)).toDouble())

    /** A human must be able to see the tray pieces: fills or outlines must differ clearly from what is behind them. */
    private fun piecesVisible(t: ThemeSpec): Boolean {
        val behind = if (t.trayPanel != null) listOf(t.trayPanel) else listOf(t.bgA, t.bgB, t.bgA.mix(t.bgB, 0.5f))
        val outline = t.outlineColor
        return t.palette.all { c -> behind.all { rgbDist(c, it) >= 55.0 } } ||
            (outline != null && t.outlineFrac >= 0.03f && behind.all { rgbDist(outline, it) >= 110.0 } && t.palette.all { c -> behind.all { rgbDist(c, it) >= 30.0 } })
    }

    private fun randomRaw(seed: Long): ThemeSpec {
        val r = Random(seed)
        fun f(a: Float, b: Float) = a + (b - a) * r.nextFloat()
        val light = r.nextFloat() < 0.35f
        fun surface(l: Float) = Rgb.hsv(r.nextFloat(), f(0.05f, 0.55f), l)
        val bgL = if (light) f(0.75f, 0.97f) else f(0.05f, 0.35f)
        val bgA = surface(bgL)
        val bgB = bgA.lift(if (light) -f(10f, 40f) else f(10f, 50f))
        val panelL = (bgL + f(-0.12f, 0.12f)).coerceIn(0.04f, 0.98f)
        val panel = surface(panelL)
        val emptyL = (panelL + f(-0.08f, 0.1f)).coerceIn(0.05f, 0.98f)
        val emptyA = Rgb.hsv(r.nextFloat(), f(0.05f, 0.5f), emptyL)
        val emptyKind = EmptyKind.values()[r.nextInt(EmptyKind.values().size)]
        val gapFrac = if (r.nextFloat() < 0.2f) 0f else f(0.02f, 0.1f)
        val gapColor = if (r.nextFloat() < 0.15f) emptyA else panel.scale(f(0.5f, 1.2f))
        val lowContrast = r.nextFloat() < 0.3f
        val palette = (0 until (1 + r.nextInt(7))).map {
            if (lowContrast) emptyA.mix(Rgb.hsv(r.nextFloat(), f(0.2f, 0.7f), f(0.4f, 0.95f)), f(0.15f, 0.4f))
            else Rgb.hsv(r.nextFloat(), f(0.4f, 0.95f), f(0.6f, 1f))
        }
        val blockKind = BlockKind.values()[r.nextInt(BlockKind.values().size)]
        val hasOutline = lowContrast || r.nextFloat() < 0.4f
        val outlineColor = if (hasOutline) (if (r.nextBoolean()) Rgb.of(255, 255, 255).mix(Rgb.of(0, 0, 0), r.nextFloat() * 0.2f) else Rgb.of(10, 10, 20)) else null
        return ThemeSpec(
            "rand-$seed", BgKind.values()[r.nextInt(BgKind.values().size)], bgA, bgB, panel, if (r.nextFloat() < 0.2f) f(0.3f, 0.7f) else 1f, f(0.02f, 0.1f),
            gapFrac, gapColor, f(0f, 0.3f), emptyKind, emptyA, emptyA.lift(if (light) -12f else 12f),
            blockKind, palette, if (hasOutline) f(0.03f, 0.07f) else 0f, outlineColor, if (r.nextBoolean()) f(0.2f, 0.8f) else 0f,
            if (r.nextFloat() < 0.5f) surface(panelL) else null, f(0.4f, 0.62f), f(0.02f, 0.08f),
            boardWidthFrac = f(0.8f, 0.94f), boardTopFrac = f(0.18f, 0.32f),
        )
    }

    private val shapes: List<Piece> by lazy { ShapeCatalog.all.toList().sortedBy { it.cells.joinToString { c -> "${c.y}${c.x}" } } }

    /** Random plausible scene: no full lines on the board, 1-3 tray pieces. */
    fun scene(seed: Long, density: Double? = null): Scene {
        val r = Random(seed)
        val d = density ?: (0.08 + r.nextDouble() * 0.5)
        var bits = 0L
        for (i in 0 until 64) if (r.nextDouble() < d) bits = bits or (1L shl i)
        for (k in 0 until 8) {
            if ((bits and Board.ROW_MASKS[k]) == Board.ROW_MASKS[k]) bits = bits and (1L shl (k * 8 + r.nextInt(8))).inv()
            if ((bits and Board.COL_MASKS[k]) == Board.COL_MASKS[k]) bits = bits and (1L shl (r.nextInt(8) * 8 + k)).inv()
        }
        val count = if (r.nextDouble() < 0.8) 3 else 1 + r.nextInt(3)
        val pieces = MutableList<Piece?>(3) { null }
        val slots = (0..2).shuffled(r).take(count)
        for (s in slots) pieces[s] = shapes[r.nextInt(shapes.size)]
        return Scene(bits, pieces, seed)
    }
}
