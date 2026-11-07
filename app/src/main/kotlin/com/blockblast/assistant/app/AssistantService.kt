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
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.blockblast.assistant.core.autoplay.AutoplayPlanner
import com.blockblast.assistant.core.autoplay.DragPlan
import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.rules.RulesEngine
import com.blockblast.assistant.core.solver.BaselineSolver
import com.blockblast.assistant.core.solver.CancellationToken
import com.blockblast.assistant.core.solver.Recommendation
import com.blockblast.assistant.core.solver.Recommender
import com.blockblast.assistant.core.solver.SearchBudget
import com.blockblast.assistant.core.solver.SearchResult
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.ChangeDetector
import com.blockblast.assistant.core.vision.FloatRect
import com.blockblast.assistant.core.vision.FrameBaseline
import com.blockblast.assistant.core.vision.PixelFrame
import com.blockblast.assistant.core.vision.RecognitionPipeline
import com.blockblast.assistant.core.vision.RecognitionResult
import com.blockblast.assistant.core.vision.Stability
import com.blockblast.assistant.core.vision.StableStateTracker
import com.blockblast.assistant.core.vision.VerifiedState
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Foreground service: MediaProjection capture -> recognition -> baseline solver -> overlay (Assist mode), and
 * optionally -> verified drag gestures (Autoplay mode, via the OPTIONAL accessibility service).
 *
 * Assist mode never acts on the game. Autoplay only acts when the user switched to it and pressed Start, only with a
 * verified recognition, verifies every move against the rules engine's predicted board, and stops on any doubt.
 * Logic runs on one dedicated thread; UI work is posted to the main thread.
 */
class AssistantService : Service(), OverlayController.Callbacks {

    private enum class ScanMode { SCANNING, WATCHING }
    private class PendingMove(val move: com.blockblast.assistant.core.solver.Move, val piece: Piece, val before: Board, val expected: Board, val cell: Float)

