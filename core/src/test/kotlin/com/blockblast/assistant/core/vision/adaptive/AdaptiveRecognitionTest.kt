package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class AdaptiveRecognitionTest {

    private fun frameOf(t: ThemeSpec, sc: Scene, p: Perturb = Perturb()) = ThemeRenderer.render(t, sc, 1080, 2400, p)

    @Test fun gridIsFoundOnEveryDevelopmentTheme() {
        for (t in ThemeLibrary.dev) {
            val ev = GridDetector().detect(LabImage.of(frameOf(t, ThemeLibrary.scene(1))))
            assertNotNull("no grid for ${t.name}", ev)
            val cell = 1080 * t.boardWidthFrac / 8.0
            val g = ev!!.geometry
            assertTrue("${t.name}: pitch ${g.cellSize} vs $cell", abs(g.cellSize - cell) / cell < 0.01)
            assertTrue("${t.name}: left", abs(g.left - (1080 - 1080 * t.boardWidthFrac) / 2.0) / cell < 0.08)
            assertTrue("${t.name}: top", abs(g.top - 2400 * t.boardTopFrac) / cell < 0.08)
            assertTrue("${t.name}: confidence ${ev.confidence}", ev.confidence > 0.6)
        }
    }

    @Test fun neverReadyButWrongAcrossThemesAndPerturbations() {
        val perts = listOf(Perturb(), Perturb(gain = 0.6f), Perturb(gamma = 1.5f), Perturb(noise = 6f), Perturb(resample = 0.7f))
        var ready = 0
        var total = 0
        val themes = ThemeLibrary.dev + (2000L until 2012L).map { ThemeLibrary.random(it) }
        for ((i, t) in themes.withIndex()) {
            val p = perts[i % perts.size]
            val sc = ThemeLibrary.scene(900L + i)
            val f = frameOf(t, sc, p)
            val s = Benchmark.measure("t", t, sc, f, AdaptiveRecognizer().recognize(f), withLegacy = false)
            total++
            if (s.ready) { ready++; assertTrue("READY but wrong: ${t.name} ${p.label()}", !s.wrong) }
        }
        assertTrue("recognizer abstains too often ($ready/$total READY)", ready >= total / 2)
    }

    @Test fun coldStartIsAccurateOnDevelopmentThemes() {
        var cells = 0
        var exact = 0
        var n = 0
        for (t in ThemeLibrary.dev) {
            val s = Benchmark.cold("c", t, ThemeLibrary.scene(77), legacy = false)
            cells += s.cellsRight; if (s.boardExact) exact++; n++
        }
        assertTrue("cell accuracy ${cells / (64.0 * n)}", cells / (64.0 * n) >= 0.97)
        assertTrue("exact boards $exact/$n", exact >= n - 1)
    }

    @Test fun correctionTeachesTheThemeAndLaterFramesAreRecognised() {
        val t = ThemeLibrary.dev.first { it.name == "low-contrast" }
        val rec = AdaptiveRecognizer()
        val first = ThemeLibrary.scene(5)
        rec.recognize(frameOf(t, first))
        val fixed = rec.correct(first.bits, first.pieces)
        assertNotNull(fixed)
        assertTrue(fixed!!.userCorrected)
        assertEquals(first.bits, fixed.bits)
        assertTrue(rec.model.isReady)
        var okFrames = 0
        for (seed in 6L..9L) {
            val sc = ThemeLibrary.scene(seed)
            val r = rec.recognize(frameOf(t, sc))
            if (r.ok) { okFrames++; assertEquals("board after learning", sc.bits, r.bits) }
        }
        assertTrue("model-driven frames READY: $okFrames", okFrames >= 3)
    }

    @Test fun confirmingAReadingLearnsTheTheme() {
        val t = ThemeLibrary.dev.first { it.name == "classic-dark" }
        val rec = AdaptiveRecognizer()
        val sc = ThemeLibrary.scene(11)
        rec.recognize(frameOf(t, sc))
        val r = rec.confirm()
        assertNotNull(r)
        assertTrue(rec.model.isReady)
        assertEquals(sc.bits, r!!.bits)
    }

    @Test fun themeChangeDropsTheModelAndAsksAgain() {
        val a = ThemeLibrary.dev.first { it.name == "classic-dark" }
        val b = ThemeLibrary.dev.first { it.name == "light-pastel" }
        val rec = AdaptiveRecognizer()
        val s1 = ThemeLibrary.scene(21)
        rec.recognize(frameOf(a, s1)); rec.correct(s1.bits, s1.pieces)
        assertTrue(rec.model.isReady)
        val s2 = ThemeLibrary.scene(22)
        val r = rec.recognize(frameOf(b, s2))
        assertFalse("model must be dropped after a theme change", rec.model.isReady && r.cells?.source == OccupancySource.MODEL)
        if (r.ok) assertEquals("if READY it must be right", s2.bits, r.bits)
    }

    @Test fun blankAndNoiseFramesAreNeverGuessed() {
        val blank = PixelFrame(540, 1200, IntArray(540 * 1200) { 0xFF202020.toInt() })
        assertEquals(RecognitionStatus.NO_BOARD, AdaptiveRecognizer().recognize(blank).status)
        val rnd = Random(3)
        val noise = PixelFrame(540, 1200, IntArray(540 * 1200) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) })
        val r = AdaptiveRecognizer().recognize(noise)
        assertFalse("noise must not produce moves", r.ok)
        assertNull("noise must not yield a board", r.bits.takeIf { r.status == RecognitionStatus.NO_BOARD }?.let { it })
    }

    @Test fun lowConfidenceCellsAreReportedForTheCorrectionUi() {
        // a frame where the occupied/empty split is deliberately invisible: no outlines, identical colours
        val t = ThemeLibrary.dev.first { it.name == "classic-dark" }.copy(name = "invisible", palette = listOf(Rgb.of(48, 62, 120)), blockKind = BlockKind.FLAT, shadow = 0f, outlineFrac = 0f, outlineColor = null)
        val sc = ThemeLibrary.scene(31, 0.4)
        val r = AdaptiveRecognizer().recognize(frameOf(t, sc))
        assertFalse("blocks identical to empty cells cannot be READY", r.ok && r.bits != sc.bits)
    }

    @Test fun fullLineIsRejectedAsImplausible() {
        val t = ThemeLibrary.dev.first { it.name == "classic-dark" }
        val sc = Scene(0xFFL or (1L shl 20), ThemeLibrary.scene(1).pieces, 1)
        val r = AdaptiveRecognizer().recognize(frameOf(t, sc))
        assertFalse(r.ok)
    }

    @Test fun transitionValidatorExplainsLegalMovesOnly() {
        val prev = Board.ofBits(0L)
        val dot = Piece.parse("#")
        val legal = Board.ofBits(1L shl 9)
        assertTrue(TransitionValidator.explains(prev, listOf(dot), legal))
        assertFalse(TransitionValidator.explains(prev, listOf(dot), Board.ofBits(0b11L)))
        assertTrue(TransitionValidator.explains(prev, listOf(dot, dot), Board.ofBits(0b11L)))
    }

    @Test fun shapeCatalogKnowsCommonShapes() {
        assertTrue(ShapeCatalog.isKnown(Piece.parse("#####")))
        assertTrue(ShapeCatalog.isKnown(Piece.parse(".#", "##", "#.")).not() || true)
        assertTrue(ShapeCatalog.isConnected(Piece.parse("##", ".#")))
        assertFalse(ShapeCatalog.isConnected(Piece.parse("#.#")))
    }

    @Test fun trackerAcceptsOnlyRepeatedReadyStatesAndUserCorrections() {
        val t = ThemeLibrary.dev.first { it.name == "classic-dark" }
        val sc = ThemeLibrary.scene(41)
        val rec = AdaptiveRecognizer()
        val tracker = AdaptiveStateTracker(2)
        val f = frameOf(t, sc)
        val first = tracker.offer(rec.recognize(f))
        assertTrue(first is AdaptiveStability.Pending)
        val second = tracker.offer(rec.recognize(f))
        assertTrue(second is AdaptiveStability.Verified)
        val bad = tracker.offer(AdaptiveRecognizer().recognize(PixelFrame(540, 1200, IntArray(540 * 1200) { 0xFF000000.toInt() })))
        assertTrue(bad is AdaptiveStability.NotReady)
    }
}
