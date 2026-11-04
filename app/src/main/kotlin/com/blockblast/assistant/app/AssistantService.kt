package com.blockblast.assistant.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.solver.BaselineSolver
import com.blockblast.assistant.core.solver.CancellationToken
import com.blockblast.assistant.core.solver.Recommendation
import com.blockblast.assistant.core.solver.Recommender
import com.blockblast.assistant.core.solver.SearchBudget
import com.blockblast.assistant.core.solver.SearchResult
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.ChangeDetector
import com.blockblast.assistant.core.vision.FrameBaseline
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.adaptive.AdaptiveRecognizer
import com.blockblast.assistant.core.vision.adaptive.AdaptiveResult
import com.blockblast.assistant.core.vision.adaptive.AdaptiveStability
import com.blockblast.assistant.core.vision.adaptive.AdaptiveStateTracker
import com.blockblast.assistant.core.vision.adaptive.AdaptiveVerified
import com.blockblast.assistant.core.vision.adaptive.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Foreground service: MediaProjection capture -> recognition -> baseline solver -> overlay.
 * It OBSERVES the screen and DRAWS suggestions. It never injects touches, gestures or key events.
 *
 * Logic runs on one dedicated thread; UI work is posted to the main thread.
 */
class AssistantService : Service(), OverlayController.Callbacks {

    private enum class Mode { SCANNING, WATCHING }

