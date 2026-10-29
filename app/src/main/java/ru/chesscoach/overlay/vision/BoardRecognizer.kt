package ru.chesscoach.overlay.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import ru.chesscoach.overlay.chess.Position
import kotlin.math.roundToInt

data class RecognizedBoard(val rect: Rect, val cells: CharArray, val whiteAtBottom: Boolean, val confidence: Double)

/** Learns local templates from the first standard starting position. No remote assets. */
class BoardRecognizer(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val n = 28
    private val templates = mutableMapOf<Char, MutableList<IntArray>>()
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
                        val score = gridScore(small, candidate) + size * .000001
                        if (score > best) { best = score; result = candidate }
                    }
                }
            }
            // Corner colors have a plateau around grid edges. Refine against actual
            // color transitions so the template crop does not drift by several pixels.
            result?.let { coarse ->
                var fineBest = -1.0
                for (size in (coarse.width() - 5)..(coarse.width() + 5))
                    for (x in (coarse.left - 4)..(coarse.left + 4))
                        for (y in (coarse.top - 4)..(coarse.top + 4)) {
                            val test = Rect(x, y, x + size, y + size)
                            val grid = gridScore(small, test)
                            if (grid < .77) continue
                            val score = grid * .7 + alignmentScore(small, test) * .3
                            if (score > fineBest) { fineBest = score; result = test }
                        }
            }
            val rect = result?.let { Rect((it.left / scale).roundToInt(), (it.top / scale).roundToInt(), (it.right / scale).roundToInt(), (it.bottom / scale).roundToInt()) }
            cached = rect
            if (rect == null) lastError = "Доска не найдена: откройте новую стандартную партию и 2D-доску"
            return rect
        } finally { if (small !== frame) small.recycle() }
    }
    private fun alignmentScore(image: Bitmap, rect: Rect): Double {
        val cell = rect.width() / 8.0
        var fit = 0; var total = 0
        for (boundary in 1..7) for (sample in listOf(0, 2, 5, 7)) {
            val bx = (rect.left + boundary * cell).roundToInt()
            val sy = (rect.top + (sample + .10) * cell).toInt()
            val by = (rect.top + boundary * cell).roundToInt()
            val sx = (rect.left + (sample + .10) * cell).toInt()
            if (bx > 0 && bx + 1 < image.width && sy in 0 until image.height) {
                if (distance(image.getPixel(bx - 1, sy), image.getPixel(bx + 1, sy)) > 2200) fit++
                total++
            }
            if (by > 0 && by + 1 < image.height && sx in 0 until image.width) {
                if (distance(image.getPixel(sx, by - 1), image.getPixel(sx, by + 1)) > 2200) fit++
                total++
            }
        }
        return if (total > 0) fit.toDouble() / total else 0.0
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
            val patches = (0..63).map { i -> IntArray(n * n).also { normalized.getPixels(it, 0, n, i % 8 * n, i / 8 * n, n, n) } }
            val backgrounds = patches.map { pixels -> medianColor(listOf(pixels[2 * n + 2], pixels[2 * n + n - 3], pixels[(n - 3) * n + 2], pixels[(n - 3) * n + n - 3])) }
            if (templates.isEmpty()) return calibrate(rect, patches, backgrounds)
            val screen = CharArray(64); var minMargin = 1.0
            for (row in 0..7) for (col in 0..7) {
                val pixels = patches[row * 8 + col]
                val bg = backgrounds[row * 8 + col]
                val costs = mutableListOf('.' to pixels.indices.sumOf { distance(pixels[it], bg).toDouble() } / pixels.size)
                for ((piece, variants) in templates) {
                    costs += piece to variants.minOf { templateCost(pixels, bg, it) }
                }
                costs.sortBy { it.second }
                val first = costs[0]; val second = costs[1]
                val margin = (second.second - first.second) / maxOf(second.second, 1.0)
                if (first.second > 2600 || (first.first != '.' && (margin < .11 || second.second - first.second < 85))) {
                    lastError = "Фигуры не распознаны уверенно: дождитесь конца анимации или начните новую тренировку"
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
    private fun templateCost(pixels: IntArray, bg: Int, template: IntArray): Double {
        var error = 0.0
        for (i in pixels.indices) {
            val alpha = Color.alpha(template[i]) / 255.0
            val r = (Color.red(template[i]) * alpha + Color.red(bg) * (1 - alpha)).roundToInt()
            val g = (Color.green(template[i]) * alpha + Color.green(bg) * (1 - alpha)).roundToInt()
            val b = (Color.blue(template[i]) * alpha + Color.blue(bg) * (1 - alpha)).roundToInt()
            error += distance(pixels[i], Color.rgb(r, g, b))
        }
        return error / pixels.size
    }
    private fun calibrate(rect: Rect, patches: List<IntArray>, backgrounds: List<Int>): RecognizedBoard? {
        val masks = patches.mapIndexed { i, pixels -> foregroundMask(pixels, backgrounds[i]) }
        val occupied = masks.map { it.count { foreground -> foreground } }
        val validLayout = (0..63).all { i -> if (i / 8 in listOf(0, 1, 6, 7)) occupied[i] in 45..650 else occupied[i] < 28 }
        if (!validLayout) {
            android.util.Log.d("BoardRecognition", "Calibration occupancy: $occupied")
            lastError = "Для обучения нужна полная начальная расстановка до первого хода"; return null
        }
        // Pawns must repeat the same shape; a menu, 3D board or arbitrary position
        // cannot simply be labelled as the initial chess position.
        for (row in listOf(1, 6)) {
            val reference = masks[row * 8]
            for (col in 1..7) {
                val other = masks[row * 8 + col]
                val intersection = reference.indices.count { reference[it] && other[it] }
                val union = reference.indices.count { reference[it] || other[it] }
                if (intersection.toDouble() / maxOf(union, 1) < .68) {
                    android.util.Log.d("BoardRecognition", "Pawn mismatch: row=$row col=$col intersection=$intersection union=$union")
                    lastError = "Неустойчивая начальная расстановка; отключите анимацию и метки ходов"; return null
                }
            }
        }
        fun brightness(row: Int): Double = (0..7).map { col ->
            val i = row * 8 + col
            patches[i].filterIndexed { index, _ -> masks[i][index] }.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3.0 }.average()
        }.average()
        val top = brightness(1); val bottom = brightness(6)
        if (kotlin.math.abs(top - bottom) < 35) { lastError = "Цвет фигур не различим; выберите контрастные светлые и тёмные фигуры"; return null }
        val whiteBottom = bottom > top
        val initial = Position.initial().cells
        for (screenIndex in 0..63) {
            if (screenIndex / 8 !in listOf(0, 1, 6, 7)) continue
            val boardIndex = if (whiteBottom) (7 - screenIndex / 8) * 8 + screenIndex % 8 else (screenIndex / 8) * 8 + 7 - screenIndex % 8
            val piece = initial[boardIndex]
            val bg = backgrounds[screenIndex]
            val template = patches[screenIndex].mapIndexed { index, pixel ->
                if (masks[screenIndex][index]) pixel or (0xff shl 24) else Color.TRANSPARENT
            }.toIntArray()
            templates.getOrPut(piece) { mutableListOf() }.add(template)
        }
        // Reject styles whose shapes do not separate all six piece classes.
        for (i in 0..63) {
            if (i / 8 !in listOf(0, 1, 6, 7)) continue
            val index = if (whiteBottom) (7 - i / 8) * 8 + i % 8 else i / 8 * 8 + 7 - i % 8
            val expected = initial[index]
            val scores = templates.map { (piece, variants) -> piece to variants.minOf { templateCost(patches[i], backgrounds[i], it) } }.sortedBy { it.second }
            if (scores[0].first != expected || scores[1].second - scores[0].second < 85) {
                android.util.Log.d("BoardRecognition", "Template separation: expected=$expected scores=${scores.take(3)}")
                templates.clear(); lastError = "Набор фигур не удалось различить. Выберите стандартный контрастный набор 2D."; return null
            }
        }
        lastError = "Фигуры изучены по начальной расстановке"
        return RecognizedBoard(rect, initial.copyOf(), whiteBottom, 1.0)
    }
    private fun foregroundMask(pixels: IntArray, bg: Int): List<Boolean> {
        // Flood from the square boundary. A white piece can have almost the same
        // fill as a light square; its closed dark contour still encloses the fill.
        val reached = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size); var head = 0; var tail = 0
        fun add(index: Int) {
            if (!reached[index] && distance(pixels[index], bg) < 1500) { reached[index] = true; queue[tail++] = index }
        }
        for (i in 0 until n) { add(i); add((n - 1) * n + i); add(i * n); add(i * n + n - 1) }
        while (head < tail) {
            val i = queue[head++]
            if (i % n > 0) add(i - 1)
            if (i % n < n - 1) add(i + 1)
            if (i >= n) add(i - n)
            if (i < n * (n - 1)) add(i + n)
        }
        return reached.map { !it }
    }
    fun reset() { cached = null; templates.clear() }
}
