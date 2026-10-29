package ru.chesscoach.overlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONObject
import ru.chesscoach.overlay.capture.CaptureService
import ru.chesscoach.overlay.chess.Move
import ru.chesscoach.overlay.chess.Position
import ru.chesscoach.overlay.engine.StockfishEngine
import ru.chesscoach.overlay.safety.ChessAccessibilityService
import ru.chesscoach.overlay.storage.ReportStore
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val background = Color.rgb(242, 247, 245)
    private val ink = Color.rgb(20, 48, 44)
    private val muted = Color.rgb(74, 99, 92)
    private val accent = Color.rgb(15, 118, 110)
    private val prefs by lazy { getSharedPreferences("coach", MODE_PRIVATE) }
    private lateinit var page: LinearLayout
    private var status: TextView? = null
    private var exportText: String? = null
    private var displayedReport: File? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var reviewing = false
    private var reviewEngine: StockfishEngine? = null
    private val refresh: () -> Unit = { status?.text = if (AppState.running) "Захват включён\n${AppState.advice.message}" else AppState.advice.message }
    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppState.running) {
            AppState.mode = CoachMode.entries.getOrElse(prefs.getInt("mode", 0)) { CoachMode.COACH }
            AppState.playerWhite = prefs.getBoolean("white", true)
        }
        exportText = savedInstanceState?.getString("export")
        renderHome()
        AppState.listeners.add(refresh)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("export", exportText); super.onSaveInstanceState(outState) }
    private fun newPage(title: String, subtitle: String) {
        status = null
        val scroll = ScrollView(this).apply { setBackgroundColor(background); isFillViewport = true }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        scroll.addView(page)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll); scroll.requestApplyInsets()
        label("CHESS COACH / OFFLINE", 12, muted).apply { letterSpacing = .14f }
        label(title, 32, ink, true)
        label(subtitle, 16, muted)
        gap(16)
    }
    private fun label(value: String, size: Int = 16, color: Int = ink, bold: Boolean = false, target: LinearLayout = page): TextView {
        return TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
            if (bold) { setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true }
            target.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun gap(size: Int = 8) { page.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun card(title: String, body: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        }
        page.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        label(title, 18, ink, true, box); label(body, 16, muted, target = box)
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit): Button {
        return Button(this).apply {
            text = title; isAllCaps = false; textSize = 16f; minHeight = dp(52)
            setTextColor(if (primary) Color.WHITE else accent)
            if (primary) backgroundTintList = ColorStateList.valueOf(accent)
            page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            setOnClickListener { action() }
        }
    }
    private fun renderHome() {
        displayedReport = null
        newPage("Ваш ход.\nУчимся вместе.", "Локальный тренер для партий с компьютерными ботами Chess.com.")
        card("Сначала — безопасная тренировка", "Только официальное приложение, новая партия, 2D-доска и фигуры Neo. При неизвестном режиме движок отключается.")
        label("Режим тренировки", 20, ink, true)
        val modes = RadioGroup(this)
        val descriptions = listOf("Тренер — замечания после хода", "Подсказки — оценка и стрелка (только бот)", "Разбор — запись и отчёт после партии")
        CoachMode.entries.forEachIndexed { index, mode ->
            modes.addView(RadioButton(this).apply {
                id = 100 + index; text = descriptions[index]; textSize = 16f; setTextColor(ink)
                minHeight = dp(52); isEnabled = !AppState.running
            })
        }
        modes.check(100 + AppState.mode.ordinal)
        modes.setOnCheckedChangeListener { _, checked ->
            AppState.mode = CoachMode.entries[checked - 100]; prefs.edit().putInt("mode", checked - 100).apply()
        }
        page.addView(modes); gap()
        val side = Switch(this).apply {
            text = "Я играю белыми"; isChecked = AppState.playerWhite; textSize = 16f; minHeight = dp(52); setTextColor(ink)
            isEnabled = !AppState.running
            setOnCheckedChangeListener { _, checked -> AppState.playerWhite = checked; prefs.edit().putBoolean("white", checked).apply() }
        }
        page.addView(side); gap()
        label("Подготовка", 20, ink, true)
        button("1. Разрешить поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. Включить проверку режима игры") {
            AlertDialog.Builder(this).setTitle("Проверка: только боты")
                .setMessage("В специальных возможностях включите «Защита: только боты Chess.com». Служба проверяет активное приложение и метку бота у соперника. Ничего не нажимает и не передаёт. Без явной метки анализ останется заблокирован.\n\nЕсли Android запретит включение: сведения о приложении → меню ⋮ → Разрешить ограниченные настройки.")
                .setPositiveButton("Открыть настройки") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton("Отмена", null).show()
        }
        status = label(AppState.advice.message, 16, accent)
        status?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        button(if (AppState.running) "Захват уже включён" else "Начать тренировку", true) { startTraining() }.isEnabled = !AppState.running
        button("Остановить и сохранить партию") {
            if (AppState.running) { stopService(Intent(this, CaptureService::class.java)); page.postDelayed({ renderHome() }, 300) }
            else toast("Захват уже остановлен")
        }
        button("Сохранённые партии и отчёты") { renderGames() }
        button("Настройка Xiaomi и помощь") { renderHelp() }
        label("Без интернета · без root · Stockfish 17.1\nЭкраны не сохраняются. Партии хранятся только на устройстве.", 14, muted)
    }
    private fun startTraining() {
        if (AppState.running || reviewing) { toast("Сначала завершите текущий захват или разбор"); return }
        if (!Settings.canDrawOverlays(this)) { toast("Сначала разрешите показ поверх других окон"); return }
        if (ChessAccessibilityService.instance == null) { toast("Сначала включите проверку режима в специальных возможностях"); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        AlertDialog.Builder(this).setTitle("Захват экрана с вашего разрешения")
            .setMessage("Весь экран обрабатывается только на телефоне. Скриншоты и переписка не сохраняются. Выберите «Весь экран», затем откройте Chess.com и начните новую партию с ботом. Повернуть телефон можно после остановки захвата.")
            .setPositiveButton("Продолжить") { _, _ ->
                val manager = getSystemService(MediaProjectionManager::class.java)
                val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 7)
            }.setNegativeButton("Отмена", null).show()
    }
    @Deprecated("Platform activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            try {
                startForegroundService(Intent(this, CaptureService::class.java).putExtra("result", resultCode).putExtra("consent", data))
                val chess = packageManager.getLaunchIntentForPackage("com.chess")
                if (chess != null) startActivity(chess) else toast("Установите официальное приложение Chess.com")
                renderHome()
            } catch (e: Exception) { toast("Не удалось запустить захват: ${e.message}") }
        }
        if (requestCode == 8 && resultCode == RESULT_OK && data?.data != null) {
            runCatching { contentResolver.openOutputStream(data.data!!)?.use { it.write(exportText.orEmpty().toByteArray(Charsets.UTF_8)) } ?: error("Файл недоступен") }
                .onSuccess { toast("Файл сохранён") }.onFailure { toast("Не удалось сохранить: ${it.message}") }
        }
    }
    private fun renderGames() {
        newPage("Ваши партии", "История ходов, ошибки и варианты Stockfish. Всё хранится на телефоне.")
        button("Вернуться к тренировке") { renderHome() }
        val games = ReportStore.list(this)
        if (games.isEmpty()) card("Пока нет партий", "Запустите захват перед первым ходом партии с ботом. После первого распознанного хода партия появится здесь.")
        games.forEach { file ->
            val game = runCatching { ReportStore.load(file) }.getOrNull()
            if (game != null) button("${game.getString("date")} · ${game.getJSONArray("moves").length()} полуходов · ${file.nameWithoutExtension.substringAfter('-')}") { renderReport(file) }
        }
    }
    private fun renderReport(file: File) {
        val game = runCatching { ReportStore.load(file) }.getOrElse { toast("Не удалось открыть партию"); return }
        displayedReport = file
        newPage("Разбор партии", "${game.getString("date")} · Stockfish 17.1")
        button("К списку партий") { renderGames() }
        label(ReportStore.report(game))
        button(if (reviewing) "Идёт анализ…" else "Завершить анализ всех ходов", true) { analyzeSaved(file) }.isEnabled = !AppState.running && !reviewing
        if (AppState.running) label("Для полного разбора сначала остановите захват.", 14, muted)
        button("Сохранить партию как PGN") { export(ReportStore.pgn(game), "${file.nameWithoutExtension}.pgn") }
        button("Сохранить отчёт как TXT") { export(ReportStore.report(game), "${file.nameWithoutExtension}-report.txt") }
        button("Удалить эту партию") {
            AlertDialog.Builder(this).setTitle("Удалить партию?").setMessage("Историю и отчёт нельзя будет восстановить.")
                .setPositiveButton("Удалить") { _, _ -> if (!AppState.running && !reviewing) { file.delete(); renderGames() } else toast("Сначала остановите анализ") }
                .setNegativeButton("Отмена", null).show()
        }
    }
    private fun analyzeSaved(file: File) {
        if (AppState.running || reviewing) return
        reviewing = true; renderReport(file)
        worker.execute {
            val engine = StockfishEngine(this); reviewEngine = engine
            try {
                val game = ReportStore.load(file); val rows = game.getJSONArray("moves")
                var before = engine.analyze(Position.initial(), 1000)
                for (i in 0 until rows.length()) {
                    if (Thread.currentThread().isInterrupted) break
                    val row = rows.getJSONObject(i); val p = Position.fromFen(row.getString("before"))
                    val move = Move.parse(row.getString("uci")); val next = p.play(move)
                    check(next.fen() == row.getString("after")) { "Повреждена история партии" }
                    val after = engine.analyze(next, 1000)
                    val loss = ((before.whiteCp - after.whiteCp) * if (p.white) 1 else -1).coerceAtLeast(0)
                    row.put("cp", after.whiteCp).put("loss", loss).put("depth", after.depth)
                    before.bestMove?.let { row.put("alternative", p.san(it)) }; before = after
                    val atomic = android.util.AtomicFile(file); val output = atomic.startWrite()
                    try { output.write(game.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
                    catch (e: Exception) { atomic.failWrite(output); throw e }
                }
                runOnUiThread { toast("Разбор завершён") }
            } catch (e: Exception) { runOnUiThread { if (!isDestroyed) toast("Разбор прерван: ${e.message}") } }
            finally {
                engine.close(); reviewEngine = null; reviewing = false
                runOnUiThread { if (!isDestroyed && displayedReport == file) renderReport(file) }
            }
        }
    }
    @Suppress("DEPRECATION") private fun export(text: String, name: String) {
        exportText = text
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, name), 8)
    }
    private fun renderHelp() {
        newPage("Перед первым ходом", "Настройка Redmi 12 занимает несколько минут.")
        button("Вернуться") { renderHome() }
        card("1 / Установка и разрешения", "Установите ARM64 APK. Разрешите уведомления и показ поверх других окон. В специальных возможностях включите службу проверки режима. При запрете Android: сведения о приложении → ⋮ → Разрешить ограниченные настройки.")
        card("2 / Xiaomi", "Настройки → Приложения → Шахматный тренер → Другие разрешения: показ всплывающих окон, включая фон. В разделе батареи выберите «Без ограничений». Названия пунктов зависят от MIUI/HyperOS. Автозапуск не нужен: захват запускается вручную.")
        card("3 / Chess.com", "Используйте 2D-доску, стандартные фигуры Neo. Отключите анимации и звёздочки ходов, не увеличивайте доску. Запускайте новую стандартную партию с ботом перед первым ходом. Цвет доски может отличаться, ориентация определяется в начальной позиции.")
        card("4 / Только подтверждённый бот", "Нужна явная доступная Android метка «Bot», «Computer», «Бот» или «Компьютер» в области соперника над доской. Меню «Боты» и одно имя Martin разрешением не являются. Если версия Chess.com скрывает эту метку, анализ блокируется: обхода проверки нет.")
        card("5 / Захват и остановка", "Выберите весь экран. Остановить можно кнопкой оверлея, уведомлением или системной кнопкой трансляции. Блокировка экрана тоже может остановить захват. Для повторного запуска понадобится новое разрешение Android.")
        card("6 / Отчёты", "После остановки откройте «Сохранённые партии». Полный анализ продолжит оценку всех ходов локально. Экспортируйте PGN и текстовый отчёт через системное меню сохранения. Мат и пат определяются правилами; результат прерванной партии обозначен *. Фактическую сдачу или ничью по соглашению скриншоты доски не устанавливают.")
        label("Stockfish 17.1 — GPLv3. Исходный код и лицензии входят в комплект артефактов сборки. Приложение не связано с Chess.com. Блокировка основана на доступных метках интерфейса и требует проверки на конкретной версии Chess.com.", 14, muted)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { AppState.listeners.remove(refresh); worker.shutdownNow(); reviewEngine?.close(); super.onDestroy() }
}