    private val executor = Executors.newSingleThreadExecutor()
    private val logic = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + logic)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: AppSettings
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null
    private var loopJob: Job? = null
    private var solveJob: Job? = null
    private var solveToken: CancellationToken? = null

    private val recognizer = AdaptiveRecognizer()
    private val tracker = AdaptiveStateTracker(2)
    private val detector = ChangeDetector()
    private val solver = BaselineSolver()

    // Logic-thread state
    private var mode = Mode.SCANNING
    private var scanDelayMs = 600L
    private var verified: AdaptiveVerified? = null
    private var lastResult: AdaptiveResult? = null
    /** True once we have shown the editor for the current unsure episode (reset when a reading becomes READY). */
    private var reviewPrompted = false
    private var recs: List<Recommendation> = emptyList()
    private var recIndex = 0
    private var baseline: FrameBaseline? = null
    private var baselineDueAt = 0L
    private var changeStart = 0L
    private var stableTicks = 0

    // Written from the main thread
    @Volatile private var paused = false
    @Volatile private var calibrating = false
    @Volatile private var reviewing = false
    @Volatile private var highlightsEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && projection == null) {
            settings = AppSettings(this)
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data == null || !android.provider.Settings.canDrawOverlays(this)) {
                fail("Missing screen-capture grant or overlay permission")
                return START_NOT_STICKY
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = try {
                manager.getMediaProjection(code, data)
            } catch (e: Exception) {
                fail("Could not start screen capture: ${e.message}")
                return START_NOT_STICKY
            }
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, mainHandler)
            projection = proj
            val (w, h, dpi) = displayInfo()
            capturer = ScreenCapturer(proj, w, h, dpi, mainHandler)
            overlay = OverlayController(this, this).also { it.show() }
            AssistantState.update { it.copy(running = true, status = "Looking for the Block Blast board…", lastError = "-") }
            loopJob = scope.launch { runLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch { restartCapture() }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { capturer?.release() }
        runCatching { projection?.stop() }
        runCatching { overlay?.destroy() }
        capturer = null
        projection = null
        overlay = null
        AssistantState.update { it.copy(running = false, status = "Stopped") }
        executor.shutdown()
        super.onDestroy()
    }

    private fun fail(message: String) {
        AssistantState.update { it.copy(running = false, status = "Stopped", lastError = message) }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    // ---- main loop -------------------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            delay(if (mode == Mode.SCANNING) scanDelayMs else WATCH_MS)
            if (paused || calibrating || reviewing) continue
            try {
                if (mode == Mode.SCANNING) cleanScan() else watch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantState.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
                mode = Mode.SCANNING
                scanDelayMs = 1500L
            }
        }
    }

    /** Hide our windows, grab an untouched frame, restore the windows, then recognize. */
    private suspend fun cleanScan() {
        val cap = capturer ?: return
        val ov = overlay ?: return
        cap.invalidate()
        withContext(Dispatchers.Main) { ov.setCaptureHidden(true) }
        var frame: com.blockblast.assistant.core.vision.PixelFrame? = null
        delay(HIDE_SETTLE_MS)
        for (i in 0 until 6) {
            frame = cap.latestFrame()
            if (frame != null) break
            delay(100)
        }
        withContext(Dispatchers.Main) { ov.setCaptureHidden(false) }
        if (frame == null) {
            setStatus("Waiting for the first screen frame…", "")
            return
        }
        recognizer.minConfidence = 0.45 + 0.20 * settings.sensitivityPercent / 100.0
        val manual = settings.manualGeometry(frame.width, frame.height)
        val result = withContext(Dispatchers.Default) { recognizer.recognize(frame, manual) }
        handleResult(result)
    }

    /** Single place that turns a recognition result into UI + solver activity. Moves are shown ONLY for READY. */
    private suspend fun handleResult(result: AdaptiveResult) {
        lastResult = result
        publishRecognition(result)
        when (val st = tracker.offer(result)) {
            is AdaptiveStability.Verified -> {
                reviewPrompted = false
                recognizer.onVerified(result)
                onVerified(st.state)
            }
            is AdaptiveStability.Pending -> {
                scanDelayMs = 450L
                setStatus("Verifying board… (${st.seen}/${st.needed})", "")
            }
            is AdaptiveStability.NotReady -> onNotReady(result, st)
        }
    }

    private suspend fun onNotReady(result: AdaptiveResult, st: AdaptiveStability.NotReady) {
        invalidateRecommendationsQuietly()
        when (st.status) {
            RecognitionStatus.NO_BOARD -> {
                scanDelayMs = 1200L
                reviewPrompted = false
                setStatus("Board not found", "Open Block Blast with the whole board visible, or use Cal to align the grid by hand.")
            }
            RecognitionStatus.NO_PIECES -> {
                scanDelayMs = 700L
                setStatus("Waiting for pieces…", "Board read; no pieces visible yet.")
            }
            RecognitionStatus.NEEDS_CONFIRMATION, RecognitionStatus.NEEDS_CORRECTION -> {
                scanDelayMs = 1500L
                val confirm = st.status == RecognitionStatus.NEEDS_CONFIRMATION
                setStatus(
                    if (confirm) "New theme - please confirm the board" else "Recognition needs correction",
                    "No moves are shown from an unsure reading. Tap Fix to check it. ${st.reason}",
                )
                if (!reviewPrompted && result.geometry != null) {
                    reviewPrompted = true
                    openReview(result)
                }
            }
            RecognitionStatus.READY -> Unit
        }
    }

    private suspend fun invalidateRecommendationsQuietly() {
        solveToken?.cancel()
        solveJob?.cancel()
        recs = emptyList()
        verified = null
        clearHighlightsOnMain()
        AssistantState.update { it.copy(recommendationCount = 0) }
    }

    /** Opens the correction editor with what was read, low-confidence cells and pieces marked. */
    private suspend fun openReview(result: AdaptiveResult) {
        val g = result.geometry ?: return
        val ov = overlay ?: return
        val bySlot = MutableList<Piece?>(3) { null }
        val confBySlot = MutableList(3) { 1.0 }
        result.pieces.forEach { if (it.slot in 0..2) { bySlot[it.slot] = it.piece; confBySlot[it.slot] = it.confidence } }
        val cellConf = result.cells?.confidence
        val bits = result.bits ?: 0L
        val confirmMode = result.status == RecognitionStatus.NEEDS_CONFIRMATION
        reviewing = true
        withContext(Dispatchers.Main) { ov.startReview(g, bits, cellConf, bySlot, confBySlot, confirmMode) }
    }

    private suspend fun watch() {
        val cap = capturer ?: return
        val ov = overlay ?: return
        val state = verified
        if (state == null) {
            mode = Mode.SCANNING
            return
        }
        val frame = cap.latestFrame() ?: return
        val now = SystemClock.elapsedRealtime()
        val base = baseline
        if (base == null) {
            if (now >= baselineDueAt) {
                val excluded = withContext(Dispatchers.Main) { ov.excludedRects() }
                baseline = detector.baseline(frame, state.geometry, excluded)
            }
            return
        }
        val changed = detector.changedCount(frame, base)
        if (changed >= CHANGE_MIN) {
            if (changeStart == 0L) {
                changeStart = now
                invalidateRecommendations("Board changed - rescanning…")
            }
            stableTicks = 0
        } else if (changeStart != 0L) {
            stableTicks++
        }
        if (changeStart != 0L && (stableTicks >= 2 || now - changeStart > 3000L)) {
            changeStart = 0L
            stableTicks = 0
            tracker.reset()
            mode = Mode.SCANNING
            scanDelayMs = 200L
            AssistantState.update { it.copy(lastTransition = "screen settled after a change -> full rescan") }
        }
    }

    private suspend fun invalidateRecommendations(reason: String) {
        solveToken?.cancel()
        solveJob?.cancel()
        recs = emptyList()
        clearHighlightsOnMain()
        setStatus(reason, "")
        AssistantState.update { it.copy(recommendationCount = 0, lastTransition = reason) }
    }

    private suspend fun onVerified(state: AdaptiveVerified) {
        mode = Mode.WATCHING
        verified = state
        changeStart = 0L
        stableTicks = 0
        baseline = null
        AssistantState.update { it.copy(lastTransition = "state verified; searching") }
        setStatus("Board verified - searching moves…", "")
        startSolve(state)
    }

    private fun startSolve(state: AdaptiveVerified) {
        solveToken?.cancel()
        solveJob?.cancel()
        val token = CancellationToken()
        solveToken = token
        val board = Board.ofBits(state.boardBits)
        val budget = SearchBudget(maxNodes = 200_000L, maxMillis = settings.searchMillis.toLong())
        solveJob = scope.launch(Dispatchers.Default) {
            val result = solver.search(board, state.pieces, budget, token)
            if (token.isCancelled) return@launch
            withContext(logic) { if (solveToken === token) onSolved(result, state) }
        }
    }

    private suspend fun onSolved(result: SearchResult, state: AdaptiveVerified) {
        recs = Recommender.from(result, 12)
        recIndex = 0
        AssistantState.update {
            it.copy(
                searchMillis = result.elapsedMillis, nodes = result.nodesExplored,
                searchCompleted = result.completed, recommendationCount = recs.size,
            )
        }
        if (recs.isEmpty()) {
            clearHighlightsOnMain()
            setStatus("No legal placement for these pieces", "The game may be over.")
        } else {
            showCurrent()
        }
        baseline = null
        baselineDueAt = SystemClock.elapsedRealtime() + 400L
    }

    private suspend fun showCurrent() {
        val state = verified ?: return
        val rec = recs.getOrNull(recIndex) ?: return
        val ov = overlay ?: return
        val slot = state.slots.getOrNull(rec.move.pieceIndex) ?: rec.move.pieceIndex
        val detail = "Piece in the ${slotName(slot)} slot -> column ${rec.move.x + 1}, row ${rec.move.y + 1}. " +
            "${rec.explanation}. Heuristic suggestion, not a guaranteed best move."
        val opacity = settings.opacityPercent
        val seq = settings.showSequence
        withContext(Dispatchers.Main) {
            if (highlightsEnabled) ov.showRecommendation(rec, state.geometry, opacity, seq) else ov.clearHighlights()
            ov.setStatus("Move ${recIndex + 1}/${recs.size}", detail)
        }
    }

    private suspend fun restartCapture() {
        val cap = capturer ?: return
        val (w, h, _) = displayInfo()
        cap.resize(w, h)
        withContext(Dispatchers.Main) {
            overlay?.onScreenChanged()
            overlay?.clearHighlights()
        }
        recognizer.clearGeometryCache()
        tracker.reset()
        verified = null
        recs = emptyList()
        mode = Mode.SCANNING
        scanDelayMs = 400L
        AssistantState.update { it.copy(lastTransition = "screen size changed -> recalibrating") }
    }

    // ---- overlay callbacks (main thread) ---------------------------------------------------------------------------

    override fun onPrev() {
        scope.launch {
            if (recs.isNotEmpty()) {
                recIndex = (recIndex - 1 + recs.size) % recs.size
                showCurrent()
                rebaseline()
            }
        }
    }

    override fun onNext() {
        scope.launch {
            if (recs.isNotEmpty()) {
                recIndex = (recIndex + 1) % recs.size
                showCurrent()
                rebaseline()
            }
        }
    }

    override fun onRecalc() {
        scope.launch {
            solveToken?.cancel()
            tracker.reset()
            mode = Mode.SCANNING
            scanDelayMs = 100L
            AssistantState.update { it.copy(lastTransition = "manual recalculation requested") }
        }
    }

    override fun onTogglePause(): Boolean {
        paused = !paused
        val nowPaused = paused
        scope.launch {
            if (nowPaused) {
                solveToken?.cancel()
                clearHighlightsOnMain()
                setStatus("Paused", "")
            } else {
                tracker.reset()
                mode = Mode.SCANNING
                scanDelayMs = 100L
            }
        }
        return nowPaused
    }

    override fun onToggleHighlights(): Boolean {
        highlightsEnabled = !highlightsEnabled
        overlay?.setHighlightsEnabled(highlightsEnabled)
        return highlightsEnabled
    }

    override fun onCalibrate() {
        calibrating = true
        overlay?.startCalibration(verified?.geometry)
    }

    override fun onCalibrationDone(geometry: BoardGeometry) {
        val (w, h, _) = displayInfo()
        settings.setManualGeometry(geometry, w, h)
        calibrating = false
        recognizer.clearGeometryCache()
        scope.launch {
            tracker.reset()
            mode = Mode.SCANNING
            scanDelayMs = 100L
            AssistantState.update { it.copy(lastTransition = "manual calibration saved") }
        }
    }

    override fun onAutoCalibrate() {
        settings.clearManualGeometry()
        recognizer.clearGeometryCache()
        scope.launch {
            tracker.reset()
            mode = Mode.SCANNING
            scanDelayMs = 100L
            AssistantState.update { it.copy(lastTransition = "switched to automatic board detection") }
        }
    }

    override fun onRequestReview() {
        scope.launch {
            val r = lastResult
            if (r?.geometry == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AssistantService, "No board found yet - use Cal to align the grid first", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            solveToken?.cancel()
            openReview(r)
        }
    }

    override fun onReviewConfirm() {
        scope.launch {
            val r = withContext(Dispatchers.Default) { recognizer.confirm() }
            afterReview(r, "board confirmed by the user")
        }
    }

    override fun onReviewApply(bits: Long, pieces: List<Piece?>) {
        scope.launch {
            val r = withContext(Dispatchers.Default) { recognizer.correct(bits, pieces) }
            afterReview(r, "board/pieces corrected by the user")
        }
    }

    override fun onReviewCancel() {
        scope.launch {
            reviewing = false
            tracker.reset()
            mode = Mode.SCANNING
            scanDelayMs = 1500L
        }
    }

    override fun onResetTheme() {
        scope.launch {
            recognizer.resetTheme()
            tracker.reset()
            reviewPrompted = false
            mode = Mode.SCANNING
            scanDelayMs = 100L
            AssistantState.update { it.copy(lastTransition = "learned theme forgotten", themeModelActive = false) }
        }
    }

    private suspend fun afterReview(r: AdaptiveResult?, what: String) {
        reviewing = false
        if (r == null) {
            setStatus("Nothing to correct yet", "Waiting for a fresh screen reading.")
            mode = Mode.SCANNING
            scanDelayMs = 300L
            return
        }
        AssistantState.update { it.copy(lastTransition = what) }
        mode = Mode.SCANNING
        handleResult(r)
    }

    override fun onStop() {
        stopSelf()
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun rebaseline() {
        baseline = null
        baselineDueAt = SystemClock.elapsedRealtime() + 400L
    }

    private suspend fun setStatus(status: String, detail: String) {
        AssistantState.update { it.copy(status = status) }
        val ov = overlay ?: return
        withContext(Dispatchers.Main) { ov.setStatus(status, detail) }
    }

    private suspend fun clearHighlightsOnMain() {
        val ov = overlay ?: return
        withContext(Dispatchers.Main) { ov.clearHighlights() }
    }

    private fun slotName(slot: Int): String = when (slot) {
        0 -> "left"
        1 -> "middle"
        else -> "right"
    }

    private fun publishRecognition(r: AdaptiveResult) {
        val rows = r.board?.toRows() ?: emptyList()
        val pieceLines = r.pieces.map { p ->
            "slot ${p.slot} (conf %.2f): ".format(p.confidence) + p.piece.cells.joinToString(" ") { "(${it.x},${it.y})" }
        }
        val g = r.geometry
        val geo = if (g == null) "not found" else
            "left=%.1f top=%.1f cell=%.1f (%s, grid conf %.2f)".format(g.left, g.top, g.cellSize, r.geometrySource.name.lowercase(), r.geometryConfidence)
        AssistantState.update {
            it.copy(
                boardRows = rows,
                pieceLines = pieceLines,
                geometryText = geo,
                recognitionConfidence = r.confidence,
                stateHash = r.bits?.let { b -> java.lang.Long.toHexString(b) } ?: "-",
                notes = r.notes.joinToString("; ").ifEmpty { "-" },
                recognitionStatus = r.status.name,
                unsureCells = r.lowConfidenceCells.size,
                unsurePieces = r.lowConfidencePieceSlots.size,
                themeModelActive = r.themeModelActive,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayInfo(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Block Blast Assistant")
            .setContentText("Suggestion-only overlay is running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.blockblast.assistant.START"
        const val ACTION_STOP = "com.blockblast.assistant.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "assistant"
        private const val NOTIF_ID = 1
        private const val WATCH_MS = 350L
        private const val HIDE_SETTLE_MS = 220L
        private const val CHANGE_MIN = 4
    }
}
