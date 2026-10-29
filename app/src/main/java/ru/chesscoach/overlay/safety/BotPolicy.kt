package ru.chesscoach.overlay.safety

data class ScreenEvidence(val packageName: String, val opponentMarkers: List<String>, val allLabels: List<String>, val hasBoard: Boolean)
data class Verdict(val allowed: Boolean, val reason: String)

object BotPolicy {
    private val human = Regex("(?i)(rated|unrated|live chess|daily chess|rapid|blitz|bullet|arena|tournament|rating points|рейтингов|без рейтинга|блиц|буллет|рапид|турнир|ежедневн|соперник найден|find opponent|finding opponent|поиск соперника)")
    private val proof = Regex("(?i)^(bot|computer|бот|компьютер|игра с ботом|игра против компьютера|play computer|bot game|computer game)$")
    fun evaluate(e: ScreenEvidence): Verdict {
        if (e.packageName != "com.chess") return Verdict(false, "Ожидаю официальное приложение Chess.com")
        if (!e.hasBoard) return Verdict(false, "Ожидаю шахматную доску")
        if (e.allLabels.any { human.containsMatchIn(it.lowercase(java.util.Locale.ROOT)) }) return Verdict(false, "Онлайн-партия или неизвестный режим: подсказки отключены")
        if (e.opponentMarkers.none { proof.matches(it.trim().lowercase(java.util.Locale.ROOT)) }) return Verdict(false, "Нет явной метки «Бот» у соперника — анализ заблокирован")
        return Verdict(true, "Подтверждена тренировка с ботом")
    }
}
