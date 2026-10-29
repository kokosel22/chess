package ru.chesscoach.overlay

import org.junit.Assert.*
import org.junit.Test
import ru.chesscoach.overlay.chess.*
import ru.chesscoach.overlay.safety.*

class RulesTest {
    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = p.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(p.applyUnchecked(it), depth - 1) }
    }
    @Test fun initialPerft() {
        val p = Position.initial()
        assertEquals(Position.START, p.fen())
        assertEquals(20L, perft(p, 1)); assertEquals(400L, perft(p, 2)); assertEquals(8902L, perft(p, 3))
    }
    @Test fun kiwipetePerftCoversCastlingAndPins() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, perft(p, 1)); assertEquals(2039L, perft(p, 2)); assertEquals(97862L, perft(p, 3))
    }
    @Test fun enPassantCannotExposeKing() {
        val p = Position.fromFen("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertFalse(Move.parse("e5d6") in p.legalMoves())
    }
    @Test fun enPassantTracksHalfmoveAndCapturedPawn() {
        var p = Position.initial()
        for (move in listOf("e2e4", "a7a6", "e4e5", "d7d5")) p = p.play(Move.parse(move))
        assertEquals(Move.index("d6"), p.ep)
        p = p.play(Move.parse("e5d6"))
        assertEquals('.', p.cells[Move.index("d5")]); assertEquals('P', p.cells[Move.index("d6")]); assertEquals(0, p.halfmove)
        assertEquals(p.fen(), Position.fromFen(p.fen()).fen())
    }
    @Test fun castleCannotPassThroughAttack() {
        val p = Position.fromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1")
        assertFalse(Move.parse("e1g1") in p.legalMoves())
    }
    @Test fun rookMoveAndCaptureRemoveRights() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").play(Move.parse("a1a8"))
        assertEquals("Kk", p.castling)
    }
    @Test fun fourPromotionsAndSan() {
        val p = Position.fromFen("7k/P7/8/8/8/8/8/7K w - - 0 1")
        assertEquals(4, p.legalMoves().count { it.from == 48 })
        assertEquals("a8=Q+", p.san(Move.parse("a7a8q")))
    }
    @Test fun foolsMate() {
        var p = Position.initial()
        for (uci in listOf("f2f3", "e7e5", "g2g4")) p = p.play(Move.parse(uci))
        assertEquals("Qh4#", p.san(Move.parse("d8h4")))
        p = p.play(Move.parse("d8h4")); assertEquals("0-1", p.outcome()); assertTrue(p.inCheck())
    }
    @Test fun stalemateIsDraw() {
        val p = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertFalse(p.inCheck()); assertEquals("1/2-1/2", p.outcome())
    }
    @Test fun invalidFenRejected() {
        for (fen in listOf("8/8/8/8/8/8/8/8 w - - 0 1", "8/8/8/8/8/8/4k3/4K3 w - - 0 1", "4k3/8/8/8/8/8/8/4K3 w K - 0 1", "4k3/8/8/8/8/8/8/4K3 w - e6 0 1", "4k3/8/8/8/8/8/8/4K3 x - - 0 1", "4k3/8/8/8/8/8/8/P3K3 w - - 0 1")) {
            assertThrows(IllegalArgumentException::class.java) { Position.fromFen(fen) }
        }
    }
    @Test fun trackerRequiresInitialAndRecoversFastReply() {
        val tracker = GameTracker()
        val p = Position.initial().play(Move.parse("e2e4")).play(Move.parse("e7e5"))
        assertTrue(tracker.observe(p.cells) is Observation.Rejected)
        assertEquals(Observation.Initial, tracker.observe(Position.initial().cells))
        val update = tracker.observe(p.cells) as Observation.Advanced
        assertEquals(listOf("e4", "e5"), update.moves.map { it.san })
        assertEquals(p.fen(), tracker.position!!.fen())
        assertEquals(Observation.Unchanged, tracker.observe(p.cells))
    }
    @Test fun trackerRejectsImpossibleBoardWithoutChangingState() {
        val tracker = GameTracker(); tracker.observe(Position.initial().cells)
        val bad = Position.initial().cells.copyOf(); bad[0] = '.'; bad[32] = 'R'
        assertTrue(tracker.observe(bad) is Observation.Rejected)
        assertEquals(Position.START, tracker.position!!.fen())
    }
    @Test fun repetitionIgnoresIrrelevantEp() {
        val a = Position.fromFen("4k3/8/8/4p3/8/8/8/4K3 w - e6 0 2")
        val b = Position.fromFen("4k3/8/8/4p3/8/8/8/4K3 w - - 0 2")
        assertEquals(a.repetitionKey(), b.repetitionKey())
    }
    @Test fun onlyExplicitBotProofEnablesAnalysis() {
        fun verdict(pkg: String = "com.chess", opponent: List<String> = listOf("Бот"), labels: List<String> = emptyList(), board: Boolean = true) = BotPolicy.evaluate(ScreenEvidence(pkg, opponent, labels, board)).allowed
        assertTrue(verdict()); assertTrue(verdict(opponent = listOf("Computer")))
        assertFalse(verdict(pkg = "org.browser")); assertFalse(verdict(board = false))
        assertFalse(verdict(opponent = listOf("Martin"))); assertFalse(verdict(opponent = listOf("Играть с ботами")))
        for (label in listOf("Rated", "Rapid", "Блиц", "Ежедневная партия", "Finding opponent")) assertFalse(verdict(labels = listOf(label)))
        assertFalse(verdict(opponent = emptyList(), labels = listOf("Bot")))
    }
}
