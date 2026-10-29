package ru.chesscoach.overlay.engine

import android.content.Context
import ru.chesscoach.overlay.chess.Move
import ru.chesscoach.overlay.chess.Position
import java.io.Closeable
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class EngineResult(val whiteCp: Int, val mate: Int?, val bestMove: Move?, val depth: Int) {
    fun display() = mate?.let { if (it == 0) "Мат" else "Мат ${if (it > 0) "+" else "−"}${kotlin.math.abs(it)}" }
        ?: String.format(java.util.Locale.US, "%+.2f", whiteCp / 100.0)
}

/** UCI is serialized on one analysis worker; close() may interrupt from the main thread. */
class StockfishEngine(private val context: Context) : Closeable {
    @Volatile private var process: Process? = null
    private var input: java.io.BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>(4096)
    private var reader: Thread? = null
    private fun start() {
        if (process?.isAlive == true) return
        val binary = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        check(binary.canExecute()) { "В APK отсутствует исполняемый Stockfish для этого процессора" }
        lines.clear()
        val p = ProcessBuilder(binary.absolutePath).directory(context.filesDir).redirectErrorStream(true).start()
        process = p; input = p.outputStream.bufferedWriter()
        reader = Thread({
            try { p.inputStream.bufferedReader().useLines { stream -> stream.forEach { if (!lines.offer(it)) { lines.poll(); lines.offer(it) } } } }
            catch (_: java.io.IOException) { /* Process stopped. */ }
            finally { lines.offer("__eof__") }
        }, "stockfish-uci-reader").apply { isDaemon = true; start() }
        send("uci"); until("uciok", 12000)
        send("setoption name Threads value 1")
        send("setoption name Hash value 16")
        send("setoption name MultiPV value 1")
        send("isready"); until("readyok", 12000)
    }
    private fun send(command: String) { val writer = checkNotNull(input); writer.write(command); writer.newLine(); writer.flush() }
    private fun until(prefix: String, timeout: Long, consume: (String) -> Unit = {}): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (true) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "Stockfish не ответил вовремя" }
            val line = lines.poll(left, TimeUnit.NANOSECONDS) ?: error("Stockfish не ответил вовремя")
            check(line != "__eof__") { "Stockfish завершился: проверьте состав APK" }
            consume(line)
            if (line.startsWith(prefix)) return line
        }
    }
    @Synchronized fun analyze(position: Position, millis: Int = 650): EngineResult {
        // Reparse all six fields, so invalid states never reach the native process.
        Position.fromFen(position.fen())
        try {
            start(); send("position fen ${position.fen()}"); send("go movetime ${millis.coerceIn(100, 2000)}")
            var cp: Int? = null; var mate: Int? = null; var depth = 0
            val final = until("bestmove", 12000) { line ->
                if (line.startsWith("info ") && !line.contains("lowerbound") && !line.contains("upperbound")) {
                    Regex("\\bdepth (\\d+)").find(line)?.let { depth = it.groupValues[1].toInt() }
                    Regex("\\bscore (cp|mate) (-?\\d+)").find(line)?.let {
                        val rawScore = it.groupValues[2].toInt()
                        val value = rawScore * if (position.white) 1 else -1
                        if (it.groupValues[1] == "mate") {
                            mate = value
                            cp = if (rawScore == 0) (if (position.white) -30000 else 30000) else if (value > 0) 30000 else -30000
                        }
                        else { cp = value; mate = null }
                    }
                }
            }
            val raw = final.split(' ').getOrNull(1)
            val best = raw?.takeIf { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }?.let(Move::parse)
            val legal = position.legalMoves()
            check(best == null && legal.isEmpty() || best in legal) { "Движок вернул нелегальный ход" }
            val terminal = if (position.inCheck()) (if (position.white) -30000 else 30000) else 0
            return EngineResult(cp ?: if (legal.isEmpty()) terminal else error("Нет оценки Stockfish"), mate, best, depth)
        } catch (e: Exception) { close(); throw e }
    }
    override fun close() {
        val p = process; process = null
        runCatching { p?.destroy() }; runCatching { p?.inputStream?.close() }; runCatching { p?.outputStream?.close() }
        input = null
    }
}
