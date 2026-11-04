package com.blockblast.assistant.core.vision.adaptive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for tray readings that used to be silently wrong (synthetic frames). */
class TraySafetyTest {
    private fun readyButWrong(seed: Long, sceneSeed: Long, w: Int = 1080, h: Int = 2400, dev: String? = null): Boolean {
        val t = if (dev != null) ThemeLibrary.dev.first { it.name == dev } else ThemeLibrary.random(seed)
        val sc = ThemeLibrary.scene(sceneSeed)
        val r = AdaptiveRecognizer().recognize(ThemeRenderer.render(t, sc, w, h))
        val truth = sc.pieces.mapIndexedNotNull { i, p -> p?.let { i to it } }
        val got = r.pieces.associate { it.slot to it.piece }
        val right = r.bits == sc.bits && truth.all { (i, p) -> got[i] == p } && got.size == truth.size
        return r.ok && !right
    }

    @Test fun pieceMergedWithPatternedBackgroundIsNeverSilentlyDropped() =
        assertFalse(readyButWrong(0, 300, 1080, 2160, dev = "transparent-panel"))

    @Test fun tallPiecesCutOffByABannerDoNotProduceAConfidentReading() {
        assertFalse(readyButWrong(5001, 300))
        assertFalse(readyButWrong(5033, 300))
    }

    @Test fun normalLayoutStillReadsReady() {
        val t = ThemeLibrary.dev.first { it.name == "transparent-panel" }
        val r = AdaptiveRecognizer().recognize(ThemeRenderer.render(t, ThemeLibrary.scene(300)))
        assertTrue("status=${r.status} notes=${r.notes}", r.ok)
    }
}
