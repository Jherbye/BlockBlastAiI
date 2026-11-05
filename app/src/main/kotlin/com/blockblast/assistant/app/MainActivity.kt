package com.blockblast.assistant.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Plain-Views home screen (deliberately no Compose: smaller dependency list, less unverified build surface).
 * The assistant is suggestion-only: nothing here, or anywhere in the app, performs gameplay input.
 */
class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var overlayStatus: TextView
    private lateinit var runStatus: TextView
    private lateinit var diagnostics: TextView
    private val listener: (Snapshot) -> Unit = { s -> runOnUiThread { render(s) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 3, 0, pad / 3)
        }
        root.addView(text("Block Blast Assistant", 22f, true))
        root.addView(
            text(
                "Suggestion-only. It watches your screen after you grant capture permission and draws a highlight " +
                    "over the board. It never taps, swipes or controls the game - you make every move.",
            ),
        )
        overlayStatus = text("")
        runStatus = text("")
        root.addView(overlayStatus)
        root.addView(Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "2. Start assistant (asks for screen capture)"
            setOnClickListener { startAssistant() }
        })
        root.addView(Button(this).apply {
            text = "Stop assistant"
            setOnClickListener {
                startService(Intent(this@MainActivity, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
            }
        })
        root.addView(runStatus)
        root.addView(text("3. Open Block Blast. Keep the whole board and the three pieces visible. If highlights look misaligned, use Cal in the floating panel.", 12f))

        root.addView(text("Settings", 16f, true))
        root.addView(seek("Overlay opacity", 10, 95, settings.opacityPercent) { settings.opacityPercent = it })
        root.addView(seek("Search time (ms)", 100, 3000, settings.searchMillis) { settings.searchMillis = it })
        root.addView(Button(this).apply {
            text = "Forget learned theme (re-learn from the next correction)"
            setOnClickListener {
                settings.emptyLook = null
                Toast.makeText(this@MainActivity, "Learned theme cleared", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Show numbered follow-up moves"
            isChecked = settings.showSequence
            setOnCheckedChangeListener { _, v -> settings.showSequence = v }
        })
        diagnostics = text("").apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        root.addView(CheckBox(this).apply {
            text = "Show diagnostics"
            isChecked = settings.showDiagnostics
            setOnCheckedChangeListener { _, v ->
                settings.showDiagnostics = v
                render(AssistantState.snapshot)
            }
        })
        root.addView(diagnostics)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun seek(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = TextView(this).apply { text = "$label: $value" }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    caption.text = "$label: $v"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(caption)
        box.addView(bar)
        return box
    }

    override fun onResume() {
        super.onResume()
        AssistantState.addListener(listener)
        render(AssistantState.snapshot)
    }

    override fun onPause() {
        AssistantState.removeListener(listener)
        super.onPause()
    }

    private fun render(s: Snapshot) {
        overlayStatus.text = "Overlay permission: " + if (Settings.canDrawOverlays(this)) "granted" else "NOT granted"
        runStatus.text = (if (s.running) "Running: " else "Not running: ") + s.status
        if (!settings.showDiagnostics) {
            diagnostics.text = ""
            return
        }
        diagnostics.text = buildString {
            appendLine("Recognized board:")
            if (s.boardRows.isEmpty()) appendLine("  -") else s.boardRows.forEach { appendLine("  $it") }
            appendLine("Pieces:")
            if (s.pieceLines.isEmpty()) appendLine("  -") else s.pieceLines.forEach { appendLine("  $it") }
            appendLine("Calibration: ${s.geometryText}")
            appendLine("Confidence: %.2f   needs correction: ${s.needsCorrection}   doubtful cells: ${s.lowConfidenceCells}   learned theme: ${s.learnedTheme}".format(s.recognitionConfidence))
            appendLine("Search: ${s.searchMillis} ms, ${s.nodes} nodes, ${if (s.searchCompleted) "completed" else "budget reached"}")
            appendLine("Recommendations: ${s.recommendationCount}")
            appendLine("State hash: ${s.stateHash}")
            appendLine("Last transition: ${s.lastTransition}")
            appendLine("Notes: ${s.notes}")
            appendLine("Last error: ${s.lastError}")
        }
    }

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant the overlay permission first", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        requestCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) requestCapture() // works even if the notification is denied
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_START)
                    .putExtra(AssistantService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(AssistantService.EXTRA_DATA, data),
            )
        } else {
            Toast.makeText(this, "Screen capture permission was denied", Toast.LENGTH_LONG).show()
            AssistantState.update { it.copy(status = "Screen capture permission denied") }
        }
    }

    private companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_NOTIFICATIONS = 1002
    }
}
