package com.blockblast.assistant.core.vision.adaptive

import com.blockblast.assistant.core.model.Board
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.BoardGeometry
import com.blockblast.assistant.core.vision.GeometrySource

enum class RecognitionStatus {
    /** No board lattice found with enough confidence. Offer calibration. */
    NO_BOARD,
    /** Something was read but parts are too uncertain to base moves on: show it and ask the user to correct. */
    NEEDS_CORRECTION,
    /** Looks plausible but this theme is new and which cells are blocks was inferred: ask the user to confirm once. */
    NEEDS_CONFIRMATION,
    /** Board read confidently, no pieces visible (between sets, or animation). */
    NO_PIECES,
    /** Confident enough to show moves. */
    READY,
}

class AdaptiveResult(
    val status: RecognitionStatus,
    val geometry: BoardGeometry?,
    val geometrySource: GeometrySource,
    val geometryConfidence: Double,
    val cells: BoardCells?,
    val pieces: List<TrayPiece>,
    val trayReading: TrayReading?,
    val confidence: Double,
    val notes: List<String>,
    val userCorrected: Boolean = false,
    val themeModelActive: Boolean = false,
) {
    val ok: Boolean get() = status == RecognitionStatus.READY
    val bits: Long? get() = cells?.bits
    val board: Board? get() = bits?.let { Board.ofBits(it) }
    val pieceList: List<Piece> get() = pieces.map { it.piece }
    val slots: List<Int> get() = pieces.map { it.slot }
    val lowConfidenceCells: List<Int> get() = cells?.confidence?.indices?.filter { cells.confidence[it] < LOW } ?: emptyList()
    val lowConfidencePieceSlots: List<Int> get() = pieces.filter { it.confidence < LOW }.map { it.slot }

    companion object {
        const val LOW = 0.55
    }
}

/** What the user told us is true (manual correction / confirmation). */
class UserState(val bits: Long, val pieces: List<Piece?>)
