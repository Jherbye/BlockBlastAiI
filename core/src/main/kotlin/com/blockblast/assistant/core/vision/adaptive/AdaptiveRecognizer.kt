package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.FloatRect
import com.blockblast.assistant.core.vision.GeometrySource
import com.blockblast.assistant.core.vision.PixelFrame
import kotlin.math.min

/**
 * Theme-independent recognition: grid geometry -> tray pieces -> cell occupancy (unsupervised, or model-driven once a
 * [ThemeModel] has been learned) -> temporal fusion -> confidence gating.
 *
 * Contract: [AdaptiveResult.status] is READY only when every cell, every piece and the geometry are above the confidence
 * threshold and the board is physically plausible. Everything else is reported as needing confirmation or correction;
 * this class never silently guesses. Frames are analysed in memory only; the last frame is retained in memory so that a
 * user confirmation or correction can teach the theme model, and is never written anywhere.
 */
class AdaptiveRecognizer(
    private val gridDetector: GridDetector = GridDetector(),
    private val analyzer: CellAnalyzer = CellAnalyzer(),
    private val trayReader: TrayReader = TrayReader(),
    var minGeometryConfidence: Double = 0.40,
    var minConfidence: Double = AdaptiveResult.LOW,
) {
    val model = ThemeModel()
    private val temporal = TemporalFusion()

    private var cachedGeometry: BoardGeometry? = null
    private var lastFrame: PixelFrame? = null
    private var lastManual: BoardGeometry? = null
    private var lastVerified: Pair<Board, List<Piece>>? = null

    /** Forget everything about the current theme and screen (rotation, new game session, "Auto" pressed). */
    fun resetTheme() {
        model.reset(); temporal.reset(); cachedGeometry = null; lastVerified = null
    }

    fun clearGeometryCache() { cachedGeometry = null; temporal.reset() }

    fun recognize(frame: PixelFrame, manual: BoardGeometry? = null): AdaptiveResult {
        lastFrame = frame
        lastManual = manual
        return analyse(frame, manual, null)
    }

    private fun analyse(frame: PixelFrame, manual: BoardGeometry?, user: UserState?): AdaptiveResult {
        val notes = ArrayList<String>()
        val img = LabImage.of(frame)

        // geometry
        val geometry: BoardGeometry
        val source: GeometrySource
        var geoConf = 1.0
        if (manual != null) {
            geometry = manual; source = GeometrySource.MANUAL
        } else {
            val ev = gridDetector.detect(img)
            if (ev == null || ev.confidence < minGeometryConfidence) {
                notes.add(if (ev == null) "board grid not found" else "board grid confidence ${"%.2f".format(ev.confidence)} too low")
                cachedGeometry = null
                return AdaptiveResult(RecognitionStatus.NO_BOARD, null, GeometrySource.NONE, ev?.confidence ?: 0.0, null, emptyList(), null, 0.0, notes + (ev?.notes ?: emptyList()))
            }
            val cached = cachedGeometry
            geometry = if (cached != null && cached.isSameAs(ev.geometry, 0.1f)) cached else ev.geometry
            cachedGeometry = geometry
            source = GeometrySource.AUTO; geoConf = ev.confidence
            notes.addAll(ev.notes)
        }
        if (geometry.left < -2f || geometry.top < -2f || geometry.right > frame.width + 2f || geometry.bottom > frame.height + 2f) {
            notes.add("board geometry lies outside the frame")
            return AdaptiveResult(RecognitionStatus.NO_BOARD, geometry, source, geoConf, null, emptyList(), null, 0.0, notes)
        }

        // tray, then board (the tray colours feed the board's orientation vote)
        val d = img.scale.toDouble()
        val tray = trayReader.read(img, geometry, model.trayRatio, geometry.left / d, (geometry.right - geometry.left) / d)
        val features = CellFeatures.extract(img, geometry)
        lastFeatures = features; lastTray = tray; lastGeometry = geometry
        val raw = analyzer.analyze(features, if (model.isReady) model else null, tray.cellColours)
        if (raw.modelRejected) { model.reset(); temporal.reset(); lastVerified = null }
        var cells = temporal.apply(geometry, features, raw)
        notes.addAll(cells.notes.filter { it !in notes })
        notes.addAll(tray.notes)
        lastCells = cells

        var pieces = tray.pieces
        var corrected = false
        if (user != null) {
            cells = overrideCells(cells, user.bits)
            pieces = overridePieces(tray.pieces, user.pieces)
            corrected = true
        }

        // board plausibility: a persistent full line cannot exist
        var plausible = true
        val bits = cells.bits
        for (k in 0 until Board.SIZE) {
            if ((bits and Board.ROW_MASKS[k]) == Board.ROW_MASKS[k] || (bits and Board.COL_MASKS[k]) == Board.COL_MASKS[k]) plausible = false
        }
        if (!plausible && !corrected) notes.add("board shows a complete row/column, which cannot persist in the game")

        // temporal transition sanity (informational factor)
        var transitionFactor = 1.0
        lastVerified?.let { (pb, pp) ->
            if (!corrected && pb.bits != bits && !TransitionValidator.explains(pb, pp, Board.ofBits(bits))) {
                transitionFactor = 0.85
                notes.add("board changed in a way the visible pieces cannot explain (new game, or a misread)")
            }
        }

        val pieceMin = pieces.minOfOrNull { it.confidence } ?: 1.0
        val orient = if (cells.source == OccupancySource.UNSUPERVISED) cells.orientationConfidence else 1.0
        val confidence = (minOf(cells.minConfidence, pieceMin, orient, if (source == GeometrySource.AUTO) maxOf(geoConf, 0.5) else 1.0) * transitionFactor)
        val status = when {
            corrected -> if (pieces.isEmpty()) RecognitionStatus.NO_PIECES else RecognitionStatus.READY
            !plausible -> RecognitionStatus.NEEDS_CORRECTION
            cells.minConfidence < minConfidence || cells.conflict || pieceMin < minConfidence -> {
                if (cells.source == OccupancySource.UNSUPERVISED && cells.orientationConfidence < minConfidence && cells.minConfidence >= minConfidence && pieceMin >= minConfidence)
                    RecognitionStatus.NEEDS_CONFIRMATION else RecognitionStatus.NEEDS_CORRECTION
            }
            cells.source == OccupancySource.UNSUPERVISED && cells.orientationConfidence < minConfidence -> RecognitionStatus.NEEDS_CONFIRMATION
            pieces.isEmpty() -> RecognitionStatus.NO_PIECES
            confidence < minConfidence -> RecognitionStatus.NEEDS_CORRECTION
            else -> RecognitionStatus.READY
        }
        if (status == RecognitionStatus.NEEDS_CORRECTION) {
            val lc = cells.confidence.indices.count { cells.confidence[it] < minConfidence }
            if (lc > 0) notes.add("$lc board cells are uncertain")
            pieces.filter { it.confidence < minConfidence }.forEach { notes.add("piece in slot ${it.slot} is uncertain") }
        }
        return AdaptiveResult(status, geometry, source, geoConf, cells, pieces, tray, confidence, notes, corrected, model.isReady)
    }

    private fun overrideCells(c: BoardCells, bits: Long) = BoardCells(
        BooleanArray(64) { (bits ushr it) and 1L == 1L }, DoubleArray(64) { 1.0 }, c.source, 1.0, c.signals, false, false, c.notes + "board set by the user", false,
    )

    private fun overridePieces(detected: List<TrayPiece>, user: List<Piece?>): List<TrayPiece> {
        val out = ArrayList<TrayPiece>()
        for (slot in 0 until 3) {
            val p = user.getOrNull(slot) ?: continue
            val det = detected.firstOrNull { it.slot == slot }
            out.add(TrayPiece(slot, p, 1.0, List(p.size) { 1.0 }, det?.bounds ?: FloatRect(0f, 0f, 0f, 0f), listOf("set by the user")))
        }
        return out
    }

    // ---- learning and user input -----------------------------------------------------------------------------------

    private var lastFeatures: CellFeatures? = null
    private var lastCells: BoardCells? = null
    private var lastTray: TrayReading? = null
    private var lastGeometry: BoardGeometry? = null

    /**
     * Call when the state tracker has verified [r] (identical READY readings in a row). Confident readings teach the
     * theme model, so later frames are judged against what this theme's empty cells look like.
     */
    fun onVerified(r: AdaptiveResult) {
        val f = lastFeatures ?: return
        val c = r.cells ?: return
        val b = r.board ?: return
        if (!r.ok) return
        val strong = c.minConfidence >= 0.75 && (c.source == OccupancySource.MODEL || c.orientationConfidence >= 0.8)
        if (r.userCorrected || strong) {
            model.learn(f, c.bits)
            learnTrayRatio(r.pieces, r.geometry)
        }
        lastVerified = b to r.pieceList
    }

    private fun learnTrayRatio(pieces: List<TrayPiece>, g: BoardGeometry?) {
        if (g == null) return
        val ratios = pieces.filter { it.confidence >= 0.75 && it.bounds.right > it.bounds.left }
            .map { ((it.bounds.right - it.bounds.left) / it.piece.width) / g.cellSize.toDouble() }
        if (ratios.isNotEmpty()) model.trayRatio = ratios.average()
    }

    /** The user says the current reading is right: learn the theme from it and re-evaluate the same frame. */
    fun confirm(): AdaptiveResult? {
        val f = lastFeatures ?: return null
        val c = lastCells ?: return null
        val frame = lastFrame ?: return null
        model.learn(f, c.bits)
        learnTrayRatio(lastTray?.pieces ?: emptyList(), lastGeometry)
        return analyse(frame, lastManual, UserState(c.bits, MutableList<Piece?>(3) { null }.also { l -> lastTray?.pieces?.forEach { l[it.slot] = it.piece } }))
    }

    /**
     * The user corrected the board and/or the pieces. The corrected state is trusted at once, and the theme model learns
     * from it so the same theme is recognised automatically afterwards.
     */
    fun correct(bits: Long, pieces: List<Piece?>): AdaptiveResult? {
        val f = lastFeatures ?: return null
        val frame = lastFrame ?: return null
        model.learn(f, bits)
        val g = lastGeometry
        if (g != null) {
            val ratios = lastTray?.pieces?.mapNotNull { det ->
                val p = pieces.getOrNull(det.slot) ?: return@mapNotNull null
                if (det.bounds.right <= det.bounds.left) null else ((det.bounds.right - det.bounds.left) / p.width) / g.cellSize.toDouble()
            } ?: emptyList()
            if (ratios.isNotEmpty()) model.trayRatio = ratios.average()
        }
        return analyse(frame, lastManual, UserState(bits, pieces))
    }

    /** Current board reading and tray geometry of the last frame, for the correction editor. */
    fun lastReading(): Triple<BoardCells?, TrayReading?, BoardGeometry?> = Triple(lastCells, lastTray, lastGeometry)
}
