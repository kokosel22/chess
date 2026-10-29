package ru.chesscoach.overlay

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import ru.chesscoach.overlay.chess.Move
import java.util.concurrent.CopyOnWriteArraySet

enum class CoachMode(val title: String) { COACH("Тренер"), HINTS("Подсказки для бота"), REVIEW("Разбор") }
data class Advice(val message: String, val evaluation: String? = null, val arrow: Move? = null)
object AppState {
    @Volatile var running = false
    @Volatile var mode = CoachMode.COACH
    @Volatile var whiteAtBottom = true
    @Volatile var playerWhite = true
    @Volatile var boardRect: Rect? = null
    @Volatile var capturedFrames = 0
    @Volatile var advice = Advice("Готов к тренировке")
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val dispatch = Runnable { listeners.forEach { it() } }
    @Synchronized fun publish(value: Advice) {
        if (advice == value) return
        advice = value
        main.removeCallbacks(dispatch)
        main.post(dispatch)
    }
    fun clearAdvice(reason: String) { publish(Advice(reason)) }
}
