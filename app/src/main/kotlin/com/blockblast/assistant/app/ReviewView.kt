package com.blockblast.assistant.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.blockblast.assistant.core.model.Cell
import com.blockblast.assistant.core.model.Piece
import com.blockblast.assistant.core.vision.BoardGeometry

/**
 * Manual correction / confirmation editor, shown over the game only when recognition is unsure (or when the user asks).
 *
 * It edits the ASSISTANT'S reading of the screen: tapping a cell here toggles our own bit, it is never forwarded to the
 * game and nothing is injected. While it is open it intentionally swallows touches so the user cannot hit the game by
 * accident. Cells and pieces the recogniser was unsure about are outlined in red.
 *
 * Coordinates are screen pixels (the same space as [BoardGeometry]).
 */
internal class ReviewView(
    context: Context,
    private val geometry: BoardGeometry,
    initialBits: Long,
    private val cellConfidence: DoubleArray?,
    initialPieces: List<Piece?>,
    private val pieceConfidence: List<Double>,
    private val confirmMode: Boolean,
    private val screenW: Int,
    private val onConfirm: () -> Unit,
    private val onApply: (Long, List<Piece?>) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val occupied = BooleanArray(64) { (initialBits ushr it) and 1L == 1L }
    private val tray = Array(3) { BooleanArray(25) }
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * density }

    init {
        initialPieces.take(3).forEachIndexed { slot, p ->
            p?.cells?.forEach { c -> if (c.x in 0..4 && c.y in 0..4) tray[slot][c.y * 5 + c.x] = true }
        }
        setWillNotDraw(false)
        setBackgroundColor(Color.argb(90, 0, 0, 0))
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (confirmMode) bar.addView(Button(context).apply {
            this.text = "Looks right"
            isAllCaps = false
            setOnClickListener { onConfirm() }
        })
        bar.addView(Button(context).apply {
            this.text = "Apply my fixes"
            isAllCaps = false
            setOnClickListener { onApply(currentBits(), currentPieces()) }
        })
        bar.addView(Button(context).apply {
            this.text = "Cancel"
            isAllCaps = false
            setOnClickListener { onCancel() }
        })
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (24 * density).toInt()
        })
    }

    private fun currentBits(): Long {
        var b = 0L
        for (i in 0 until 64) if (occupied[i]) b = b or (1L shl i)
        return b
    }

    /** An empty editor grid means "no piece in this slot". */
    private fun currentPieces(): List<Piece?> = tray.map { grid ->
        val cells = ArrayList<Cell>()
        for (i in 0 until 25) if (grid[i]) cells.add(Cell(i % 5, i / 5))
        if (cells.isEmpty()) null else Piece(cells)
    }

    // ---- layout (screen coordinates) ---------------------------------------------------------------------------------

    private val editorCell: Float get() = minOf(36f * density, (screenW - 24f * density) / 16.5f)
    private val editorLeft0: Float get() = (screenW - 16.5f * editorCell) / 2f
    private val editorTop: Float get() = geometry.bottom + 40f * density
    private fun editorRect(slot: Int): RectF {
        val l = editorLeft0 + slot * 6.5f * editorCell
        return RectF(l, editorTop, l + 5f * editorCell, editorTop + 5f * editorCell)
    }

    override fun onDraw(canvas: Canvas) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        for (y in 0 until 8) for (x in 0 until 8) {
            val i = y * 8 + x
            val r = geometry.cellRect(x, y)
            val rect = RectF(r.left + 3f, r.top + 3f, r.right - 3f, r.bottom - 3f)
            if (occupied[i]) {
                fill.color = Color.argb(150, 0, 200, 255)
                canvas.drawRoundRect(rect, 8f, 8f, fill)
            }
            val unsure = cellConfidence != null && cellConfidence[i] < LOW
            stroke.color = if (unsure) Color.rgb(255, 60, 60) else Color.argb(160, 255, 255, 255)
            stroke.strokeWidth = (if (unsure) 3.5f else 1.2f) * density
            canvas.drawRoundRect(rect, 8f, 8f, stroke)
        }

        text.textSize = 13f * density
        canvas.drawText(
            if (confirmMode) "Is this reading right? Tap cells to fix. Red = unsure." else "Tap cells to fix. Red = unsure.",
            geometry.left, maxOf(geometry.top - 12f * density, 24f * density), text,
        )
        val e = editorCell
        for (slot in 0 until 3) {
            val er = editorRect(slot)
            val unsure = pieceConfidence.getOrNull(slot)?.let { it < LOW } ?: false
            canvas.drawText("Piece ${slot + 1}", er.left, er.top - 8f * density, text)
            for (i in 0 until 25) {
                val cr = RectF(er.left + (i % 5) * e, er.top + (i / 5) * e, er.left + (i % 5 + 1) * e, er.top + (i / 5 + 1) * e)
                if (tray[slot][i]) {
                    fill.color = Color.argb(200, 255, 190, 60)
                    canvas.drawRect(cr, fill)
                }
                stroke.color = Color.argb(120, 255, 255, 255)
                stroke.strokeWidth = 1f * density
                canvas.drawRect(cr, stroke)
            }
            stroke.color = if (unsure) Color.rgb(255, 60, 60) else Color.argb(200, 255, 255, 255)
            stroke.strokeWidth = (if (unsure) 3f else 1.5f) * density
            canvas.drawRect(er, stroke)
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = e.rawX
            val y = e.rawY
            val cs = geometry.cellSize
            if (x >= geometry.left && y >= geometry.top && x < geometry.right && y < geometry.bottom) {
                val cx = ((x - geometry.left) / cs).toInt().coerceIn(0, 7)
                val cy = ((y - geometry.top) / cs).toInt().coerceIn(0, 7)
                occupied[cy * 8 + cx] = !occupied[cy * 8 + cx]
                invalidate()
            } else {
                for (slot in 0 until 3) {
                    val er = editorRect(slot)
                    if (er.contains(x, y)) {
                        val c = ((x - er.left) / editorCell).toInt().coerceIn(0, 4)
                        val r = ((y - er.top) / editorCell).toInt().coerceIn(0, 4)
                        tray[slot][r * 5 + c] = !tray[slot][r * 5 + c]
                        invalidate()
                        break
                    }
                }
            }
        }
        return true // modal on purpose; see class comment
    }

    private companion object {
        const val LOW = 0.55
    }
}
