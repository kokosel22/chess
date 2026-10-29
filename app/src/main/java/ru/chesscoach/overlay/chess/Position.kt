package ru.chesscoach.overlay.chess

import kotlin.math.abs

data class Move(val from: Int, val to: Int, val promotion: Char? = null) {
    fun uci() = square(from) + square(to) + (promotion?.lowercaseChar()?.toString() ?: "")
    companion object {
        fun square(i: Int) = "${('a'.code + i % 8).toChar()}${i / 8 + 1}"
        fun parse(s: String): Move {
            require(s.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
            return Move(index(s.substring(0, 2)), index(s.substring(2, 4)), s.getOrNull(4))
        }
        fun index(s: String) = (s[1] - '1') * 8 + (s[0] - 'a')
    }
}

/** a1=0, h8=63. Immutable externally: every transition owns a fresh board. */
class Position private constructor(
    val cells: CharArray, val white: Boolean, val castling: String,
    val ep: Int, val halfmove: Int, val fullmove: Int
) {
    fun placement(): String = (7 downTo 0).joinToString("/") { r ->
        buildString {
            var blanks = 0
            for (f in 0..7) {
                val p = cells[r * 8 + f]
                if (p == '.') blanks++ else { if (blanks > 0) append(blanks); blanks = 0; append(p) }
            }
            if (blanks > 0) append(blanks)
        }
    }
    fun fen() = "${placement()} ${if (white) "w" else "b"} ${castling.ifEmpty { "-" }} ${if (ep < 0) "-" else Move.square(ep)} $halfmove $fullmove"
    fun inCheck(side: Boolean = white) = attacked(cells.indexOf(if (side) 'K' else 'k'), !side)
    fun attacked(target: Int, byWhite: Boolean): Boolean {
        if (target !in 0..63) return true
        val x = target % 8; val y = target / 8
        for (i in 0..63) {
            val p = cells[i]
            if (p == '.' || p.isUpperCase() != byWhite) continue
            val dx = x - i % 8; val dy = y - i / 8
            when (p.lowercaseChar()) {
                'p' -> if (abs(dx) == 1 && dy == if (byWhite) 1 else -1) return true
                'n' -> if (abs(dx) * abs(dy) == 2) return true
                'k' -> if (maxOf(abs(dx), abs(dy)) == 1) return true
                else -> {
                    val diagonal = abs(dx) == abs(dy) && dx != 0
                    val straight = (dx == 0) != (dy == 0)
                    val hits = when (p.lowercaseChar()) { 'b' -> diagonal; 'r' -> straight; 'q' -> diagonal || straight; else -> false }
                    if (hits) {
                        val sx = dx.compareTo(0); val sy = dy.compareTo(0)
                        var fx = i % 8 + sx; var fy = i / 8 + sy; var clear = true
                        while (fx != x || fy != y) {
                            if (cells[fy * 8 + fx] != '.') { clear = false; break }
                            fx += sx; fy += sy
                        }
                        if (clear) return true
                    }
                }
            }
        }
        return false
    }
    fun legalMoves(): List<Move> {
        val moves = mutableListOf<Move>()
        fun add(from: Int, to: Int) {
            if (to !in 0..63) return
            val dest = cells[to]
            if (dest != '.' && (dest.isUpperCase() == white || dest.lowercaseChar() == 'k')) return
            if (cells[from].lowercaseChar() == 'p' && to / 8 in listOf(0, 7)) {
                for (p in "qrbn") moves += Move(from, to, p)
            } else moves += Move(from, to)
        }
        for (i in 0..63) {
            val p = cells[i]; if (p == '.' || p.isUpperCase() != white) continue
            val f = i % 8; val r = i / 8
            when (p.lowercaseChar()) {
                'p' -> {
                    val step = if (white) 8 else -8; val one = i + step
                    if (one in 0..63 && cells[one] == '.') {
                        add(i, one)
                        if (r == if (white) 1 else 6) if (cells[i + 2 * step] == '.') add(i, i + 2 * step)
                    }
                    for (df in listOf(-1, 1)) {
                        val to = i + step + df
                        if (f + df in 0..7 && to in 0..63) {
                            if (cells[to] != '.' && cells[to].isUpperCase() != white) add(i, to)
                            else if (to == ep && cells[to - step] == if (white) 'p' else 'P') add(i, to)
                        }
                    }
                }
                'n', 'k' -> {
                    val jumps = if (p.lowercaseChar() == 'n') KNIGHT else KING
                    for ((dx, dy) in jumps) if (f + dx in 0..7 && r + dy in 0..7) add(i, (r + dy) * 8 + f + dx)
                    if (p.lowercaseChar() == 'k') {
                        val base = if (white) 0 else 56
                        if (i == base + 4 && !inCheck()) {
                            val rook = if (white) 'R' else 'r'
                            if ((if (white) 'K' else 'k') in castling && cells[base + 7] == rook && cells[base + 5] == '.' && cells[base + 6] == '.' && !attacked(base + 5, !white) && !attacked(base + 6, !white)) add(i, base + 6)
                            if ((if (white) 'Q' else 'q') in castling && cells[base] == rook && (1..3).all { cells[base + it] == '.' } && !attacked(base + 3, !white) && !attacked(base + 2, !white)) add(i, base + 2)
                        }
                    }
                }
                else -> {
                    val dirs = when (p.lowercaseChar()) { 'b' -> DIAGONAL; 'r' -> STRAIGHT; else -> DIAGONAL + STRAIGHT }
                    for ((dx, dy) in dirs) {
                        var fx = f + dx; var ry = r + dy
                        while (fx in 0..7 && ry in 0..7) {
                            val to = ry * 8 + fx; add(i, to)
                            if (cells[to] != '.') break
                            fx += dx; ry += dy
                        }
                    }
                }
            }
        }
        return moves.filter { !applyUnchecked(it).inCheck(white) }
    }
    fun play(move: Move): Position {
        require(move in legalMoves()) { "Недопустимый ход ${move.uci()}" }
        return applyUnchecked(move)
    }
    internal fun applyUnchecked(move: Move): Position {
        val b = cells.copyOf(); val p = b[move.from]; val captured = b[move.to]
        b[move.from] = '.'
        if (p.lowercaseChar() == 'p' && move.to == ep && captured == '.') b[move.to + if (white) -8 else 8] = '.'
        b[move.to] = move.promotion?.let { if (white) it.uppercaseChar() else it.lowercaseChar() } ?: p
        if (p.lowercaseChar() == 'k' && abs(move.to - move.from) == 2) {
            val rf = if (move.to > move.from) move.from + 3 else move.from - 4
            val rt = if (move.to > move.from) move.from + 1 else move.from - 1
            b[rt] = b[rf]; b[rf] = '.'
        }
        var rights = castling
        if (p == 'K') rights = rights.replace("K", "").replace("Q", "")
        if (p == 'k') rights = rights.replace("k", "").replace("q", "")
        for ((sq, flag) in listOf(0 to 'Q', 7 to 'K', 56 to 'q', 63 to 'k'))
            if (move.from == sq || move.to == sq) rights = rights.replace(flag.toString(), "")
        return Position(b, !white, rights, if (p.lowercaseChar() == 'p' && abs(move.to - move.from) == 16) (move.from + move.to) / 2 else -1,
            if (p.lowercaseChar() == 'p' || captured != '.') 0 else halfmove + 1, fullmove + if (white) 0 else 1)
    }
    fun san(move: Move): String {
        val legal = legalMoves(); require(move in legal)
        val p = cells[move.from]
        var text = if (p.lowercaseChar() == 'k' && abs(move.to - move.from) == 2) {
            if (move.to > move.from) "O-O" else "O-O-O"
        } else buildString {
            val capture = cells[move.to] != '.' || (p.lowercaseChar() == 'p' && move.to == ep)
            if (p.lowercaseChar() != 'p') {
                append(p.uppercaseChar())
                val other = legal.filter { it.from != move.from && it.to == move.to && cells[it.from] == p }
                if (other.isNotEmpty()) {
                    if (other.none { it.from % 8 == move.from % 8 }) append(Move.square(move.from)[0])
                    else if (other.none { it.from / 8 == move.from / 8 }) append(Move.square(move.from)[1])
                    else append(Move.square(move.from))
                }
            } else if (capture) append(Move.square(move.from)[0])
            if (capture) append('x')
            append(Move.square(move.to))
            move.promotion?.let { append('='); append(it.uppercaseChar()) }
        }
        val next = applyUnchecked(move)
        if (next.inCheck()) text += if (next.legalMoves().isEmpty()) "#" else "+"
        return text
    }
    fun repetitionKey(): String {
        // En passant is significant only when a legal capture is possible (FIDE 9.2).
        val relevantEp = if (ep >= 0 && legalMoves().any { it.to == ep && cells[it.from].lowercaseChar() == 'p' }) Move.square(ep) else "-"
        return "${placement()} $white $castling $relevantEp"
    }
    fun outcome(repetitions: Int = 1): String? {
        if (legalMoves().isEmpty()) return if (inCheck()) (if (white) "0-1" else "1-0") else "1/2-1/2"
        if (halfmove >= 100 || repetitions >= 3) return "1/2-1/2"
        val men = cells.filter { it != '.' && it.lowercaseChar() != 'k' }
        if (men.isEmpty() || (men.size == 1 && men[0].lowercaseChar() in "bn")) return "1/2-1/2"
        if (men.all { it.lowercaseChar() == 'b' }) {
            val shades = cells.indices.filter { cells[it].lowercaseChar() == 'b' }.map { (it / 8 + it % 8) % 2 }.distinct()
            if (shades.size == 1) return "1/2-1/2"
        }
        return null
    }
    companion object {
        const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private val DIAGONAL = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val KING = DIAGONAL + STRAIGHT
        private val KNIGHT = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
        fun initial() = fromFen(START)
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+")); require(parts.size == 6) { "FEN: требуется шесть полей" }
            val rows = parts[0].split('/'); require(rows.size == 8)
            val b = CharArray(64) { '.' }
            for ((row, text) in rows.withIndex()) {
                var f = 0
                for (p in text) if (p in '1'..'8') f += p - '0' else {
                    require(p in "PNBRQKpnbrqk" && f < 8); b[(7 - row) * 8 + f++] = p
                }
                require(f == 8)
            }
            require(parts[1] in listOf("w", "b"))
            require(parts[2] == "-" || (parts[2].isNotEmpty() && parts[2].all { it in "KQkq" } && parts[2].toSet().size == parts[2].length))
            val ep = if (parts[3] == "-") -1 else { require(parts[3].matches(Regex("[a-h][36]"))); Move.index(parts[3]) }
            val p = Position(b, parts[1] == "w", parts[2].replace("-", ""), ep, parts[4].toInt(), parts[5].toInt())
            require(p.halfmove >= 0 && p.fullmove >= 1)
            require(b.count { it == 'K' } == 1 && b.count { it == 'k' } == 1)
            require((0..7).none { b[it].lowercaseChar() == 'p' || b[56 + it].lowercaseChar() == 'p' })
            for (side in listOf(true, false)) {
                require(b.count { it != '.' && it.isUpperCase() == side } <= 16)
                require(b.count { it == if (side) 'P' else 'p' } <= 8)
            }
            require(!p.inCheck(!p.white)) { "Сторона, завершившая ход, оставила короля под шахом" }
            for ((flag, king, rook) in listOf(Triple('K', 4, 7), Triple('Q', 4, 0), Triple('k', 60, 63), Triple('q', 60, 56)))
                if (flag in p.castling) require(b[king] == if (flag.isUpperCase()) 'K' else 'k') { "Рокировка: нет короля" }.also {
                    require(b[rook] == if (flag.isUpperCase()) 'R' else 'r') { "Рокировка: нет ладьи" }
                }
            if (ep >= 0) {
                require(b[ep] == '.' && ep / 8 == if (p.white) 5 else 2)
                require(b[ep + if (p.white) -8 else 8] == if (p.white) 'p' else 'P')
                require(b[ep + if (p.white) 8 else -8] == '.' && p.halfmove == 0)
            }
            return p
        }
    }
}
