package ru.chesscoach.overlay.safety

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.chesscoach.overlay.AppState

class ChessAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { inspect(); handler.postDelayed(this, 200) }
    }
    override fun onServiceConnected() { instance = this; handler.post(poll) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() !in listOf("com.chess", packageName)) invalidate("Экран изменился; проверяю режим")
        inspect()
    }
    override fun onInterrupt() { invalidate("Проверка режима прервана") }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); instance = null; invalidate("Включите службу проверки режима"); super.onDestroy() }
    private fun inspect() {
        val root = rootInActiveWindow ?: run { invalidate("Не удаётся проверить активный экран"); return }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.chess") { invalidate("Ожидаю официальное приложение Chess.com"); return }
        val board = AppState.boardRect
        val all = mutableListOf<String>(); val markers = mutableListOf<String>()
        val screen = Rect(); root.getBoundsInScreen(screen)
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || all.size > 600) return
            if (node.isVisibleToUser) {
                val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                all.addAll(labels)
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val id = node.viewIdResourceName.orEmpty().lowercase()
                // Positive proof must be at the opponent header, never a menu link to Bots.
                val opponentHeader = board != null && bounds.bottom <= board.top && bounds.top >= board.top - board.width() / 3 && bounds.left < board.right && bounds.right > board.left
                if (opponentHeader && !node.isClickable && !id.contains("menu") && !id.contains("navigation")) markers.addAll(labels)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        val result = BotPolicy.evaluate(ScreenEvidence(pkg, markers, all, board != null))
        val wasAllowed = allowed
        allowed = result.allowed; reason = result.reason; checkedAt = SystemClock.elapsedRealtime()
        if (wasAllowed != allowed) epoch++
        if (!allowed) AppState.clearAdvice(reason)
    }
    companion object {
        @Volatile var instance: ChessAccessibilityService? = null; private set
        @Volatile private var allowed = false
        @Volatile var reason = "Включите службу проверки режима"; private set
        @Volatile private var checkedAt = 0L
        @Volatile var epoch = 0L; private set
        fun permits() = instance != null && allowed && SystemClock.elapsedRealtime() - checkedAt < 650
        fun invalidate(message: String) { allowed = false; reason = message; checkedAt = 0; epoch++; AppState.clearAdvice(message) }
    }
}
