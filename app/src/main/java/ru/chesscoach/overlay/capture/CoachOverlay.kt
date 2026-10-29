package ru.chesscoach.overlay.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ru.chesscoach.overlay.AppState
import ru.chesscoach.overlay.CoachMode
import ru.chesscoach.overlay.safety.ChessAccessibilityService
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CoachOverlay(private val context: Context, private val stop: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val drawing = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (AppState.mode != CoachMode.HINTS || !ChessAccessibilityService.permits()) return
            val board = AppState.boardRect ?: return
            val move = AppState.advice.arrow ?: return
            val offset = IntArray(2); getLocationOnScreen(offset)
            val cell = board.width() / 8f
            fun point(i: Int): Pair<Float, Float> {
                val col = if (AppState.whiteAtBottom) i % 8 else 7 - i % 8
                val row = if (AppState.whiteAtBottom) 7 - i / 8 else i / 8
                return Pair(board.left + (col + .5f) * cell - offset[0], board.top + (row + .5f) * cell - offset[1])
            }
            val (x1, y1) = point(move.from); val (x2, y2) = point(move.to)
            paint.color = Color.rgb(12, 162, 151); paint.strokeWidth = cell * .12f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x1, y1, x2, y2, paint)
            val angle = atan2(y2 - y1, x2 - x1)
            val arrow = Path().apply {
                moveTo(x2, y2)
                lineTo(x2 - cell * .36f * cos(angle - .55f), y2 - cell * .36f * sin(angle - .55f))
                lineTo(x2 - cell * .36f * cos(angle + .55f), y2 - cell * .36f * sin(angle + .55f)); close()
            }
            canvas.drawPath(arrow, paint)
        }
    }
    private val label = TextView(context).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), dp(6), dp(12), dp(6)); maxWidth = dp(300) }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(20, 43, 40)); addView(label)
        addView(LinearLayout(context).apply {
            addView(Button(context).apply { text = "Свернуть"; minHeight = dp(48); setOnClickListener { label.visibility = if (label.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Стоп"; minHeight = dp(48); setOnClickListener { stop() } }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private val drawParams = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        // Below Android 12's maximum obscuring opacity, so board touches pass through.
        alpha = .60f
    }
    private val panelParams = WindowManager.LayoutParams(dp(310), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(40) }
    private var captureHidden = false
    private val refresh: () -> Unit = {
        val advice = AppState.advice
        label.text = "${AppState.mode.title}\n${advice.message}" + (advice.evaluation?.takeIf { ChessAccessibilityService.permits() }?.let { "\nОценка белых: $it" } ?: "")
        updateDrawingVisibility(); drawing.invalidate()
    }
    init {
        try { manager.addView(drawing, drawParams); manager.addView(panel, panelParams) }
        catch (e: Exception) { runCatching { manager.removeView(drawing) }; throw e }
        AppState.listeners.add(refresh); refresh()
    }
    fun placeBelowBoard() {
        val r = AppState.boardRect ?: return
        val display = manager.maximumWindowMetrics.bounds
        val y = (r.bottom + dp(8)).coerceAtMost(display.height() - panel.height - dp(32)).coerceAtLeast(dp(32))
        if (panelParams.y == y) return
        panelParams.y = y
        manager.updateViewLayout(panel, panelParams)
    }
    fun hideForCapture(hidden: Boolean) {
        // Prevent capturing our own arrows into templates during full-display projection.
        captureHidden = hidden
        updateDrawingVisibility()
    }
    private fun updateDrawingVisibility() {
        val visibility = if (captureHidden || AppState.mode != CoachMode.HINTS || AppState.advice.arrow == null || !ChessAccessibilityService.permits()) View.INVISIBLE else View.VISIBLE
        if (drawing.visibility != visibility) drawing.visibility = visibility
    }
    fun close() { AppState.listeners.remove(refresh); runCatching { manager.removeView(drawing) }; runCatching { manager.removeView(panel) } }
}
