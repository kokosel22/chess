package ru.chesscoach.overlay.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import ru.chesscoach.overlay.chess.Position
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecognizedBoard(val rect: Rect, val cells: CharArray, val whiteAtBottom: Boolean, val confidence: Double)

/** Offline template recognition. Unknown piece themes, animation and occlusion fail closed. */
class BoardRecognizer(context: Context) {
    private val n = 28
    private val templates: Map<Char, IntArray> = "PNBRQKpnbrqk".associateWith { piece ->
        val name = (if (piece.isUpperCase()) "w" else "b") + piece.lowercaseChar()
        context.assets.open("pieces/$name.png").use { input ->
            val original = requireNotNull(BitmapFactory.decodeStream(input))
            val scaled = Bitmap.createScaledBitmap(original, n, n, true)
            val pixels = IntArray(n * n); scaled.getPixels(pixels, 0, n, 0, 0, n, n)
            if (scaled !== original) scaled.recycle(); original.recycle(); pixels
        }
    }
    private var cached: Rect? = null
    var lastError = "Ищу доску"; private set
    fun locate(frame: Bitmap): Rect? {
        val scale = minOf(1.0, 360.0 / frame.width)
        val small = Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), false)
        try {
            cached?.let { r ->
                val test = Rect((r.left * scale).roundToInt(), (r.top * scale).roundToInt(), (r.right * scale).roundToInt(), (r.bottom * scale).roundToInt())
                if (gridScore(small, test) > 0.77) return r
            }
            var best = 0.80; var result: Rect? = null
            val w = small.width
            for (size in (w * 0.70).toInt()..w step 4) {
                for (x in listOf(0, (w - size) / 2, w - size).distinct()) {
                    for (y in 0..(small.height - size) step 4) {
                        val candidate = Rect(x, y, x + size, y + size)
                        val score = gridScore(small, candidate)
                        if (score > best) { best = score; result = candidate }
                    }
                }
            }
            val rect = result?.let { Rect((it.left / scale).roundToInt(), (it.top / scale).roundToInt(), (it.right / scale).roundToInt(), (it.bottom / scale).roundToInt()) }
            cached = rect
            if (rect == null) lastError = "Доска не найдена: откройте новую партию, 2D-доску и фигуры Neo"
            return rect
        } finally { if (small !== frame) small.recycle() }
    }
    private fun distance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db
    }
    private fun medianColor(colors: List<Int>): Int = colors.minBy { a -> colors.sumOf { b -> distance(a, b).toLong() } }
    private fun gridScore(image: Bitmap, rect: Rect): Double {
        if (rect.left < 0 || rect.top < 0 || rect.right > image.width || rect.bottom > image.height || rect.width() < 80) return 0.0
        val cell = rect.width() / 8.0
        val colors = (0..63).map { k ->
            image.getPixel((rect.left + (k % 8 + .10) * cell).toInt(), (rect.top + (k / 8 + .10) * cell).toInt())
        }
        val a = medianColor(colors.filterIndexed { i, _ -> (i / 8 + i % 8) % 2 == 0 })
        val b = medianColor(colors.filterIndexed { i, _ -> (i / 8 + i % 8) % 2 == 1 })
        if (distance(a, b) < 2200) return 0.0
        var fits = 0
        for (i in 0..63) {
            val expected = if ((i / 8 + i % 8) % 2 == 0) a else b
            if (distance(colors[i], expected) < 1300) fits++
            val x = (rect.left + (i % 8 + .90) * cell).toInt().coerceAtMost(image.width - 1)
            val y = (rect.top + (i / 8 + .10) * cell).toInt().coerceAtMost(image.height - 1)
            if (distance(image.getPixel(x, y), expected) < 1300) fits++
        }
        return fits / 128.0
    }
    fun recognize(frame: Bitmap, rect: Rect, previousWhiteAtBottom: Boolean): RecognizedBoard? {
        if (rect.right > frame.width || rect.bottom > frame.height) return null
        val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        val normalized = Bitmap.createScaledBitmap(crop, n * 8, n * 8, true)
        try {
            val screen = CharArray(64); var minMargin = 1.0
            for (row in 0..7) for (col in 0..7) {
                val pixels = IntArray(n * n)
                normalized.getPixels(pixels, 0, n, col * n, row * n, n, n)
                val bg = medianColor(listOf(pixels[2 * n + 2], pixels[2 * n + n - 3], pixels[(n - 3) * n + 2], pixels[(n - 3) * n + n - 3]))
                val costs = mutableListOf('.' to pixels.indices.sumOf { distance(pixels[it], bg).toDouble() } / pixels.size)
                for ((piece, template) in templates) {
                    var error = 0.0
                    for (i in pixels.indices) {
                        val alpha = Color.alpha(template[i]) / 255.0
                        val r = (Color.red(template[i]) * alpha + Color.red(bg) * (1 - alpha)).roundToInt()
                        val g = (Color.green(template[i]) * alpha + Color.green(bg) * (1 - alpha)).roundToInt()
                        val b = (Color.blue(template[i]) * alpha + Color.blue(bg) * (1 - alpha)).roundToInt()
                        error += distance(pixels[i], Color.rgb(r, g, b))
                    }
                    costs += piece to error / pixels.size
                }
                costs.sortBy { it.second }
                val first = costs[0]; val second = costs[1]
                val margin = (second.second - first.second) / maxOf(second.second, 1.0)
                if (first.second > 2600 || (first.first != '.' && (margin < .11 || second.second - first.second < 85))) {
                    lastError = "Фигуры не распознаны уверенно: дождитесь конца анимации; выберите тему Neo"
                    return null
                }
                screen[row * 8 + col] = first.first
                minMargin = minOf(minMargin, margin)
            }
            fun oriented(whiteBottom: Boolean): CharArray = CharArray(64) { i ->
                if (whiteBottom) screen[(7 - i / 8) * 8 + i % 8] else screen[(i / 8) * 8 + 7 - i % 8]
            }
            val initial = Position.initial().cells
            val orientation = when {
                oriented(true).contentEquals(initial) -> true
                oriented(false).contentEquals(initial) -> false
                else -> previousWhiteAtBottom
            }
            val cells = oriented(orientation)
            if (cells.count { it == 'K' } != 1 || cells.count { it == 'k' } != 1) { lastError = "Не найдены оба короля"; return null }
            lastError = "Доска распознана"
            return RecognizedBoard(rect, cells, orientation, minMargin)
        } finally { if (normalized !== crop) normalized.recycle(); crop.recycle() }
    }
    fun reset() { cached = null }
}