    private val executor = Executors.newSingleThreadExecutor()
    private val logic = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + logic)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rules = RulesEngine()

    private lateinit var settings: AppSettings
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayController? = null
    private var loopJob: Job? = null
    private var solveJob: Job? = null
    private var solveToken: CancellationToken? = null

    private val pipeline = RecognitionPipeline()
    private val tracker = StableStateTracker(2)
    private val detector = ChangeDetector()
    private val solver = BaselineSolver()

    // ---- logic-thread state ----
    private var scanMode = ScanMode.SCANNING
    private var scanDelayMs = 600L
    private var verified: VerifiedState? = null
    private var recs: List<Recommendation> = emptyList()
    private var recIndex = 0
    private var baseline: FrameBaseline? = null
    private var baselineDueAt = 0L
    private var changeStart = 0L
    private var stableTicks = 0
    private var lastResult: RecognitionResult? = null
    private var lastFrame: PixelFrame? = null
    private var undoState: Pair<VerifiedState?, DoubleArray?>? = null

    // ---- panel / status (logic thread; rendered on main only when it changes) ----
    private var panel = PanelState()
    private var postedPanel: PanelState? = null
    private var lastMessageAt = 0L
    private var pendingMessage: Pair<String, Severity>? = null

    // ---- autoplay (logic thread unless @Volatile) ----
    private var mode = Mode.ASSIST
    private var run = RunState.STOPPED
    private var pending: PendingMove? = null
    private var moveCount = 0
    private var mismatches = 0
    private var notAccepted = 0
    private var lostSince = 0L
    private var lastMoveAt = 0L
    private var targetPackage: String? = null
    @Volatile private var emergency = false

    // ---- written from the main thread ----
    @Volatile private var assistPaused = false
    @Volatile private var editing = false // calibration or correction overlay is open: scanning pauses
    @Volatile private var highlightsEnabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ESTOP -> { onEmergencyStop(); return START_NOT_STICKY }
        }
        if (intent?.action == ACTION_START && projection == null) {
            settings = AppSettings(this)
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data == null || !Settings.canDrawOverlays(this)) {
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
                override fun onStop() { stopSelf() }
            }, mainHandler)
            projection = proj
            val (w, h, dpi) = displayInfo()
            capturer = ScreenCapturer(proj, w, h, dpi, mainHandler)
            overlay = OverlayController(this, this).also { it.show() }
            AssistantState.update { it.copy(running = true, status = "Looking for the Block Blast board...", lastError = "-") }
            scope.launch { setPanel { it.copy(message = "Looking for the Block Blast board...") } }
            loopJob = scope.launch { runLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch { restartCapture() }
    }

    override fun onDestroy() {
        emergency = true
        scope.cancel()
        runCatching { capturer?.release() }
        runCatching { projection?.stop() }
        runCatching { overlay?.destroy() }
        capturer = null; projection = null; overlay = null
        AssistantState.update { it.copy(running = false, status = "Stopped") }
        executor.shutdown()
        super.onDestroy()
    }

    private fun fail(message: String) {
        AssistantState.update { it.copy(running = false, status = "Stopped", lastError = message) }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    // ---- stable status publishing ----------------------------------------------------------------------------------

    /** Applies [t] to the panel state; renders on the main thread only if the state really changed. */
    private suspend fun setPanel(t: (PanelState) -> PanelState) {
        val next = t(panel)
        if (next == panel) return
        panel = next
        postPanel()
    }

    private fun postPanel() {
        val p = panel
        if (p == postedPanel) return
        postedPanel = p
        mainHandler.post { overlay?.render(p) }
    }

    /**
     * A message replaces the sticky status line. Identical messages are ignored. A DIFFERENT message of equal or lower
     * severity waits for a 1.5 s cooldown (the latest one wins), so rapidly changing conditions cannot make the
     * panel flicker; an escalation to a higher severity applies immediately.
     */
    private suspend fun say(text: String, sev: Severity = Severity.INFO) {
        if (text == panel.message && sev == panel.severity) { pendingMessage = null; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastMessageAt < MESSAGE_COOLDOWN_MS && sev.ordinal <= panel.severity.ordinal) {
            pendingMessage = text to sev
            return
        }
        pendingMessage = null
        lastMessageAt = now
        setPanel { it.copy(message = text, severity = sev) }
    }

    private suspend fun flushPendingMessage() {
        val pm = pendingMessage ?: return
        if (SystemClock.elapsedRealtime() - lastMessageAt >= MESSAGE_COOLDOWN_MS) { pendingMessage = null; say(pm.first, pm.second) }
    }

    private suspend fun publishRecognition(r: RecognitionResult) {
        val level = when {
            r.geometry == null -> Level.SEARCHING
            r.ok -> Level.GOOD
            r.boardOk && r.uncertainSlots.isNotEmpty() -> Level.UNCERTAIN
            r.boardOk && !r.piecesOk -> Level.SEARCHING // board fine, tray empty: waiting for a new set
            else -> Level.NEEDS_CORRECTION
        }
        val piecesText = when {
            r.pieces == null || r.pieces?.pieces.isNullOrEmpty() -> "none"
            r.uncertainSlots.isNotEmpty() -> "slot " + r.uncertainSlots.joinToString { (it + 1).toString() } + " uncertain"
            else -> "all ${r.pieces?.pieces?.size} ok"
        }
        setPanel { it.copy(level = level, gridConfidence = if (r.geometry != null) r.locateConfidence else null, boardConfidence = r.board?.confidence, piecesText = piecesText) }
        val rows = r.board?.let { Board.ofBits(it.bits).toRows() } ?: emptyList()
        val pieceLines = r.pieces?.pieces?.map { dp -> "slot ${dp.slot}: " + dp.piece.cells.joinToString(" ") { "(${it.x},${it.y})" } + "  conf %.2f".format(dp.confidence) } ?: emptyList()
        val g = r.geometry
        val geo = if (g == null) "not found" else "left=%.1f top=%.1f cell=%.1f (%s, grid %.2f)".format(g.left, g.top, g.cellSize, r.source.name.lowercase(), r.locateConfidence)
        AssistantState.update {
            it.copy(
                boardRows = rows, pieceLines = pieceLines, geometryText = geo, recognitionConfidence = r.confidence,
                needsCorrection = r.needsCorrection, lowConfidenceCells = r.board?.weakCells?.size ?: 0, learnedTheme = pipeline.emptyLook != null,
                stateHash = r.board?.let { b -> java.lang.Long.toHexString(b.bits) } ?: "-", notes = (listOf(r.summary) + r.notes).joinToString("; "),
            )
        }
    }

    // ---- main loop -------------------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            delay(if (scanMode == ScanMode.SCANNING) scanDelayMs else WATCH_MS)
            flushPendingMessage()
            if (assistPaused || editing) continue
            try {
                if (scanMode == ScanMode.SCANNING) cleanScan() else { watch(); autoplayStep() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AssistantState.update { it.copy(lastError = "${e.javaClass.simpleName}: ${e.message}") }
                scanMode = ScanMode.SCANNING
                scanDelayMs = 1500L
                if (run == RunState.RUNNING) attention("Autoplay stopped after an internal error: ${e.message}")
            }
        }
    }

    private fun gameRegion(): FloatRect? {
        val g = verified?.geometry ?: lastResult?.geometry ?: return null
        val s = g.cellSize
        return FloatRect(g.left, g.top, g.right, g.bottom + 6.2f * s)
    }

    /** Hide our highlight window (and the panel only if it covers the game), grab an untouched frame, restore, recognize. */
    private suspend fun cleanScan() {
        val cap = capturer ?: return
        val ov = overlay ?: return
        val region = gameRegion()
        cap.invalidate()
        withContext(Dispatchers.Main) { ov.setCaptureHidden(true, region) }
        var frame: PixelFrame? = null
        delay(HIDE_SETTLE_MS)
        for (i in 0 until 6) {
            frame = cap.latestFrame()
            if (frame != null) break
            delay(100)
        }
        withContext(Dispatchers.Main) { ov.setCaptureHidden(false, region) }
        if (frame == null) { say("Waiting for the first screen frame..."); return }
        pipeline.emptyLook = settings.emptyLook
        val manual = settings.manualGeometry(frame.width, frame.height)
        val result = withContext(Dispatchers.Default) { pipeline.recognize(frame, manual) }
        lastResult = result
        lastFrame = frame
        publishRecognition(result)
        when (val st = tracker.offer(result)) {
            is Stability.Verified -> { lostSince = 0L; onVerified(st.state) }
            is Stability.Pending -> { scanDelayMs = 450L }
            is Stability.Rejected -> {
                scanDelayMs = minOf(4000L, maxOf(900L, (scanDelayMs * 1.5).toLong()))
                if (run == RunState.RUNNING) {
                    val now = SystemClock.elapsedRealtime()
                    if (lostSince == 0L) lostSince = now
                    if (now - lostSince > RECOVERY_WINDOW_MS) attention("Autoplay stopped: the screen could not be read for ${RECOVERY_WINDOW_MS / 1000} s. Check it, tap Fix if needed, then Resume.")
                    else say("Autoplay waiting: recognition is temporarily uncertain...", Severity.WARNING)
                } else {
                    clearHighlightsOnMain()
                    explainRejection(result)
                }
            }
        }
    }

    private suspend fun explainRejection(r: RecognitionResult) {
        when {
            r.geometry == null -> say("Board grid not found. Open the game and keep the whole board visible, or use Cal to align the grid.", Severity.WARNING)
            r.board == null -> say("The grid looks weak (${"%.2f".format(r.locateConfidence)}). Use Cal to align the board.", Severity.WARNING)
            !r.boardOk -> say("Board uncertain: ${r.board?.weakCells?.size ?: 0} doubtful cell(s). Tap Fix to correct them. No moves are shown until then.", Severity.ERROR)
            r.uncertainSlots.isNotEmpty() -> say("Board is good. Piece ${r.uncertainSlots.joinToString { (it + 1).toString() }} is uncertain - tap Fix to confirm just that piece.", Severity.WARNING)
            else -> say("No pieces visible yet (waiting for a new set?).", Severity.INFO)
        }
    }

    private suspend fun watch() {
        val cap = capturer ?: return
        val ov = overlay ?: return
        val state = verified
        if (state == null) { scanMode = ScanMode.SCANNING; return }
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
                if (pending == null) invalidateRecommendations("Board changed - rescanning...")
            }
            stableTicks = 0
        } else if (changeStart != 0L) {
            stableTicks++
        }
        if (changeStart != 0L && (stableTicks >= 2 || now - changeStart > 3000L)) {
            changeStart = 0L; stableTicks = 0
            tracker.reset()
            scanMode = ScanMode.SCANNING
            scanDelayMs = 200L
        }
    }

    private suspend fun invalidateRecommendations(reason: String) {
        solveToken?.cancel(); solveJob?.cancel()
        recs = emptyList()
        clearHighlightsOnMain()
        say(reason)
        AssistantState.update { it.copy(recommendationCount = 0, lastTransition = reason) }
    }

    private suspend fun onVerified(state: VerifiedState) {
        scanMode = ScanMode.WATCHING
        val previous = verified
        verified = state
        changeStart = 0L; stableTicks = 0
        baseline = null
        pending?.let { verifyMove(it, state) }
        pending = null
        if (run == RunState.NEEDS_ATTENTION || previous == null) { /* keep status as set */ }
        AssistantState.update { it.copy(lastTransition = "state verified; searching") }
        say("Board verified. Searching moves...")
        startSolve(state)
    }

    // ---- solving ---------------------------------------------------------------------------------------------------

    private fun startSolve(state: VerifiedState) {
        solveToken?.cancel(); solveJob?.cancel()
        val token = CancellationToken()
        solveToken = token
        val board = Board.ofBits(state.boardBits)
        val budget = SearchBudget(maxNodes = 200_000L, maxMillis = settings.searchMillis.toLong())
        solveJob = scope.launch(Dispatchers.Default) {
            val result = solver.search(board, state.pieces, budget, token)
            if (token.isCancelled) return@launch
            withContext(logic) { if (solveToken === token) onSolved(result) }
        }
    }

    private suspend fun onSolved(result: SearchResult) {
        recs = Recommender.from(result, 12)
        recIndex = 0
        AssistantState.update { it.copy(searchMillis = result.elapsedMillis, nodes = result.nodesExplored, searchCompleted = result.completed, recommendationCount = recs.size) }
        if (recs.isEmpty()) {
            clearHighlightsOnMain()
            say("No legal placement for these pieces. The game may be over.", Severity.WARNING)
            if (run == RunState.RUNNING) attention("Autoplay stopped: no legal move (game over?).")
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
        val text = "Move ${recIndex + 1}/${recs.size}: ${slotName(slot)} piece -> column ${rec.move.x + 1}, row ${rec.move.y + 1}" +
            (if (rec.linesCleared > 0) " (clears ${rec.linesCleared})" else "")
        val opacity = settings.opacityPercent
        val seq = settings.showSequence
        withContext(Dispatchers.Main) { if (highlightsEnabled) ov.showRecommendation(rec, state.geometry, opacity, seq) else ov.clearHighlights() }
        say(text)
    }

    // ---- autoplay --------------------------------------------------------------------------------------------------

    private suspend fun attention(reason: String) {
        run = RunState.NEEDS_ATTENTION
        pending = null
        setPanel { it.copy(run = RunState.NEEDS_ATTENTION) }
        say(reason, Severity.ERROR)
    }

    private suspend fun autoplayStep() {
        if (mode != Mode.AUTOPLAY || run != RunState.RUNNING || emergency) return
        val state = verified ?: return
        if (changeStart != 0L || pending != null || recs.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMoveAt < MIN_MOVE_GAP_MS) return
        val res = lastResult
        if (res != null && !res.ok && !userConfirmed) { attention("Autoplay stopped: recognition is not confident. Tap Fix, then Resume."); return }
        val svc = AutoplayAccessibilityService.instance
        if (svc == null) { attention("Autoplay stopped: the accessibility input service is off. Enable it in Android Settings > Accessibility."); return }
        val front = AutoplayAccessibilityService.foregroundPackage
        if (targetPackage != null && front != null && front != targetPackage) { attention("Autoplay paused: the game is no longer in front ($front)."); return }
        val rec = recs.first()
        val grab = state.grabs.getOrNull(rec.move.pieceIndex)
        if (grab == null) { say("Autoplay needs the piece positions: tap Scan once.", Severity.WARNING); scanMode = ScanMode.SCANNING; tracker.reset(); scanDelayMs = 200L; userConfirmed = false; return }
        val expected = AutoplayPlanner.expectedBoard(rules, Board.ofBits(state.boardBits), rec.move)
        if (expected == null) { attention("Autoplay stopped: the chosen move is not legal on the recognized board."); return }
        val plan = AutoplayPlanner.plan(state.geometry, rec.move, grab, settings.dropOffsetX, settings.dropOffsetY)
        if (!planInsideScreen(plan)) { attention("Autoplay stopped: the drag would leave the screen. Recalibrate the board (Cal)."); return }
        val clear = withContext(Dispatchers.Main) { overlay?.panelOverlaps(pathBounds(plan)) != true }
        if (!clear) { attention("Autoplay stopped: the control panel is in the way of the drag. Move the panel off the board/tray, then Resume."); return }

        pending = PendingMove(rec.move, rec.move.piece, Board.ofBits(state.boardBits), expected, state.geometry.cellSize)
        solveToken?.cancel()
        lastMoveAt = now
        say("Autoplay: moving piece ${rec.move.pieceIndex + 1} to column ${rec.move.x + 1}, row ${rec.move.y + 1}...")
        withContext(Dispatchers.Main) { overlay?.clearHighlights() }
        if (emergency) { pending = null; return }
        val ok = dragAndAwait(plan)
        if (!ok) {
            pending = null
            notAccepted++
            if (notAccepted >= 3) attention("Autoplay stopped: the gesture was rejected 3 times. Check that the accessibility service is enabled.")
            else say("The drag was not performed; retrying.", Severity.WARNING)
            return
        }
        delay(SETTLE_AFTER_MOVE_MS) // let the game finish its animation before reading it again
        tracker.reset()
        scanMode = ScanMode.SCANNING
        scanDelayMs = 300L
    }

    private var userConfirmed = false

    private fun planInsideScreen(p: DragPlan): Boolean {
        val (w, h, _) = displayInfo()
        return p.fromX in 0f..w.toFloat() && p.toX in 0f..w.toFloat() && p.fromY in 0f..h.toFloat() && p.toY in 0f..h.toFloat()
    }

    private fun pathBounds(p: DragPlan): FloatRect =
        FloatRect(minOf(p.fromX, p.toX) - 20f, minOf(p.fromY, p.toY) - 20f, maxOf(p.fromX, p.toX) + 20f, maxOf(p.fromY, p.toY) + 20f)

    private suspend fun dragAndAwait(p: DragPlan): Boolean {
        val svc = AutoplayAccessibilityService.instance ?: return false
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                mainHandler.post {
                    svc.drag(p.fromX, p.fromY, p.toX, p.toY, DRAG_MS) { ok -> if (cont.isActive) cont.resume(ok) }
                }
            }
        } ?: false
    }

    /** Compare the freshly verified board with the rules engine's prediction for the move just made. */
    private suspend fun verifyMove(pm: PendingMove, now: VerifiedState) {
        val observed = Board.ofBits(now.boardBits)
        if (observed == pm.expected) {
            moveCount++; mismatches = 0; notAccepted = 0
            setPanel { it.copy(progress = "Moves verified: $moveCount") }
            return
        }
        if (run != RunState.RUNNING) return
        val landed = AutoplayPlanner.explainPlacement(rules, pm.before, pm.piece, observed)
        when {
            landed != null -> {
                val dx = landed.x - pm.move.x
                val dy = landed.y - pm.move.y
                settings.dropOffsetX -= dx * pm.cell
                settings.dropOffsetY -= dy * pm.cell
                mismatches++
                if (mismatches >= 3) attention("Autoplay stopped: pieces keep landing off target ($dx, $dy cells). Adjust the board calibration, then Resume.")
                else say("The piece landed ($dx, $dy) cells off. Drop offset adjusted; continuing from the real board.", Severity.WARNING)
            }
            observed == pm.before -> {
                notAccepted++
                if (notAccepted >= 3) attention("Autoplay stopped: the game did not accept the drag 3 times. Check the accessibility service and the piece positions.")
                else say("The game did not accept that drag (board unchanged). Retrying.", Severity.WARNING)
            }
            else -> attention("Autoplay stopped: the board changed in a way the move does not explain. Tap Fix to check it, then Resume.")
        }
    }

    // ---- overlay callbacks (main thread) ---------------------------------------------------------------------------

    override fun onPrev() { scope.launch { if (recs.isNotEmpty()) { recIndex = (recIndex - 1 + recs.size) % recs.size; showCurrent(); rebaseline() } } }
    override fun onNext() { scope.launch { if (recs.isNotEmpty()) { recIndex = (recIndex + 1) % recs.size; showCurrent(); rebaseline() } } }

    override fun onScan() {
        scope.launch {
            solveToken?.cancel(); tracker.reset(); scanMode = ScanMode.SCANNING; scanDelayMs = 100L
            AssistantState.update { it.copy(lastTransition = "manual rescan requested") }
        }
    }

    override fun onToggleMode() {
        scope.launch {
            if (mode == Mode.ASSIST) {
                mode = Mode.AUTOPLAY
                run = RunState.STOPPED
                if (!AutoplayAccessibilityService.isEnabled) {
                    say("Autoplay needs the accessibility input service. Android Settings opened: enable \"Block Blast Assistant autoplay input\", return to the game, then press Start.", Severity.WARNING)
                    mainHandler.post {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } else say("Autoplay mode. Press Start when the board is recognised (Good). E-STOP stops it at any time.", Severity.WARNING)
            } else {
                mode = Mode.ASSIST; run = RunState.STOPPED; pending = null
                say("Assist mode: suggestions only, nothing is done for you.")
            }
            setPanel { it.copy(mode = mode, run = run) }
        }
    }

    override fun onRunButton() {
        scope.launch {
            if (mode == Mode.ASSIST) {
                assistPaused = !assistPaused
                if (assistPaused) { solveToken?.cancel(); clearHighlightsOnMain(); say("Paused.") }
                else { tracker.reset(); scanMode = ScanMode.SCANNING; scanDelayMs = 100L; say("Resumed.") }
                setPanel { it.copy(assistPaused = assistPaused) }
                return@launch
            }
            when (run) {
                RunState.RUNNING -> { run = RunState.PAUSED; pending = null; say("Autoplay paused.") }
                else -> {
                    if (!AutoplayAccessibilityService.isEnabled) { say("Enable the accessibility input service first (Android Settings > Accessibility).", Severity.ERROR); return@launch }
                    val front = AutoplayAccessibilityService.foregroundPackage
                    if (front == null) { say("Open the game once so its app can be identified, then press Start.", Severity.WARNING); return@launch }
                    emergency = false
                    targetPackage = front
                    moveCount = 0; mismatches = 0; notAccepted = 0; lostSince = 0L
                    run = RunState.RUNNING
                    scanMode = ScanMode.SCANNING; tracker.reset(); scanDelayMs = 100L
                    say("Autoplay running on $front. It stops by itself if recognition is doubtful.", Severity.WARNING)
                }
            }
            setPanel { it.copy(run = run) }
        }
    }

    override fun onEmergencyStop() {
        emergency = true
        scope.launch {
            run = RunState.STOPPED; mode = Mode.ASSIST; pending = null
            say("EMERGENCY STOP: Autoplay is off (Assist mode).", Severity.ERROR)
            setPanel { it.copy(mode = Mode.ASSIST, run = RunState.STOPPED) }
            emergency = false
        }
    }

    override fun onQuit() { stopSelf() }

    override fun onToggleHighlights(): Boolean {
        highlightsEnabled = !highlightsEnabled
        overlay?.setHighlightsEnabled(highlightsEnabled)
        scope.launch { setPanel { it.copy(highlightsOn = highlightsEnabled) } }
        return highlightsEnabled
    }

    override fun onUndo() {
        scope.launch {
            val u = undoState
            if (u == null) { say("Nothing to undo."); return@launch }
            undoState = null
            pipeline.emptyLook = u.second; settings.emptyLook = u.second
            if (u.first != null) { verified = u.first; scanMode = ScanMode.WATCHING; startSolve(u.first!!); say("Last correction undone.") }
            else { verified = null; scanMode = ScanMode.SCANNING; tracker.reset(); say("Last correction undone; rescanning.") }
        }
    }

    override fun onCalibrate() { editing = true; overlay?.startCalibration(verified?.geometry ?: lastResult?.geometry) }

    override fun onCalibrationDone(geometry: BoardGeometry) {
        val (w, h, _) = displayInfo()
        settings.setManualGeometry(geometry, w, h)
        editing = false
        pipeline.clearCache()
        scope.launch { tracker.reset(); scanMode = ScanMode.SCANNING; scanDelayMs = 100L; say("Manual calibration saved.") }
    }

    override fun onAutoCalibrate() {
        settings.clearManualGeometry(); pipeline.clearCache()
        scope.launch { tracker.reset(); scanMode = ScanMode.SCANNING; scanDelayMs = 100L; say("Automatic board detection on.") }
    }

    override fun onFix() {
        scope.launch {
            val res = lastResult
            val frame = lastFrame
            val g = res?.geometry ?: verified?.geometry
            if (res == null || g == null || frame == null) { say("Cannot fix yet. Use Cal to align the board first.", Severity.WARNING); return@launch }
            val slotPieces = arrayOfNulls<Piece>(3)
            res.pieces?.pieces?.forEach { slotPieces[it.slot] = it.piece }
            editing = true
            withContext(Dispatchers.Main) { overlay?.startCorrection(g, res.board?.bits ?: 0L, slotPieces.toList(), res.uncertainSlots) }
        }
    }

    override fun onCorrectionCancelled() { editing = false }

    override fun onCorrectionDone(bits: Long, slotPieces: List<Piece?>) {
        scope.launch {
            editing = false
            val g = lastResult?.geometry ?: verified?.geometry ?: return@launch
            val pieces = ArrayList<Piece>()
            val slots = ArrayList<Int>()
            slotPieces.forEachIndexed { i, p -> if (p != null) { pieces.add(p); slots.add(i) } }
            undoState = verified to pipeline.emptyLook
            lastFrame?.let { pipeline.learn(it, g, bits); settings.emptyLook = pipeline.emptyLook }
            if (pieces.isEmpty()) { say("No pieces entered. Add at least one piece in Fix.", Severity.WARNING); return@launch }
            tracker.reset()
            userConfirmed = true
            AssistantState.update { it.copy(needsCorrection = false, learnedTheme = pipeline.emptyLook != null, lastTransition = "board and pieces confirmed by the user") }
            setPanel { it.copy(level = Level.GOOD) }
            onVerified(VerifiedState(bits, g, pieces, slots))
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun rebaseline() { baseline = null; baselineDueAt = SystemClock.elapsedRealtime() + 400L }

    private suspend fun clearHighlightsOnMain() {
        val ov = overlay ?: return
        withContext(Dispatchers.Main) { ov.clearHighlights() }
    }

    private suspend fun restartCapture() {
        val cap = capturer ?: return
        val (w, h, _) = displayInfo()
        cap.resize(w, h)
        withContext(Dispatchers.Main) { overlay?.onScreenChanged(); overlay?.clearHighlights() }
        pipeline.clearCache(); tracker.reset()
        verified = null; recs = emptyList(); scanMode = ScanMode.SCANNING; scanDelayMs = 400L
        if (run == RunState.RUNNING) attention("Autoplay stopped: the screen size changed. Recalibrate if needed, then Resume.")
        AssistantState.update { it.copy(lastTransition = "screen size changed -> recalibrating") }
    }

    private fun slotName(slot: Int): String = when (slot) { 0 -> "left"; 1 -> "middle"; else -> "right" }

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
        fun action(label: String, act: String, req: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), label,
            PendingIntent.getService(this, req, Intent(this, AssistantService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Block Blast Assistant")
            .setContentText("Overlay running (Assist mode unless you switch to Autoplay)")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(action("E-STOP autoplay", ACTION_ESTOP, 1))
            .addAction(action("Quit", ACTION_STOP, 0))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.blockblast.assistant.START"
        const val ACTION_STOP = "com.blockblast.assistant.STOP"
        const val ACTION_ESTOP = "com.blockblast.assistant.ESTOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "assistant"
        private const val NOTIF_ID = 1
        private const val WATCH_MS = 350L
        private const val HIDE_SETTLE_MS = 220L
        private const val CHANGE_MIN = 4
        private const val MESSAGE_COOLDOWN_MS = 1500L
        private const val RECOVERY_WINDOW_MS = 12_000L
        private const val MIN_MOVE_GAP_MS = 700L
        private const val SETTLE_AFTER_MOVE_MS = 1300L
        private const val DRAG_MS = 550L
    }
}
