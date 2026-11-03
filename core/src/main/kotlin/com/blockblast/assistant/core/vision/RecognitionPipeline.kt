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
    /** True only when the board and at least one piece were read with confidence above the threshold. */
    val ok: Boolean,
    val notes: List<String>,
) {
    val pieceList: List<Piece> get() = pieces?.pieces?.map { it.piece } ?: emptyList()
    val slots: List<Int> get() = pieces?.pieces?.map { it.slot } ?: emptyList()
}

/** Board location -> occupancy -> pieces, with a cached automatic geometry and an optional manual override. */
class RecognitionPipeline(
    private val locator: BoardLocator = BoardLocator(),
    private val classifier: CellClassifier = CellClassifier(),
    private val extractor: PieceExtractor = PieceExtractor(),
    var minLocateConfidence: Double = 0.35,
    var minConfidence: Double = 0.5,
) {
    var sensitivity: Double = 0.5
    private var cached: LocateResult? = null

    fun clearCache() {
        cached = null
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
                    if (loc == null || loc.confidence < minLocateConfidence) {
                        notes.add(
                            if (loc == null) "board not found" else "board lattice confidence ${"%.2f".format(loc.confidence)} too low",
                        )
                        return RecognitionResult(null, GeometrySource.NONE, loc?.confidence ?: 0.0, null, null, 0.0, false, notes)
                    }
                    cached = loc
                }
                geometry = loc.geometry
                locConf = loc.confidence
                source = GeometrySource.AUTO
            }

            val board = classifier.classify(frame, geometry, sensitivity)
            if (board == null) {
                notes.add("board geometry lies outside the frame")
                cached = null
                return RecognitionResult(geometry, source, locConf, null, null, 0.0, false, notes)
            }
            if (fromCache && attempt == 1 && board.confidence < 0.4) {
                cached = null
                notes.add("cached geometry looked wrong; relocating")
                continue
            }
            val pieces = extractor.extract(frame, geometry, sensitivity)
            if (pieces == null) notes.add("tray region not readable")
            if (pieces != null) notes.addAll(pieces.notes)
            val conf = minOf(board.confidence, pieces?.confidence ?: 0.0, if (source == GeometrySource.AUTO) maxOf(locConf, 0.5) else 1.0)
            val ok = pieces != null && pieces.pieces.isNotEmpty() && board.confidence >= minConfidence && conf >= minConfidence
            if (board.confidence < minConfidence) notes.add("board reading confidence ${"%.2f".format(board.confidence)} below ${minConfidence}")
            if (pieces != null && pieces.pieces.isEmpty()) notes.add("no pieces visible (waiting for a new set?)")
            return RecognitionResult(geometry, source, locConf, board, pieces, conf, ok, notes)
        }
    }
}
