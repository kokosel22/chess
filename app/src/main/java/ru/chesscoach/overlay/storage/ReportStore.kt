package ru.chesscoach.overlay.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import ru.chesscoach.overlay.chess.GameTracker
import ru.chesscoach.overlay.chess.Position
import ru.chesscoach.overlay.chess.TrackedMove
import ru.chesscoach.overlay.engine.EngineResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportStore(private val context: Context) {
    val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    private val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
    private val rows = JSONArray()
    private var result = "*"
    private var finished = false
    private var reason = "Партия записывается"
    @Synchronized fun append(move: TrackedMove) {
        rows.put(JSONObject().put("uci", move.move.uci()).put("san", move.san).put("before", move.before.fen()).put("after", move.after.fen()))
        save()
    }
    @Synchronized fun evaluate(uci: String, beforeFen: String, before: EngineResult, after: EngineResult): Int {
        val p = Position.fromFen(beforeFen)
        val loss = ((before.whiteCp - after.whiteCp) * if (p.white) 1 else -1).coerceAtLeast(0)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("before") == beforeFen && row.getString("uci") == uci) {
                row.put("cp", after.whiteCp).put("loss", loss).put("depth", after.depth)
                before.bestMove?.let { row.put("alternative", p.san(it)) }
                break
            }
        }
        save(); return loss
    }
    @Synchronized fun finish(tracker: GameTracker, message: String) {
        result = tracker.result() ?: "*"; finished = true; reason = message; save()
    }
    @Synchronized private fun save() {
        val dir = File(context.filesDir, "games").apply { mkdirs() }
        val file = AtomicFile(File(dir, "$id.json"))
        val stream = file.startWrite()
        try {
            val data = JSONObject().put("id", id).put("date", date).put("moves", rows).put("result", result)
                .put("finished", finished).put("reason", reason)
            stream.write(data.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        fun list(context: Context): List<File> = File(context.filesDir, "games").listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.name }.orEmpty()
        fun load(file: File): JSONObject = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        fun pgn(game: JSONObject): String = buildString {
            append("[Event \"Chess Coach — Bot Training\"]\n[Site \"Chess.com Android\"]\n")
            append("[Date \"${game.getString("date")}\"]\n[White \"White\"]\n[Black \"Black\"]\n")
            append("[Result \"${game.getString("result")}\"]\n\n")
            val moves = game.getJSONArray("moves")
            for (i in 0 until moves.length()) {
                if (i % 2 == 0) append("${i / 2 + 1}. ")
                val row = moves.getJSONObject(i); append(row.getString("san")); append(' ')
                if (row.has("cp")) append("{ [%eval ${String.format(Locale.US, "%.2f", row.getInt("cp") / 100.0)}] потеря ${row.getInt("loss")} cp } ")
            }
            append(game.getString("result")); append('\n')
        }
        fun report(game: JSONObject): String = buildString {
            val moves = game.getJSONArray("moves")
            append("Тренировка ${game.getString("date")}\nХодов: ${moves.length()} полуходов\n")
            append("Результат: ${game.getString("result")}\n${game.optString("reason")}\n\n")
            val evaluated = (0 until moves.length()).count { moves.getJSONObject(it).has("loss") }
            append("Проанализировано: $evaluated из ${moves.length()}.\n")
            if (evaluated < moves.length()) append("Неоценённые ходы не включены в статистику ошибок.\n")
            for (white in listOf(true, false)) {
                val rows = (0 until moves.length()).filter { (it % 2 == 0) == white }.map { moves.getJSONObject(it) }.filter { it.has("loss") }
                val average = rows.map { it.getInt("loss") }.average()
                append("${if (white) "Белые" else "Чёрные"}: зевков ${rows.count { it.getInt("loss") >= 200 }}, ошибок ${rows.count { it.getInt("loss") in 100..199 }}")
                if (rows.isNotEmpty()) append(", средняя потеря ${average.toInt()} cp")
                append(".\n")
            }
            append("\nПорог ошибки 1 пешка, зевка 2 пешки. Оценки приблизительны: ограниченный анализ Stockfish.\n\n")
            for (i in 0 until moves.length()) {
                val row = moves.getJSONObject(i)
                append("${i / 2 + 1}${if (i % 2 == 0) "." else "…"} ${row.getString("san")}")
                if (row.has("loss")) {
                    val loss = row.getInt("loss")
                    append(" — ${if (loss >= 200) "ЗЕВОК" else if (loss >= 100) "ошибка" else if (loss >= 50) "неточность" else "хороший ход"}; потеря ${String.format(Locale.US, "%.2f", loss / 100.0)}")
                    if (loss >= 50) append("; лучше ${row.optString("alternative", "—")}")
                    append("; глубина ${row.getInt("depth")}")
                } else append(" — без оценки")
                append('\n')
            }
        }
    }
}
