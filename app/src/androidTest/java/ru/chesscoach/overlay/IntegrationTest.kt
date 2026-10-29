package ru.chesscoach.overlay

import android.content.Intent
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chesscoach.overlay.chess.*
import ru.chesscoach.overlay.engine.StockfishEngine
import ru.chesscoach.overlay.storage.ReportStore
import ru.chesscoach.overlay.vision.BoardRecognizer
import java.io.File

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun bundledStockfishRunsOfflineAndReturnsLegalMove() {
        StockfishEngine(context).use { engine ->
            val p = Position.initial()
            val result = engine.analyze(p, 300)
            assertTrue(result.depth > 0); assertTrue(result.bestMove in p.legalMoves())
            val mate = Position.fromFen("7k/8/5KQ1/8/8/8/8/8 w - - 0 1")
            val answer = engine.analyze(mate, 500)
            assertEquals("1-0", mate.play(answer.bestMove!!).outcome())
            val terminal = engine.analyze(mate.play(answer.bestMove!!), 100)
            assertTrue(terminal.whiteCp > 29000); assertNull(terminal.bestMove)
        }
    }
    private fun board(position: Position, whiteBottom: Boolean): Bitmap {
        val image = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.rgb(38, 37, 34))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val top = 240; val cell = 90
        for (r in 0..7) for (f in 0..7) {
            paint.color = if ((r + f) % 2 == 0) Color.rgb(238, 238, 210) else Color.rgb(118, 150, 86)
            val rect = Rect(f * cell, top + r * cell, (f + 1) * cell, top + (r + 1) * cell)
            canvas.drawRect(rect, paint)
            val index = if (whiteBottom) (7 - r) * 8 + f else r * 8 + 7 - f
            val piece = position.cells[index]
            if (piece != '.') {
                val glyph = when (piece.lowercaseChar()) { 'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; else -> "♟" }
                paint.textSize = cell * .82f; paint.typeface = Typeface.create("serif", Typeface.NORMAL); paint.textAlign = Paint.Align.CENTER
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(25, 25, 25)
                val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2
                canvas.drawText(glyph, rect.exactCenterX(), y, paint)
                paint.style = Paint.Style.FILL; paint.color = if (piece.isUpperCase()) Color.rgb(248, 248, 240) else Color.rgb(35, 35, 35)
                canvas.drawText(glyph, rect.exactCenterX(), y, paint)
            }
        }
        return image
    }
    @Test fun findsBoardAndRecognizesPiecesBothOrientations() {
        val recognizer = BoardRecognizer(context)
        for (orientation in listOf(true, false)) {
            val image = board(Position.initial(), orientation)
            val rect = recognizer.locate(image); assertNotNull(rect)
            assertTrue(kotlin.math.abs(rect!!.top - 240) <= 8)
            val result = recognizer.recognize(image, rect, true)
            assertNotNull(recognizer.lastError, result); assertArrayEquals(Position.initial().cells, result!!.cells); assertEquals(orientation, result.whiteAtBottom)
            image.recycle(); recognizer.reset()
        }
    }
    @Test fun recognizesMovedPositionWithRightsPreservedByTracker() {
        val tracker = GameTracker(); tracker.observe(Position.initial().cells)
        val next = Position.initial().play(Move.parse("g1f3"))
        val image = board(next, true); val recognizer = BoardRecognizer(context)
        val initial = board(Position.initial(), true)
        File(context.getExternalFilesDir(null), "fixture-initial.png").outputStream().use { initial.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val calibration = recognizer.recognize(initial, Rect(0, 240, 720, 960), true)
        assertNotNull(recognizer.lastError, calibration); initial.recycle()
        val unchanged = board(Position.initial(), true)
        repeat(4) { assertNotNull(recognizer.recognize(unchanged, Rect(0, 240, 720, 960), true)) }
        assertEquals("Unchanged frames must reuse recognition", 1, recognizer.fullRecognitionPasses)
        unchanged.recycle()
        val result = recognizer.recognize(image, Rect(0, 240, 720, 960), true)!!
        assertEquals("Changed pieces must trigger fresh recognition", 2, recognizer.fullRecognitionPasses)
        assertTrue(tracker.observe(result.cells) is Observation.Advanced)
        assertEquals(next.fen(), tracker.position!!.fen()); image.recycle()
    }
    @Test fun refusesUncertainOrCoveredBoard() {
        val image = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLACK); assertNull(BoardRecognizer(context).locate(image)); image.recycle()
    }
    @Test fun writesAtomicReportAndPgn() {
        val tracker = GameTracker(); tracker.observe(Position.initial().cells)
        val after = Position.initial().play(Move.parse("e2e4"))
        val update = tracker.observe(after.cells) as Observation.Advanced
        val store = ReportStore(context); store.append(update.moves.single()); store.finish(tracker, "Тест")
        val file = File(context.filesDir, "games/${store.id}.json")
        try {
            val game = ReportStore.load(file)
            assertTrue(ReportStore.pgn(game).contains("1. e4 *"))
            assertTrue(ReportStore.report(game).contains("без оценки"))
        } finally { file.delete() }
    }
    @Test fun launchesRussianHomeAndSavesScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "home.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        instrumentation.runOnMainSync { activity.finish() }
    }
}
