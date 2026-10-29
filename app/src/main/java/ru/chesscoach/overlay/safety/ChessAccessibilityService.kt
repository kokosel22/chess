package ru.chesscoach.overlay.safety

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ru.chesscoach.overlay.AppState

class ChessAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var dirty = true
    private val poll = object : Runnable {
        override fun run() {
            inspect()
            handler.postDelayed(this, if (AppState.running) 400 else 1000)
        }
    }
    private val contentChanged = Runnable { inspect() }
    override fun onServiceConnected() { instance = this; handler.post(poll) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AppState.running || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val eventPackage = event.packageName?.toString()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventPackage == "com.chess") dirty = true
            // Overlay visibility emits WINDOWS_CHANGED with no package. It must not
            // revoke an ongoing search; inspect() still checks the active package.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage != null && eventPackage !in listOf("com.chess", packageName)) invalidate("Активный экран изменился")
            inspect()
        } else if (event.packageName?.toString() == "com.chess") {
            dirty = true
            if (!handler.hasCallbacks(contentChanged)) handler.postDelayed(contentChanged, 100)
        }
    }
    override fun onInterrupt() { invalidate("Чтение экрана прервано") }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); instance = null; chessVisible = false
        invalidate("Включите проверку метки «человек»"); super.onDestroy()
    }
    private fun inspect() {
        if (!AppState.running) { chessVisible = false; dirty = true; invalidate("Захват остановлен"); return }
        val root = rootInActiveWindow ?: run { chessVisible = false; invalidate("Не удаётся прочитать активный экран"); return }
        val pkg = root.packageName?.toString().orEmpty()
        chessVisible = pkg == "com.chess"
        if (!chessVisible) { dirty = true; invalidate("Ожидаю официальное приложение Chess.com"); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 350 || (!dirty && now - lastScan < 1000)) return
        val all = mutableListOf<String>()
        var visited = 0; var complete = root.isVisibleToUser
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || ++visited > 512) { complete = false; return }
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(all::add)
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(all::add)
            }
            for (i in 0 until node.childCount) {
                if (!complete) break
                val child = node.getChild(i)
                if (child != null) visit(child, depth + 1) else complete = false
            }
        }
        visit(root, 0)
        val result = BotPolicy.evaluate(ScreenEvidence(pkg, emptyList(), all, AppState.boardRect != null, complete))
        val wasAllowed = allowed
        allowed = result.allowed; reason = result.reason; checkedAt = now
        lastScan = now; dirty = false
        if (wasAllowed != allowed) epoch++
        if (!allowed) AppState.clearAdvice(reason)
    }
    companion object {
        @Volatile var instance: ChessAccessibilityService? = null; private set
        @Volatile private var allowed = false
        @Volatile var chessVisible = false; private set
        @Volatile var reason = "Включите проверку метки «человек»"; private set
        @Volatile private var checkedAt = 0L
        @Volatile var epoch = 0L; private set
        fun permits() = instance != null && AppState.running && allowed && SystemClock.elapsedRealtime() - checkedAt < 1800
        fun invalidate(message: String) {
            if (allowed) epoch++
            allowed = false; reason = message; checkedAt = 0
            AppState.clearAdvice(message)
        }
    }
}
