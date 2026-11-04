package com.blockblast.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.solver.Move
import com.blockblast.assistant.core.solver.Recommendation
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.FloatRect
import kotlin.math.max
import kotlin.math.min

/**
 * Floating overlay. VISUAL ONLY: the highlight window is NOT_TOUCHABLE and never forwards or injects input;
 * the small control panel only receives touches meant for its own buttons.
 * All methods must be called on the main thread.
 */
class OverlayController(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onPrev()
        fun onNext()
        fun onRecalc()
        fun onTogglePause(): Boolean
        fun onToggleHighlights(): Boolean
        fun onCalibrate()
        fun onAutoCalibrate()
        fun onStop()
        fun onCalibrationDone(geometry: BoardGeometry)
        /** User opened the editor (button) or asked for it from a "needs correction" status. */
        fun onRequestReview()
        /** Editor "Looks right": the shown reading is correct (teaches the theme model). */
        fun onReviewConfirm()
        /** Editor "Apply my fixes": the corrected board bits and the three tray slots (null = empty slot). */
        fun onReviewApply(bits: Long, pieces: List<Piece?>)
        fun onReviewCancel()
        /** Forget what the assistant learned about the current theme. */
        fun onResetTheme()
    }

    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density

    private val highlight = HighlightView(ctx)
    private val panel = LinearLayout(ctx)
    private val statusView = TextView(ctx)
    private val detailView = TextView(ctx)
    private val controls = LinearLayout(ctx)
    private lateinit var panelParams: WindowManager.LayoutParams
    private var highlightParams: WindowManager.LayoutParams? = null
    private var calibration: CalibrationView? = null
    private var review: ReviewView? = null
    private var added = false

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun baseFlags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    fun show() {
        if (added) return
        val (w, h) = screenSize()
        val hp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(false), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        highlightParams = hp
        wm.addView(highlight, hp)

        buildPanel()
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(true) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(48)
        }
        wm.addView(panel, panelParams)
        added = true
    }

    fun destroy() {
        stopCalibration()
        stopReview()
        if (added) {
            runCatching { wm.removeView(highlight) }
            runCatching { wm.removeView(panel) }
            added = false
        }
    }

    /** Called after rotation / display size change. */
    fun onScreenChanged() {
        val hp = highlightParams ?: return
        val (w, h) = screenSize()
        hp.width = w
        hp.height = h
        if (added) wm.updateViewLayout(highlight, hp)
        highlight.invalidate()
    }

    /** Hide both windows so a screen capture sees the unmodified game. */
    fun setCaptureHidden(hidden: Boolean) {
        val v = if (hidden) View.INVISIBLE else View.VISIBLE
        highlight.visibility = v
        panel.visibility = v
    }

    fun setHighlightsEnabled(enabled: Boolean) {
        highlight.drawEnabled = enabled
        highlight.invalidate()
    }

    fun showRecommendation(rec: Recommendation, geometry: BoardGeometry, opacityPercent: Int, showSequence: Boolean) {
        highlight.geometry = geometry
        highlight.recommendation = rec
        highlight.alphaValue = (opacityPercent.coerceIn(10, 95) * 255) / 100
        highlight.showSequence = showSequence
        highlight.invalidate()
    }

    fun clearHighlights() {
        highlight.recommendation = null
        highlight.invalidate()
    }

    fun setStatus(status: String, detail: String) {
        statusView.text = status
        detailView.text = detail
    }

    fun setPausedLabel(paused: Boolean) {
        (controls.findViewWithTag<Button>("pause"))?.text = if (paused) "Resume" else "Pause"
    }

    /** Screen-pixel rectangles currently covered by our own drawing (highlight cells and the control panel). */
    fun excludedRects(): List<FloatRect> {
        val out = ArrayList<FloatRect>()
        out.addAll(highlight.coveredRects())
        if (added && panel.width > 0) {
            val loc = IntArray(2)
            panel.getLocationOnScreen(loc)
            out.add(FloatRect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + panel.width).toFloat(), (loc[1] + panel.height).toFloat()))
        }
        return out
    }

    // ---- control panel ---------------------------------------------------------------------------------------------

    private fun smallButton(label: String, tag: String? = null, onClick: (Button) -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (tag != null) this.tag = tag
            setOnClickListener { onClick(this) }
        }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(8), dp(6), dp(8), dp(6))
        panel.background = GradientDrawable().apply {
            setColor(Color.argb(215, 18, 24, 48))
            cornerRadius = dp(10).toFloat()
        }
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 13f
        statusView.text = "Starting…"
        detailView.setTextColor(Color.rgb(200, 210, 235))
        detailView.textSize = 11f
        detailView.maxWidth = dp(260)
        panel.addView(statusView)
        panel.addView(detailView)

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(smallButton("Prev") { callbacks.onPrev() })
        row1.addView(smallButton("Next") { callbacks.onNext() })
        row1.addView(smallButton("Redo") { callbacks.onRecalc() })
        row1.addView(smallButton("Pause", "pause") { b -> b.text = if (callbacks.onTogglePause()) "Resume" else "Pause" })
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton("Hide") { b -> b.text = if (callbacks.onToggleHighlights()) "Hide" else "Show" })
        row2.addView(smallButton("Cal") { callbacks.onCalibrate() })
        row2.addView(smallButton("Auto") { callbacks.onAutoCalibrate() })
        row2.addView(smallButton("Stop") { callbacks.onStop() })
        val row3 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(smallButton("Fix") { callbacks.onRequestReview() })
        row3.addView(smallButton("Reset theme") { callbacks.onResetTheme() })
        controls.orientation = LinearLayout.VERTICAL
        controls.addView(row1)
        controls.addView(row2)
        controls.addView(row3)
        panel.addView(controls)

        // Drag by the status line; tap it to collapse or expand.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        statusView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = panelParams.x; startY = panelParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                    if (moved) {
                        panelParams.x = startX + dx.toInt()
                        panelParams.y = startY + dy.toInt()
                        wm.updateViewLayout(panel, panelParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    val collapsed = controls.visibility == View.VISIBLE
                    controls.visibility = if (collapsed) View.GONE else View.VISIBLE
                    detailView.visibility = if (collapsed) View.GONE else View.VISIBLE
                }
            }
            true
        }
    }

    // ---- manual calibration ----------------------------------------------------------------------------------------

    fun startCalibration(initial: BoardGeometry?) {
        if (calibration != null) return
        val (w, h) = screenSize()
        val start = initial ?: BoardGeometry(w * 0.05f, h * 0.22f, w * 0.9f / 8f)
        val view = CalibrationView(ctx, start) { g ->
            stopCalibration()
            callbacks.onCalibrationDone(g)
        }
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(true), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(view, lp)
        calibration = view
    }

    private fun stopCalibration() {
        calibration?.let { runCatching { wm.removeView(it) } }
        calibration = null
    }

    // ---- manual correction / confirmation -------------------------------------------------------------------------

    /**
     * Opens the editor over the board. Highlights are cleared first so no move is shown from an unconfirmed reading.
     * [cellConfidence] (64 values) and [pieceConfidence] (per slot) only drive the red "unsure" outlines.
     */
    fun startReview(
        geometry: BoardGeometry, bits: Long, cellConfidence: DoubleArray?, pieces: List<Piece?>,
        pieceConfidence: List<Double>, confirmMode: Boolean,
    ) {
        if (review != null) return
        clearHighlights()
        val (w, h) = screenSize()
        val view = ReviewView(
            ctx, geometry, bits, cellConfidence, pieces, pieceConfidence, confirmMode, w,
            onConfirm = { stopReview(); callbacks.onReviewConfirm() },
            onApply = { b, p -> stopReview(); callbacks.onReviewApply(b, p) },
            onCancel = { stopReview(); callbacks.onReviewCancel() },
        )
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(true), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(view, lp)
        review = view
    }

    fun stopReview() {
        review?.let { runCatching { wm.removeView(it) } }
        review = null
    }

    // ---- views -----------------------------------------------------------------------------------------------------

    private class HighlightView(context: Context) : View(context) {
        var geometry: BoardGeometry? = null
        var recommendation: Recommendation? = null
        var alphaValue: Int = 140
        var showSequence: Boolean = true
        var drawEnabled: Boolean = true

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }

        private fun cellsOf(m: Move): List<Pair<Int, Int>> = m.piece.cells.map { (m.x + it.x) to (m.y + it.y) }

        fun coveredRects(): List<FloatRect> {
            val g = geometry ?: return emptyList()
            val rec = recommendation ?: return emptyList()
            if (!drawEnabled) return emptyList()
            val moves = if (showSequence) rec.sequence else listOf(rec.move)
            return moves.flatMap { cellsOf(it) }.map { (x, y) -> g.cellRect(x, y) }
        }

        override fun onDraw(canvas: Canvas) {
            val g = geometry ?: return
            val rec = recommendation ?: return
            if (!drawEnabled) return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            label.textSize = g.cellSize * 0.45f

            if (showSequence) {
                rec.sequence.drop(1).forEachIndexed { i, m ->
                    val color = if (i == 0) Color.rgb(255, 170, 40) else Color.rgb(90, 170, 255)
                    stroke.color = color
                    for ((x, y) in cellsOf(m)) canvas.drawRoundRect(inset(g.cellRect(x, y), 6f), 8f, 8f, stroke)
                    val (fx, fy) = cellsOf(m).first()
                    val r = g.cellRect(fx, fy)
                    label.color = color
                    canvas.drawText("${i + 2}", (r.left + r.right) / 2f, (r.top + r.bottom) / 2f + label.textSize / 3f, label)
                }
            }
            fill.color = Color.argb(alphaValue, 40, 220, 110)
            stroke.color = Color.WHITE
            for ((x, y) in cellsOf(rec.move)) {
                val r = inset(g.cellRect(x, y), 3f)
                canvas.drawRoundRect(r, 8f, 8f, fill)
                canvas.drawRoundRect(r, 8f, 8f, stroke)
            }
            val (fx, fy) = cellsOf(rec.move).first()
            val r0 = g.cellRect(fx, fy)
            label.color = Color.WHITE
            canvas.drawText("1", (r0.left + r0.right) / 2f, (r0.top + r0.bottom) / 2f + label.textSize / 3f, label)
        }

        private fun inset(r: FloatRect, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)
    }

    private class CalibrationView(
        context: Context,
        start: BoardGeometry,
        private val onDone: (BoardGeometry) -> Unit,
    ) : FrameLayout(context) {
        private var boardLeft = start.left
        private var boardTop = start.top
        private var side = start.cellSize * 8f
        private var mode = 0 // 0 none, 1 move, 2 resize
        private var lastX = 0f
        private var lastY = 0f
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.FILL }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            val done = Button(context).apply {
                this.text = "Done – board aligned"
                setOnClickListener { finish() }
            }
            addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 120 })
        }

        private fun finish() {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            onDone(BoardGeometry(boardLeft, boardTop, side / 8f))
        }

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawRect(boardLeft, boardTop, boardLeft + side, boardTop + side, line)
            for (i in 1 until 8) {
                canvas.drawLine(boardLeft + i * side / 8f, boardTop, boardLeft + i * side / 8f, boardTop + side, line)
                canvas.drawLine(boardLeft, boardTop + i * side / 8f, boardLeft + side, boardTop + i * side / 8f, line)
            }
            canvas.drawCircle(boardLeft + side, boardTop + side, 36f, handle)
            canvas.drawText("Drag to move; drag the yellow dot to resize", boardLeft, max(60f, boardTop - 20f), text)
            canvas.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    val dx = e.rawX - (boardLeft + side)
                    val dy = e.rawY - (boardTop + side)
                    mode = when {
                        dx * dx + dy * dy < 90f * 90f -> 2
                        e.rawX in boardLeft..(boardLeft + side) && e.rawY in boardTop..(boardTop + side) -> 1
                        else -> 0
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        boardLeft += e.rawX - lastX
                        boardTop += e.rawY - lastY
                    } else if (mode == 2) {
                        side = max(160f, min(e.rawX - boardLeft, e.rawY - boardTop))
                    }
                    lastX = e.rawX; lastY = e.rawY
                    invalidate()
                }
                MotionEvent.ACTION_UP -> mode = 0
            }
            return true
        }
    }
}
