package com.blockblast.assistant.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Optional input channel for Autoplay mode ONLY. It exists so that gestures can be dispatched with the user's explicit
 * Android accessibility permission (MediaProjection gives screen capture, not touch input). It never reads window
 * content (canRetrieveWindowContent = false), ignores all events, and does nothing unless Autoplay asks it to drag.
 * The user can switch it off at any time in Android Settings > Accessibility.
 */
class AutoplayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service only dispatches the gestures Autoplay asks for and observes nothing.
    }

    override fun onInterrupt() {}

    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    /** Touch down at (x, y) and HOLD it there for [holdMs] without lifting. [done] gets true on the main thread when the hold completed. */
    fun begin(x: Float, y: Float, holdMs: Long, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs, true)
        lastStroke = stroke
        lastX = x
        lastY = y
        dispatch(stroke, done)
    }

    /**
     * Continue the held touch: move from the last point to (x, y) over [durationMs]. With [keepDown] = false the touch is
     * lifted at the end (the drop). Must follow [begin] or another [moveTo] with keepDown = true.
     */
    fun moveTo(x: Float, y: Float, durationMs: Long, keepDown: Boolean, done: (Boolean) -> Unit) {
        val prev = lastStroke
        if (prev == null) { done(false); return }
        val path = Path().apply { moveTo(lastX, lastY); if (x != lastX || y != lastY) lineTo(x, y) }
        val stroke = try { prev.continueStroke(path, 0L, durationMs, keepDown) } catch (e: IllegalArgumentException) { lastStroke = null; done(false); return }
        lastStroke = if (keepDown) stroke else null
        lastX = x
        lastY = y
        dispatch(stroke, done)
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription, done: (Boolean) -> Unit) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done(true)
            override fun onCancelled(g: GestureDescription?) { lastStroke = null; done(false) }
        }, Handler(Looper.getMainLooper()))
        if (!ok) { lastStroke = null; done(false) }
    }

    companion object {
        @Volatile var instance: AutoplayAccessibilityService? = null
        val isEnabled: Boolean get() = instance != null
    }
}
