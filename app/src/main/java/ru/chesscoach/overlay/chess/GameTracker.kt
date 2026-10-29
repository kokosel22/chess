package ru.chesscoach.overlay.chess

data class TrackedMove(val before: Position, val move: Move, val after: Position, val san: String)
sealed class Observation {
    data object Initial : Observation()
    data object Unchanged : Observation()
    data class Advanced(val moves: List<TrackedMove>) : Observation()
    data class Rejected(val reason: String) : Observation()
}

/** A screenshot never invents turn, castling rights, or en passant state. */
class GameTracker {
    var position: Position? = null; private set
    val history = mutableListOf<TrackedMove>()
    private val repetitions = mutableMapOf<String, Int>()
    fun observe(board: CharArray): Observation {
        if (board.size != 64) return Observation.Rejected("Не распознаны 64 клетки")
        val current = position
        if (current == null) {
            val initial = Position.initial()
            if (!initial.cells.contentEquals(board)) return Observation.Rejected("Начните новую партию: нужна начальная расстановка")
            position = initial; repetitions[initial.repetitionKey()] = 1
            return Observation.Initial
        }
        if (current.cells.contentEquals(board)) return Observation.Unchanged
        val single = current.legalMoves().filter { current.applyUnchecked(it).cells.contentEquals(board) }
        if (single.size == 1) return accept(listOf(make(current, single.single())))
        // Recover a fast bot reply between frames only if exactly one legal two-ply path fits.
        val paths = mutableListOf<List<TrackedMove>>()
        for (first in current.legalMoves()) {
            val intermediate = current.applyUnchecked(first)
            for (second in intermediate.legalMoves()) {
                val next = intermediate.applyUnchecked(second)
                if (next.cells.contentEquals(board)) paths += listOf(make(current, first), make(intermediate, second))
                if (paths.size > 1) return Observation.Rejected("Неоднозначная последовательность ходов; анализ приостановлен")
            }
        }
        return if (paths.size == 1) accept(paths.single()) else Observation.Rejected("Кадр не соответствует легальным ходам; ожидаю устойчивую доску")
    }
    private fun make(p: Position, m: Move): TrackedMove = TrackedMove(p, m, p.applyUnchecked(m), p.san(m))
    private fun accept(path: List<TrackedMove>): Observation {
        for (m in path) { history += m; position = m.after; val key = m.after.repetitionKey(); repetitions[key] = (repetitions[key] ?: 0) + 1 }
        return Observation.Advanced(path)
    }
    fun result() = position?.let { it.outcome(repetitions[it.repetitionKey()] ?: 1) } ?: "*"
}
