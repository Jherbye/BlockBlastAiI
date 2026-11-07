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
import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.solver.Move
import com.blockblast.assistant.core.solver.Recommendation
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.FloatRect
import kotlin.math.max
import kotlin.math.min

/**
 * Floating overlay. The control panel is created ONCE and kept until the assistant stops: it is never recreated, never
 * hidden by a scan (unless the user dragged it over the board/tray), and only re-rendered when [PanelState] changes.
 * The highlight window is NOT_TOUCHABLE and never forwards input. All methods must be called on the main thread.
 */
class OverlayController(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onPrev()
        fun onNext()
        fun onScan()
        fun onUndo()
        fun onToggleMode()
        fun onRunButton()
        fun onToggleHighlights(): Boolean
        fun onCalibrate()
        fun onAutoCalibrate()
        fun onFix()
        fun onEmergencyStop()
        fun onQuit()
        fun onCalibrationDone(geometry: BoardGeometry)
        fun onCorrectionDone(bits: Long, slotPieces: List<Piece?>)
        fun onCorrectionCancelled()
    }

    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density

    private val highlight = HighlightView(ctx)
    private val panel = LinearLayout(ctx)
    private val dotView = TextView(ctx)
    private val titleView = TextView(ctx)
    private val modeChip = TextView(ctx)
    private val collapseBtn = Button(ctx)
    private val levelView = TextView(ctx)
    private val statsView = TextView(ctx)
    private val messageView = TextView(ctx)
    private val body = LinearLayout(ctx)
    private val buttons = HashMap<String, Button>()
    private lateinit var panelParams: WindowManager.LayoutParams
    private var highlightParams: WindowManager.LayoutParams? = null
    private var calibration: CalibrationView? = null
    private var correction: CorrectionView? = null
    private var added = false
    private var last: PanelState? = null

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
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(true) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (PanelMemory.x == Int.MIN_VALUE) dp(8) else PanelMemory.x
            y = if (PanelMemory.y == Int.MIN_VALUE) dp(48) else PanelMemory.y
        }
        wm.addView(panel, panelParams)
        added = true
        applyCollapsed(PanelMemory.collapsed)
    }

    fun destroy() {
        stopCalibration()
        stopCorrection()
        if (added) {
            runCatching { wm.removeView(highlight) }
            runCatching { wm.removeView(panel) }
            added = false
        }
    }

    fun onScreenChanged() {
        val hp = highlightParams ?: return
        val (w, h) = screenSize()
        hp.width = w
        hp.height = h
        if (added) wm.updateViewLayout(highlight, hp)
        highlight.invalidate()
    }

    /**
     * Hide our drawing so a capture sees the unmodified game. The highlight window always hides (it is invisible when
     * there is nothing to highlight anyway). The control panel stays visible unless it overlaps [gameRegion].
     */
    fun setCaptureHidden(hidden: Boolean, gameRegion: FloatRect?) {
        highlight.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        val hidePanel = hidden && gameRegion != null && panelRect()?.let { intersects(it, gameRegion) } == true
        panel.visibility = if (hidePanel) View.INVISIBLE else View.VISIBLE
    }

    fun panelOverlaps(region: FloatRect): Boolean = panelRect()?.let { intersects(it, region) } == true

    private fun intersects(a: FloatRect, b: FloatRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun panelRect(): FloatRect? {
        if (!added || panel.width <= 0) return null
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        return FloatRect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + panel.width).toFloat(), (loc[1] + panel.height).toFloat())
    }

    fun setHighlightsEnabled(enabled: Boolean) {
        highlight.drawEnabled = enabled
        highlight.invalidate()
    }

    fun showRecommendation(rec: Recommendation, geometry: BoardGeometry, opacityPercent: Int, showSequence: Boolean) {
        val alpha = (opacityPercent.coerceIn(10, 95) * 255) / 100
        if (highlight.recommendation == rec && highlight.geometry == geometry && highlight.alphaValue == alpha && highlight.showSequence == showSequence) return
        highlight.geometry = geometry
        highlight.recommendation = rec
        highlight.alphaValue = alpha
        highlight.showSequence = showSequence
        highlight.invalidate()
    }

    fun clearHighlights() {
        if (highlight.recommendation == null) return
        highlight.recommendation = null
        highlight.invalidate()
    }

    /** Screen-pixel rectangles currently covered by our own drawing (highlight cells and the control panel). */
    fun excludedRects(): List<FloatRect> {
        val out = ArrayList<FloatRect>()
        out.addAll(highlight.coveredRects())
        panelRect()?.let { out.add(it) }
        return out
    }

    // ---- panel ------------------------------------------------------------------------------------------------------

    private fun levelColor(l: Level) = when (l) {
        Level.GOOD -> Color.rgb(76, 175, 80)
        Level.UNCERTAIN -> Color.rgb(255, 193, 7)
        Level.NEEDS_CORRECTION -> Color.rgb(244, 67, 54)
        Level.SEARCHING -> Color.rgb(144, 164, 174)
    }

    private fun setIfChanged(tv: TextView, text: String) {
        if (tv.text.toString() != text) tv.text = text
    }

    /** Re-renders only what changed. Safe to call as often as you like. */
    fun render(s: PanelState) {
        if (s == last) return
        last = s
        dotView.setTextColor(levelColor(s.level))
        setIfChanged(
            levelView,
            "Recognition: " + when (s.level) {
                Level.GOOD -> "Good"
                Level.UNCERTAIN -> "Uncertain"
                Level.NEEDS_CORRECTION -> "Needs correction"
                Level.SEARCHING -> "Searching..."
            },
        )
        levelView.setTextColor(levelColor(s.level))
        setIfChanged(
            statsView,
            "Grid " + (s.gridConfidence?.let { "%.2f".format(it) } ?: "-") + "  Board " +
                (s.boardConfidence?.let { "%.2f".format(it) } ?: "-") + "  Pieces " + s.piecesText +
                (if (s.progress.isEmpty()) "" else "\n" + s.progress),
        )
        setIfChanged(messageView, s.message)
        messageView.setTextColor(
            when (s.severity) {
                Severity.INFO -> Color.rgb(207, 216, 220)
                Severity.WARNING -> Color.rgb(255, 213, 79)
                Severity.ERROR -> Color.rgb(255, 138, 128)
            },
        )
        setIfChanged(modeChip, if (s.mode == Mode.AUTOPLAY) "AUTOPLAY" else "ASSIST")
        modeChip.setBackgroundColor(if (s.mode == Mode.AUTOPLAY) Color.rgb(198, 40, 40) else Color.rgb(57, 73, 171))
        buttons["mode"]?.let { setIfChanged(it, if (s.mode == Mode.AUTOPLAY) "Autoplay" else "Assist") }
        buttons["run"]?.let {
            setIfChanged(
                it,
                if (s.mode == Mode.ASSIST) (if (s.assistPaused) "Resume" else "Pause")
                else when (s.run) { RunState.RUNNING -> "Pause"; RunState.PAUSED, RunState.NEEDS_ATTENTION -> "Resume"; RunState.STOPPED -> "Start" },
            )
        }
        buttons["hide"]?.let { setIfChanged(it, if (s.highlightsOn) "Hide" else "Show") }
        buttons["fix"]?.let { setIfChanged(it, if (s.level == Level.NEEDS_CORRECTION || s.level == Level.UNCERTAIN) "FIX!" else "Fix") }
    }

    private fun button(label: String, tag: String, color: Int? = null, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            if (color != null) setBackgroundColor(color)
            setOnClickListener { onClick() }
            buttons[tag] = this
        }

    private fun row(vararg b: Button): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in b) addView(x, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(10), dp(8), dp(10), dp(8))
        panel.background = GradientDrawable().apply { setColor(Color.argb(230, 18, 24, 48)); cornerRadius = dp(12).toFloat() }

        dotView.text = "\u25CF"; dotView.textSize = 16f
        titleView.text = "Block Blast Assistant"; titleView.setTextColor(Color.WHITE); titleView.textSize = 14f
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        modeChip.setTextColor(Color.WHITE); modeChip.textSize = 11f; modeChip.setPadding(dp(6), dp(2), dp(6), dp(2))
        collapseBtn.text = "\u2013"; collapseBtn.textSize = 16f; collapseBtn.minWidth = 0; collapseBtn.minimumWidth = 0
        collapseBtn.minHeight = 0; collapseBtn.minimumHeight = 0; collapseBtn.setPadding(0, 0, 0, 0)
        collapseBtn.setOnClickListener { applyCollapsed(!PanelMemory.collapsed) }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dotView, LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeChip)
            addView(collapseBtn, LinearLayout.LayoutParams(dp(36), dp(32)).apply { leftMargin = dp(6) })
        }
        panel.addView(header)
        levelView.textSize = 13f; levelView.setTypeface(levelView.typeface, android.graphics.Typeface.BOLD)
        statsView.setTextColor(Color.rgb(176, 190, 197)); statsView.textSize = 12f
        messageView.textSize = 12f; messageView.minLines = 2; messageView.maxLines = 4
        panel.addView(levelView); panel.addView(statsView)

        body.orientation = LinearLayout.VERTICAL
        body.addView(messageView)
        body.addView(row(button("Assist", "mode") { callbacks.onToggleMode() }, button("Pause", "run") { callbacks.onRunButton() },
            button("Prev", "prev") { callbacks.onPrev() }, button("Next", "next") { callbacks.onNext() }))
        body.addView(row(button("Undo", "undo") { callbacks.onUndo() }, button("Hide", "hide") { callbacks.onToggleHighlights() },
            button("Cal", "cal") { callbacks.onCalibrate() }, button("Auto", "auto") { callbacks.onAutoCalibrate() }))
        body.addView(row(button("Fix", "fix") { callbacks.onFix() }, button("Scan", "scan") { callbacks.onScan() },
            button("E-STOP", "estop", Color.rgb(183, 28, 28)) { callbacks.onEmergencyStop() }, button("Quit", "quit") { callbacks.onQuit() }))
        panel.addView(body)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = panelParams.x; startY = panelParams.y }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (e.rawX - downX).toInt()
                    panelParams.y = startY + (e.rawY - downY).toInt()
                    PanelMemory.x = panelParams.x; PanelMemory.y = panelParams.y
                    wm.updateViewLayout(panel, panelParams)
                }
            }
            true
        }
        header.setOnTouchListener(drag)
        render(PanelState())
    }

    private fun applyCollapsed(collapsed: Boolean) {
        PanelMemory.collapsed = collapsed
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        statsView.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseBtn.text = if (collapsed) "+" else "\u2013"
    }

    // ---- manual calibration and correction -----------------------------------------------------------------------

    fun startCalibration(initial: BoardGeometry?) {
        if (calibration != null) return
        val (w, h) = screenSize()
        val start = initial ?: BoardGeometry(w * 0.05f, h * 0.22f, w * 0.9f / 8f)
        val view = CalibrationView(ctx, start) { g -> stopCalibration(); callbacks.onCalibrationDone(g) }
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

    fun startCorrection(geometry: BoardGeometry, bits: Long, slotPieces: List<Piece?>, uncertainSlots: List<Int>) {
        if (correction != null) return
        val (w, h) = screenSize()
        val view = CorrectionView(ctx, geometry, bits, slotPieces, uncertainSlots,
            { b, p -> stopCorrection(); callbacks.onCorrectionDone(b, p) }, { stopCorrection(); callbacks.onCorrectionCancelled() })
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(true), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(view, lp)
        correction = view
    }

    private fun stopCorrection() {
        correction?.let { runCatching { wm.removeView(it) } }
        correction = null
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

    /**
     * Manual correction. Draws the recognized state over the game: tap a board cell to toggle occupied/empty, tap cells
     * of the three 5x5 piece editors to fix the pieces. Touches here edit OUR data only; nothing is sent to the game.
     */
    private class CorrectionView(
        context: Context,
        private val geometry: BoardGeometry,
        bits: Long,
        slotPieces: List<Piece?>,
        private val uncertainSlots: List<Int>,
        private val onDone: (Long, List<Piece?>) -> Unit,
        private val onCancel: () -> Unit,
    ) : FrameLayout(context) {
        private var cellBits = bits
        private val grids = Array(3) { BooleanArray(25) }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.CYAN }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f }

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            for (slot in 0 until 3) slotPieces.getOrNull(slot)?.cells?.forEach { if (it.x < 5 && it.y < 5) grids[slot][it.y * 5 + it.x] = true }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(context).apply { text = "Confirm"; setOnClickListener { confirm() } })
            row.addView(Button(context).apply { text = "Cancel"; setOnClickListener { onCancel() } })
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 120 })
        }

        private fun confirm() {
            val pieces = grids.map { g ->
                val cells = ArrayList<Cell>()
                for (i in 0 until 25) if (g[i]) cells.add(Cell(i % 5, i / 5))
                if (cells.isEmpty()) null else Piece(cells)
            }
            onDone(cellBits, pieces)
        }

        private fun miniCell() = 0.30f * geometry.cellSize
        private fun gridOrigin(slot: Int): Pair<Float, Float> {
            val m = miniCell()
            return (width * (2 * slot + 1) / 6f - 2.5f * m) to (geometry.bottom + 0.6f * geometry.cellSize)
        }

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            for (y in 0 until 8) for (x in 0 until 8) {
                val r = geometry.cellRect(x, y)
                val rect = RectF(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3)
                if ((cellBits ushr (y * 8 + x)) and 1L == 1L) {
                    fillPaint.color = Color.argb(170, 40, 220, 110)
                    canvas.drawRoundRect(rect, 8f, 8f, fillPaint)
                }
                canvas.drawRoundRect(rect, 8f, 8f, linePaint)
            }
            canvas.drawText("Tap board cells that are OCCUPIED. Edit the pieces below. Then Confirm.", geometry.left, max(60f, geometry.top - 24f), textPaint)
            val m = miniCell()
            for (slot in 0 until 3) {
                val (ox, oy) = gridOrigin(slot)
                for (i in 0 until 25) {
                    val rx = ox + (i % 5) * m
                    val ry = oy + (i / 5) * m
                    val rect = RectF(rx + 2, ry + 2, rx + m - 2, ry + m - 2)
                    fillPaint.color = if (grids[slot][i]) Color.argb(200, 255, 190, 60) else Color.argb(60, 255, 255, 255)
                    canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
                }
                canvas.drawText(if (slot in uncertainSlots) "piece ${slot + 1} - CHECK THIS ONE" else "piece ${slot + 1}", ox, oy - 8f, textPaint)
            }
            canvas.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            val x = e.rawX
            val y = e.rawY
            val cx = ((x - geometry.left) / geometry.cellSize).toInt()
            val cy = ((y - geometry.top) / geometry.cellSize).toInt()
            if (x >= geometry.left && y >= geometry.top && cx in 0..7 && cy in 0..7) {
                cellBits = cellBits xor (1L shl (cy * 8 + cx))
                invalidate()
                return true
            }
            val m = miniCell()
            for (slot in 0 until 3) {
                val (ox, oy) = gridOrigin(slot)
                val gx = ((x - ox) / m).toInt()
                val gy = ((y - oy) / m).toInt()
                if (x >= ox && y >= oy && gx in 0..4 && gy in 0..4) {
                    grids[slot][gy * 5 + gx] = !grids[slot][gy * 5 + gx]
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}
