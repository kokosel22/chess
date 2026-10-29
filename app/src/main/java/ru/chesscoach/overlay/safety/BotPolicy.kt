package ru.chesscoach.overlay.safety

data class ScreenEvidence(val packageName: String, val opponentMarkers: List<String>, val allLabels: List<String>, val hasBoard: Boolean, val complete: Boolean = true)
data class Verdict(val allowed: Boolean, val reason: String)

object BotPolicy {
    private val human = Regex("(?i)(rated|unrated|live chess|daily chess|rapid|blitz|bullet|arena|tournament|rating points|рейтингов|без рейтинга|блиц|буллет|рапид|турнир|ежедневн|соперник найден|find opponent|finding opponent|поиск соперника)")
    private val person = Regex("(?<![\\p{L}\\p{N}_])(человек|human)(?![\\p{L}\\p{N}_])")
    fun evaluate(e: ScreenEvidence): Verdict {
        if (e.packageName != "com.chess") return Verdict(false, "Ожидаю официальное приложение Chess.com")
        if (!e.hasBoard) return Verdict(false, "Ожидаю шахматную доску")
        if (!e.complete) return Verdict(false, "Экран не удалось прочитать полностью; ожидаю новый кадр")
        val labels = (e.allLabels + e.opponentMarkers).map { it.lowercase(java.util.Locale.ROOT) }
        if (labels.any { person.containsMatchIn(it) }) return Verdict(false, "Найдена метка «человек»: подсказки отключены")
        if (labels.any { human.containsMatchIn(it) }) return Verdict(false, "Найдены признаки онлайн-партии: подсказки отключены")
        return Verdict(true, "Метка «человек» и признаки онлайн-партии не найдены")
    }
}
