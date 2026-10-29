package ru.chesscoach.overlay.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var capture: Handler
    private lateinit var recognizer: BoardRecognizer
    private lateinit var engine: StockfishEngine
    private val analysis = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64))
    private val tracker = GameTracker()
    private lateinit var store: ReportStore
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: CoachOverlay? = null
    private var screenWidth = 0; private var screenHeight = 0
    @Volatile private var frameRequested = false
    @Volatile private var hiddenAt = 0L
    @Volatile private var closing = false
    @Volatile private var geometryValid = true
    @Volatile private var stable: CharArray? = null
    private var stableCount = 0
    private var lastEngineFen: String? = null
    private var lastEngineResult: ru.chesscoach.overlay.engine.EngineResult? = null
    private var trackedAny = false
    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            if (!frameRequested) {
                hiddenAt = SystemClock.elapsedRealtime(); frameRequested = true
                overlay?.hideForCapture(true)
            }
            if (!ChessAccessibilityService.permits()) AppState.clearAdvice(ChessAccessibilityService.reason)
            main.postDelayed(this, 800)
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
                .setContentText("Локально. Подсказки разрешены только для ботов.")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build()).build()
            startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            captureThread = HandlerThread("board-recognition").apply { start() }; capture = Handler(captureThread.looper)
            recognizer = BoardRecognizer(this); engine = StockfishEngine(this); store = ReportStore(this)
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenWidth = bounds.width(); screenHeight = bounds.height()
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            reader!!.setOnImageAvailableListener({ source -> readFrame(source) }, capture)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, consent)
            projection!!.registerCallback(projectionCallback, main)
            display = projection!!.createVirtualDisplay("ChessCoach", screenWidth, screenHeight, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, capture)
            overlay = CoachOverlay(this) { stopSelf() }
            AppState.running = true; AppState.boardRect = null
            AppState.clearAdvice("Откройте новую партию с ботом. Фигуры: Neo, доска: 2D.")
            main.post(tick); main.post(watchdog)
        } catch (e: Exception) { AppState.clearAdvice("Не удалось начать захват: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun readFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (closing || !geometryValid || !frameRequested || SystemClock.elapsedRealtime() - hiddenAt < 140) return
            frameRequested = false
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            try { processFrame(frame) }
            finally { if (frame !== padded) frame.recycle(); padded.recycle(); main.post { overlay?.hideForCapture(false) } }
        } catch (e: Exception) {
            AppState.clearAdvice("Распознавание приостановлено: ${e.message}")
            main.post { overlay?.hideForCapture(false) }
        } finally { image.close() }
    }
    private fun processFrame(frame: Bitmap) {
        val rect = recognizer.locate(frame)
        if (rect == null) {
            AppState.boardRect = null; stable = null; stableCount = 0
            ChessAccessibilityService.invalidate(recognizer.lastError); return
        }
        AppState.boardRect = rect
        main.post { overlay?.placeBelowBoard() }
        if (!ChessAccessibilityService.permits()) { stable = null; stableCount = 0; return }
        val epoch = ChessAccessibilityService.epoch
        val board = recognizer.recognize(frame, rect, AppState.whiteAtBottom)
        if (board == null) { stable = null; stableCount = 0; AppState.clearAdvice(recognizer.lastError); return }
        AppState.whiteAtBottom = board.whiteAtBottom
        if (!ChessAccessibilityService.permits() || epoch != ChessAccessibilityService.epoch) return
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
            Observation.Unchanged -> Unit
            is Observation.Rejected -> AppState.clearAdvice(update.reason)
        }
    }
    private fun valid(epoch: Long) = !closing && ChessAccessibilityService.permits() && ChessAccessibilityService.epoch == epoch
    private fun enqueueInitial(epoch: Long) {
        val p = Position.initial()
        enqueue {
            if (!valid(epoch)) return@enqueue
            val result = engine.analyze(p)
            lastEngineFen = p.fen(); lastEngineResult = result
            if (valid(epoch) && AppState.mode == CoachMode.HINTS) AppState.publish(Advice("Тренировка с ботом", result.display(), result.bestMove))
        }
    }
    private fun enqueueAnalysis(move: TrackedMove, epoch: Long) {
        val currentFen = tracker.position?.fen()
        enqueue {
            if (!valid(epoch)) return@enqueue
            val before = if (lastEngineFen == move.before.fen()) lastEngineResult!! else engine.analyze(move.before)
            if (!valid(epoch)) return@enqueue
            val after = engine.analyze(move.after)
            if (!valid(epoch)) return@enqueue
            lastEngineFen = move.after.fen(); lastEngineResult = after
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
