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
    @Volatile var advice = Advice("Готов к тренировке")
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun publish(value: Advice) { advice = value; main.post { listeners.forEach { it() } } }
    fun clearAdvice(reason: String) { publish(Advice(reason)) }
}
