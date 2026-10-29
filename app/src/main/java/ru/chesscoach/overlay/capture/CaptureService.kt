package ru.chesscoach.overlay.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import ru.chesscoach.overlay.*
import ru.chesscoach.overlay.chess.*
import ru.chesscoach.overlay.engine.StockfishEngine
import ru.chesscoach.overlay.safety.ChessAccessibilityService
import ru.chesscoach.overlay.storage.ReportStore
import ru.chesscoach.overlay.vision.BoardRecognizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var capture: Handler
    private lateinit var recognizer: BoardRecognizer
    private lateinit var engine: StockfishEngine
    // Persist all moves, but keep only the latest waiting live analysis.
    private val analysis = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())
    private val tracker = GameTracker()
    private lateinit var store: ReportStore
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: CoachOverlay? = null
    private var screenWidth = 0; private var screenHeight = 0
    private var paddedFrame: Bitmap? = null
    private var captureFrame: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var latestImage: android.media.Image? = null
    private val consumeRequestedFrame = Runnable { consumeFrame() }
    @Volatile private var frameRequested = false
    @Volatile private var hiddenAt = 0L
    @Volatile private var closing = false
    @Volatile private var geometryValid = true
    @Volatile private var stable: CharArray? = null
    private var stableCount = 0
    @Volatile private var cachedAnalysis: Pair<String, ru.chesscoach.overlay.engine.EngineResult>? = null
    private var trackedAny = false
    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            if (!android.provider.Settings.canDrawOverlays(this@CaptureService)) {
                AppState.clearAdvice("Разрешение показа поверх окон отозвано"); stopSelf(); return
            }
            if (!frameRequested) {
                hiddenAt = SystemClock.elapsedRealtime(); frameRequested = true
                overlay?.hideForCapture(true)
            }
            capture.removeCallbacks(consumeRequestedFrame)
            capture.postDelayed(consumeRequestedFrame, 110)
            if (!ChessAccessibilityService.permits()) AppState.clearAdvice(ChessAccessibilityService.reason)
            main.postDelayed(this, 450)
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (closing) return
            if (!ChessAccessibilityService.permits()) AppState.clearAdvice(ChessAccessibilityService.reason)
            main.postDelayed(this, 200)
        }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { AppState.clearAdvice("Захват экрана остановлен системой"); stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            // This overlay needs display coordinates, not an isolated app-window capture.
            geometryValid = kotlin.math.abs(width.toDouble() / height - screenWidth.toDouble() / screenHeight) < .025
            if (!geometryValid) {
                AppState.boardRect = null; ChessAccessibilityService.invalidate("Выберите захват всего экрана"); stopSelf()
            }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) ChessAccessibilityService.invalidate("Chess.com не виден на экране")
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Тренировка и захват экрана", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null || closing) return START_NOT_STICKY
        val consent = intent?.getParcelableExtra("consent", Intent::class.java)
        val result = intent?.getIntExtra("result", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (consent == null || result != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        try {
            val stopIntent = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_coach)
                .setContentTitle("Шахматный тренер: захват экрана")
                .setContentText("Локально. Подсказки скрываются при метке «человек».")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build()).build()
            startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            captureThread = HandlerThread("board-recognition").apply { start() }; capture = Handler(captureThread.looper)
            recognizer = BoardRecognizer(this); engine = StockfishEngine(this); store = ReportStore(this)
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenWidth = bounds.width(); screenHeight = bounds.height()
            val captureWidth = minOf(screenWidth, 720)
            val captureHeight = (screenHeight.toDouble() * captureWidth / screenWidth).roundToInt()
            reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
            reader!!.setOnImageAvailableListener({ source -> readFrame(source) }, capture)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, consent)
            projection!!.registerCallback(projectionCallback, main)
            display = projection!!.createVirtualDisplay("ChessCoach", captureWidth, captureHeight, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, capture)
            overlay = CoachOverlay(this) { stopSelf() }
            AppState.running = true; AppState.boardRect = null; AppState.capturedFrames = 0
            AppState.clearAdvice("Откройте новую стандартную партию с ботом. Доска: 2D; начальную расстановку оставьте до подтверждения.")
            main.post(tick); main.post(watchdog)
        } catch (e: Exception) { AppState.clearAdvice("Не удалось начать захват: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun readFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        if (closing) { image.close(); return }
        // Retain the most recent buffer without copying every display refresh.
        // Holding one image still leaves two slots for acquireLatestImage().
        latestImage?.close()
        latestImage = image
        if (frameRequested && SystemClock.elapsedRealtime() - hiddenAt >= 90) consumeFrame()
    }
    private fun consumeFrame() {
        if (closing || !geometryValid || !frameRequested) return
        if (SystemClock.elapsedRealtime() - hiddenAt < 90) return
        val image = latestImage
        latestImage = null
        if (image == null && captureFrame == null) return
        frameRequested = false
        try {
            if (image != null) {
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                if (paddedFrame?.width != rowPixels || paddedFrame?.height != image.height) {
                    if (captureFrame !== paddedFrame) captureFrame?.recycle()
                    paddedFrame?.recycle()
                    paddedFrame = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
                    captureFrame = if (rowPixels == image.width) paddedFrame else Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    captureCanvas = if (captureFrame === paddedFrame) null else Canvas(captureFrame!!)
                }
                paddedFrame!!.copyPixelsFromBuffer(plane.buffer)
                captureCanvas?.drawBitmap(paddedFrame!!, 0f, 0f, null)
                AppState.capturedFrames++
            }
            // A static display may emit no new buffers. Reuse the last frame for
            // the stability interval; future changed buffers are retained above.
            captureFrame?.let { processFrame(it) }
        } catch (e: Exception) {
            AppState.clearAdvice("Распознавание приостановлено: ${e.message}")
        } finally {
            image?.close()
            main.post { overlay?.hideForCapture(false) }
        }
    }
    private fun processFrame(frame: Bitmap) {
        if (closing) return
        if (!ChessAccessibilityService.chessVisible) return
        val rect = recognizer.locate(frame)
        if (rect == null) {
            AppState.boardRect = null; stable = null; stableCount = 0
            ChessAccessibilityService.invalidate(recognizer.lastError); return
        }
        AppState.boardRect = Rect(
            (rect.left.toDouble() * screenWidth / frame.width).roundToInt(),
            (rect.top.toDouble() * screenHeight / frame.height).roundToInt(),
            (rect.right.toDouble() * screenWidth / frame.width).roundToInt(),
            (rect.bottom.toDouble() * screenHeight / frame.height).roundToInt())
        main.post { overlay?.placeBelowBoard() }
        if (!ChessAccessibilityService.permits()) { stable = null; stableCount = 0; return }
        val epoch = ChessAccessibilityService.epoch
        val board = recognizer.recognize(frame, rect, AppState.whiteAtBottom)
        if (board == null) { stable = null; stableCount = 0; AppState.clearAdvice(recognizer.lastError); return }
        AppState.whiteAtBottom = board.whiteAtBottom
        if (closing || !ChessAccessibilityService.permits() || epoch != ChessAccessibilityService.epoch) return
        if (stable?.contentEquals(board.cells) == true) stableCount++ else { stable = board.cells.copyOf(); stableCount = 1; AppState.clearAdvice("Проверяю устойчивость позиции") }
        if (stableCount < 2) return
        when (val update = tracker.observe(board.cells)) {
            Observation.Initial -> {
                trackedAny = true
                AppState.clearAdvice(if (AppState.mode == CoachMode.REVIEW) "Запись партии. Отчёт после остановки." else "Начальная позиция подтверждена. Сделайте ход.")
                if (AppState.mode == CoachMode.HINTS) enqueueInitial(epoch)
            }
            is Observation.Advanced -> {
                for (move in update.moves) { store.append(move); enqueueAnalysis(move, epoch) }
            }
            Observation.Unchanged -> {
                val cached = cachedAnalysis
                if (AppState.mode == CoachMode.HINTS && cached?.first == tracker.position?.fen() && valid(epoch))
                    AppState.publish(Advice("Текущая позиция", cached!!.second.display(), cached.second.bestMove))
            }
            is Observation.Rejected -> AppState.clearAdvice(update.reason)
        }
    }
    private fun valid(epoch: Long) = !closing && ChessAccessibilityService.permits() && ChessAccessibilityService.epoch == epoch
    private fun enqueueInitial(epoch: Long) {
        val p = Position.initial()
        enqueue {
            if (!valid(epoch)) return@enqueue
            val result = engine.analyze(p, 350)
            cachedAnalysis = p.fen() to result
            if (valid(epoch) && AppState.mode == CoachMode.HINTS && stable?.contentEquals(p.cells) == true)
                AppState.publish(Advice("Текущая позиция", result.display(), result.bestMove))
        }
    }
    private fun enqueueAnalysis(move: TrackedMove, epoch: Long) {
        if (AppState.mode == CoachMode.REVIEW) {
            AppState.clearAdvice("Партия сохраняется. Движок запустится при разборе после остановки."); return
        }
        val currentFen = tracker.position?.fen()
        enqueue {
            if (!valid(epoch)) return@enqueue
            val cached = cachedAnalysis
            if (AppState.mode == CoachMode.HINTS) {
                val result = engine.analyze(move.after, 350)
                if (!valid(epoch)) return@enqueue
                cachedAnalysis = move.after.fen() to result
                if (cached?.first == move.before.fen()) store.evaluate(move.move.uci(), move.before.fen(), cached.second, result)
                if (currentFen == move.after.fen() && stable?.contentEquals(move.after.cells) == true)
                    AppState.publish(Advice("Текущая позиция", result.display(), result.bestMove))
                return@enqueue
            }
            val before = if (cached?.first == move.before.fen()) cached.second else engine.analyze(move.before, 450)
            if (!valid(epoch)) return@enqueue
            val after = engine.analyze(move.after, 450)
            if (!valid(epoch)) return@enqueue
            cachedAnalysis = move.after.fen() to after
            val loss = store.evaluate(move.move.uci(), move.before.fen(), before, after)
            // A finished analysis may not describe the current board anymore.
            if (AppState.mode == CoachMode.REVIEW) { AppState.clearAdvice("Партия сохраняется. Отчёт будет доступен после остановки."); return@enqueue }
            val isPlayer = move.before.white == AppState.playerWhite
            val message = if (isPlayer) when {
                loss >= 200 -> "Зевок ${move.san}: потеря ${formatLoss(loss)}"
                loss >= 100 -> "Ошибка ${move.san}: потеря ${formatLoss(loss)}"
                loss >= 50 -> "Неточность ${move.san}: потеря ${formatLoss(loss)}"
                else -> "${move.san}: хороший ход"
            } else "Бот сыграл ${move.san}"
            val stillCurrent = AppState.boardRect != null && stable?.contentEquals(move.after.cells) == true
            if (valid(epoch)) {
                if (AppState.mode == CoachMode.HINTS && stillCurrent && currentFen == move.after.fen()) AppState.publish(Advice(message, after.display(), after.bestMove))
                else if (isPlayer) AppState.publish(Advice(message))
            }
        }
    }
    private fun formatLoss(cp: Int) = String.format(java.util.Locale.US, "%.2f пешки", cp / 100.0)
    private fun enqueue(block: () -> Unit) {
        try {
            analysis.execute { try { block() } catch (e: Exception) { if (!closing) AppState.clearAdvice("Анализ недоступен: ${e.message}. Партия сохраняется.") } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { AppState.clearAdvice("Очередь анализа заполнена. Ходы сохранены для отчёта.") }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppState.clearAdvice("Ориентация изменилась. Запустите захват заново."); stopSelf()
    }
    override fun onDestroy() {
        closing = true; AppState.running = false; AppState.boardRect = null
        main.removeCallbacksAndMessages(null); overlay?.close(); overlay = null
        analysis.shutdownNow(); if (::engine.isInitialized) engine.close()
        display?.release(); display = null
        projection?.unregisterCallback(projectionCallback); projection?.stop(); projection = null
        reader?.setOnImageAvailableListener(null, null)
        if (::capture.isInitialized) {
            capture.post {
                runCatching { if (trackedAny) store.finish(tracker, "Захват остановлен. Неоценённые ходы можно разобрать в отчёте.") }
                if (captureFrame !== paddedFrame) captureFrame?.recycle()
                paddedFrame?.recycle(); captureFrame = null; paddedFrame = null; captureCanvas = null
                latestImage?.close(); latestImage = null
                reader?.close(); reader = null
            }
            captureThread.quitSafely()
        } else { reader?.close(); reader = null }
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppState.clearAdvice("Захват остановлен. Партия сохранена в разделе «Партии».")
        super.onDestroy()
    }
    companion object { const val STOP = "ru.chesscoach.STOP"; private const val CHANNEL = "chess-coach-capture" }
}
