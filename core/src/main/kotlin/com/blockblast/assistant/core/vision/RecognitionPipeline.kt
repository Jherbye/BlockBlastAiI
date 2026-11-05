package com.blockblast.assistant.core.vision

import com.blockblast.assistant.core.model.Piece

enum class GeometrySource { MANUAL, AUTO, NONE }

data class RecognitionResult(
    val geometry: BoardGeometry?,
    val source: GeometrySource,
    val locateConfidence: Double,
    val board: BoardReading?,
    val pieces: PiecesReading?,
    val confidence: Double,
    /** True only when geometry, every cell and every piece were read with enough confidence to show moves. */
    val ok: Boolean,
    /** A reading exists but is not trustworthy: the UI should offer manual correction instead of showing moves. */
    val needsCorrection: Boolean,
    val notes: List<String>,
) {
    val pieceList: List<Piece> get() = pieces?.pieces?.map { it.piece } ?: emptyList()
    val slots: List<Int> get() = pieces?.pieces?.map { it.slot } ?: emptyList()
}

/**
 * Board location -> pieces -> cell occupancy, with a cached automatic geometry, an optional manual calibration, and an
 * optional learned empty look (from earlier user-confirmed boards).
 *
 * Acceptance rule (never a silent guess): auto geometry confidence >= 0.5, board confidence >= 0.75 (second-lowest
 * cell), piece confidence >= 0.5 and at least one piece. Anything else is reported with needsCorrection = true.
 */
class RecognitionPipeline(
    private val locator: BoardLocator = BoardLocator(),
    private val reader: CellReader = CellReader(),
    private val extractor: PieceExtractor = PieceExtractor(),
) {
    /** Learned empty look from confirmed boards (6-vector), or null. Cleared with [clearLearning]. */
    var emptyLook: DoubleArray? = null
    private var cached: LocateResult? = null

    fun clearCache() {
        cached = null
    }

    fun clearLearning() {
        emptyLook = null
    }

    /** Learn the theme's empty look from a board the user confirmed. */
    fun learn(frame: PixelFrame, geometry: BoardGeometry, confirmedBits: Long) {
        reader.learnEmptyLook(frame, geometry, confirmedBits)?.let { emptyLook = it }
    }

    fun recognize(frame: PixelFrame, manual: BoardGeometry? = null): RecognitionResult {
        val notes = ArrayList<String>()
        var attempt = 0
        while (true) {
            attempt++
            val source: GeometrySource
            val geometry: BoardGeometry
            var locConf = 1.0
            val fromCache: Boolean
            if (manual != null) {
                geometry = manual
                source = GeometrySource.MANUAL
                fromCache = false
            } else {
                var loc = cached
                fromCache = loc != null
                if (loc == null) {
                    loc = locator.locate(frame)
                    if (loc == null) {
                        notes.add("board grid not found")
                        return RecognitionResult(null, GeometrySource.NONE, 0.0, null, null, 0.0, false, false, notes)
                    }
                    if (loc.confidence < MIN_LOCATE) {
                        notes.add("grid found but weak (confidence ${"%.2f".format(loc.confidence)}): calibrate the board")
                        return RecognitionResult(loc.geometry, GeometrySource.AUTO, loc.confidence, null, null, 0.0, false, false, notes)
                    }
                    cached = loc
                }
                geometry = loc.geometry
                locConf = loc.confidence
                source = GeometrySource.AUTO
            }
            if (geometry.cellSize < 20f || geometry.left < -2f || geometry.top < -2f ||
                geometry.right > frame.width + 2f || geometry.bottom > frame.height + 2f
            ) {
                notes.add("board geometry lies outside the frame")
                cached = null
                return RecognitionResult(geometry, source, locConf, null, null, 0.0, false, false, notes)
            }
            val pieces = extractor.extract(frame, geometry)
            val palette = pieces?.pieces?.map { it.rgb } ?: emptyList()
            val board = reader.read(frame, geometry, palette, emptyLook)
            if (fromCache && attempt == 1 && board.confidence < 0.4) {
                cached = null
                notes.add("cached geometry looked wrong; relocating")
                continue
            }
            if (pieces == null) notes.add("tray area not readable")
            if (pieces != null) notes.addAll(pieces.notes)
            notes.addAll(board.notes)
            val pieceConf = pieces?.confidence ?: 0.0
            val conf = minOf(board.confidence, pieceConf, locConf)
            val hasPieces = pieces != null && pieces.pieces.isNotEmpty()
            val ok = hasPieces && locConf >= MIN_LOCATE && board.confidence >= MIN_BOARD && pieceConf >= MIN_PIECES
            if (hasPieces && board.confidence < MIN_BOARD) notes.add("board reading confidence ${"%.2f".format(board.confidence)} is too low")
            if (pieces != null && pieces.pieces.isEmpty()) notes.add("no pieces visible (waiting for a new set?)")
            val needsCorrection = !ok && (hasPieces || board.confidence < MIN_BOARD)
            return RecognitionResult(geometry, source, locConf, board, pieces, conf, ok, needsCorrection, notes)
        }
    }

    companion object {
        const val MIN_LOCATE = 0.5
        const val MIN_BOARD = 0.75
        const val MIN_PIECES = 0.5
    }
}
