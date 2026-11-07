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
 * content (canRetrieveWindowContent = false) and does nothing unless Autoplay asks it to drag.
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Track the foreground app so Autoplay can pause when the game is no longer in front.
            if (pkg != packageName && pkg != "com.android.systemui") foregroundPackage = pkg
        }
    }

    override fun onInterrupt() {}

    /** Drag from (fromX, fromY) to (toX, toY); calls [done] on the main thread with true if the gesture completed. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done(true)
            override fun onCancelled(g: GestureDescription?) = done(false)
        }, Handler(Looper.getMainLooper()))
        if (!ok) done(false)
    }

    companion object {
        @Volatile var instance: AutoplayAccessibilityService? = null
        @Volatile var foregroundPackage: String? = null
        val isEnabled: Boolean get() = instance != null
    }
}
