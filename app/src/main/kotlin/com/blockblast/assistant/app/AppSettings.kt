package com.blockblast.assistant.app

import android.content.Context
import com.blockblast.assistant.core.vision.BoardGeometry

class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var opacityPercent: Int
        get() = p.getInt("opacity", 55)
        set(v) = p.edit().putInt("opacity", v.coerceIn(10, 95)).apply()

    var searchMillis: Int
        get() = p.getInt("search_ms", 800)
        set(v) = p.edit().putInt("search_ms", v.coerceIn(100, 5000)).apply()

    var sensitivityPercent: Int
        get() = p.getInt("sensitivity", 50)
        set(v) = p.edit().putInt("sensitivity", v.coerceIn(0, 100)).apply()

    var showSequence: Boolean
        get() = p.getBoolean("show_sequence", true)
        set(v) = p.edit().putBoolean("show_sequence", v).apply()

    var showDiagnostics: Boolean
        get() = p.getBoolean("diagnostics", false)
        set(v) = p.edit().putBoolean("diagnostics", v).apply()

    /** A manual calibration is only valid for the screen size it was made on. */
    fun manualGeometry(screenW: Int, screenH: Int): BoardGeometry? {
        if (p.getInt("cal_w", -1) != screenW || p.getInt("cal_h", -1) != screenH) return null
        if (!p.contains("cal_cell")) return null
        return BoardGeometry(p.getFloat("cal_left", 0f), p.getFloat("cal_top", 0f), p.getFloat("cal_cell", 0f))
    }

    fun setManualGeometry(g: BoardGeometry, screenW: Int, screenH: Int) {
        p.edit().putInt("cal_w", screenW).putInt("cal_h", screenH)
            .putFloat("cal_left", g.left).putFloat("cal_top", g.top).putFloat("cal_cell", g.cellSize).apply()
    }

    fun clearManualGeometry() {
        p.edit().remove("cal_w").remove("cal_h").remove("cal_left").remove("cal_top").remove("cal_cell").apply()
    }
}
