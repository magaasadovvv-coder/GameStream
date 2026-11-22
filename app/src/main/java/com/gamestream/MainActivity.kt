package com.gamestream

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val reqCapture = 100
    private val reqPerms = 101

    private val accent = Color.rgb(230, 40, 60)
    private val textMain = Color.WHITE
    private val textDim = Color.rgb(160, 160, 175)
    private val card = Color.rgb(30, 30, 40)

    private var target = Target.YOUTUBE
    private var quality = Quality.P720_60
    private var vertical = false
    private var audio = AudioMode.GAME_AND_MIC
    private var pcPort = 9000
    private var privacy = false
    private var gamePkg = ""
    private lateinit var privacyBox: LinearLayout
    private lateinit var gameBtn: Button
    private lateinit var usageBtn: Button

    private lateinit var ytBlock: LinearLayout
    private lateinit var pcBlock: LinearLayout
    private lateinit var serverField: EditText
    private lateinit var keyField: EditText
    private lateinit var ipField: EditText
    private lateinit var findStatus: TextView
    private lateinit var overlayBtn: Button
    private lateinit var startBtn: Button
    private lateinit var audioHint: TextView
    private lateinit var qualityGroup: RadioGroup
    private lateinit var updateBox: LinearLayout
    private lateinit var updateBtn: Button
    private var release: Updater.Release? = null

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = StreamConfig.load(this)
        target = cfg.target; quality = cfg.quality; vertical = cfg.vertical; audio = cfg.audio; pcPort = cfg.pcPort
        privacy = cfg.privacy; gamePkg = cfg.gamePkg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "● GameStream"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
        })
        root.addView(hint("Стрим игр без водяных знаков — на YouTube или на ПК в OBS"))
        root.addView(hint("Версия " + (runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?")))

        // Плашка «доступно обновление» — появляется сама, если на GitHub есть версия новее
        updateBox = section().apply {
            visibility = View.GONE
            addView(label("🔔 Доступна новая версия"))
            updateBtn = Button(this@MainActivity).apply {
                isAllCaps = false
                setOnClickListener { installUpdate() }
            }
            addView(updateBtn)
        }
        root.addView(updateBox)

        // Если в прошлый раз был сбой — показываем причину, чтобы её можно было отправить
        App.lastCrash(this)?.let { crash ->
            val box = section()
            box.addView(label("⚠ В прошлый раз произошёл сбой"))
            box.addView(hint(crash.take(1200)).apply { setTextIsSelectable(true) })
            box.addView(Button(this).apply {
                text = "Скопировать ошибку"
                isAllCaps = false
                setOnClickListener {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("GameStream", crash))
                    toast("Скопировано — вставь в чат")
                }
            })
            box.addView(Button(this).apply {
                text = "Скрыть"
                isAllCaps = false
                setOnClickListener {
                    App.clearCrash(this@MainActivity)
                    box.visibility = View.GONE
                }
            })
            root.addView(box)
        }

        // --- Куда стримить ---
        root.addView(header("Куда стримить"))
        root.addView(radioGroup(
            listOf("📺 YouTube (прямо с телефона)", "💻 На ПК в OBS (Wi‑Fi или USB)"),
            target.ordinal
        ) { target = Target.entries[it]; refresh() })

        ytBlock = section().apply {
            addView(label("Сервер"))
            serverField = field(cfg.ytServer, false).also { addView(it) }
            addView(label("Ключ трансляции"))
            keyField = field(cfg.ytKey, true).also { addView(it) }
            addView(hint("Ключ: YouTube Studio → Создать → Начать трансляцию → вкладка «Трансляция» → Ключ трансляции. Никому его не показывай."))
        }
        root.addView(ytBlock)

        pcBlock = section().apply {
            addView(Button(this@MainActivity).apply {
                text = "🔍 Найти ПК автоматически"
                isAllCaps = false
                setOnClickListener { findPc() }
            })
            findStatus = hint("Запусти на компьютере программу GameStream PC, телефон и ПК — в одной Wi‑Fi сети.")
            addView(findStatus)
            addView(label("IP компьютера (если не нашёлся сам)"))
            ipField = field(cfg.pcIp, false).also {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.hint = "например 192.168.1.5"
                addView(it)
            }
            addView(hint("Микрофон, вебка и оверлеи — в OBS на ПК. Поэтому для режима «На ПК» лучше выбрать звук «Только звук игры»."))
            addView(label("По USB-кабелю"))
            addView(hint("Меньше задержка и не зависишь от Wi‑Fi, а телефон заодно заряжается. Подключи телефон к ПК кабелем, включи «USB-модем» и нажми «Найти ПК автоматически»."))
            addView(Button(this@MainActivity).apply {
                text = "🔌 Включить USB-модем"
                isAllCaps = false
                setOnClickListener { openTetherSettings() }
            })
        }
        root.addView(pcBlock)

        // --- Качество ---
        root.addView(header("Качество"))
        qualityGroup = radioGroup(Quality.entries.map { it.title }, quality.ordinal) {
            quality = Quality.entries[it]
        }
        root.addView(qualityGroup)
        root.addView(hint("Для YouTube с мобильного интернета начни с 720p. Для режима «На ПК» битрейт автоматически вдвое выше."))

        root.addView(header("Ориентация"))
        root.addView(radioGroup(
            listOf("Горизонтально (большинство игр)", "Вертикально (Shorts)"),
            if (vertical) 1 else 0
        ) { vertical = it == 1 })

        root.addView(header("Звук"))
        root.addView(radioGroup(AudioMode.entries.map { it.title }, audio.ordinal) {
            audio = AudioMode.entries[it]
        })
        audioHint = hint(
            if (Build.VERSION.SDK_INT < 29) "На твоей версии Android звук игры захватить нельзя — будет только микрофон."
            else "Звук игры захватывается напрямую, без шума комнаты. Некоторые игры могут его запрещать."
        )
        root.addView(audioHint)

        // --- Приватность ---
        root.addView(header("Приватность"))
        root.addView(android.widget.CheckBox(this).apply {
            text = "Авто-скрытие личного"
            textSize = 15f
            setTextColor(textMain)
            isChecked = privacy
            setOnCheckedChangeListener { _, on -> privacy = on; refresh() }
        })
        root.addView(hint("Зрители видят заставку «Скоро вернусь» вместо экрана, когда ты выходишь из игры. Звук продолжает идти."))
        privacyBox = section().apply {
            gameBtn = Button(this@MainActivity).apply {
                isAllCaps = false
                setOnClickListener { pickGame() }
            }
            addView(gameBtn)
            usageBtn = Button(this@MainActivity).apply {
                isAllCaps = false
                setOnClickListener { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            }
            addView(usageBtn)
            addView(hint("В открывшемся списке найди GameStream и включи. Если переключатель серый: Настройки → Приложения → GameStream → ⋮ → «Разрешить ограниченные настройки»."))
            addView(hint("Чтобы уведомления не всплывали на стриме, включай на телефоне режим «Не беспокоить»."))
        }
        root.addView(privacyBox)

        // --- Удобства ---
        root.addView(header("Удобства"))
        root.addView(Button(this).apply {
            text = "🩺 Проверка перед эфиром"
            isAllCaps = false
            setOnClickListener { runPreflight() }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(Button(this).apply {
                text = "⚡ Добавить плитку быстрого старта в шторку"
                isAllCaps = false
                setOnClickListener { addTile() }
            })
        }
        root.addView(hint("Плитка «GameStream» в шторке запускает стрим одним тапом с последними настройками." +
            (if (Build.VERSION.SDK_INT < 33) " Добавь её: опусти шторку → карандаш ✏ → перетащи плитку GameStream." else "")))
        root.addView(Button(this).apply {
            text = "🔄 Проверить обновления"
            isAllCaps = false
            setOnClickListener { checkUpdates(manual = true) }
        })

        // --- Кнопки ---
        overlayBtn = Button(this).apply {
            text = "Разрешить кнопку поверх игр"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayBtn, spaced(16))

        startBtn = Button(this).apply {
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(12).toFloat() }
            setOnClickListener { onStartClick() }
        }
        root.addView(startBtn, spaced(12).apply { height = dp(56) })

        root.addView(hint("Во время игры: тап по красной кнопке — микрофон вкл/выкл, долгое нажатие — завершить стрим."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 16, 22))
            addView(root)
        })
        refresh()
        checkUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::startBtn.isInitialized) return
        ytBlock.visibility = if (target == Target.YOUTUBE) View.VISIBLE else View.GONE
        pcBlock.visibility = if (target == Target.PC) View.VISIBLE else View.GONE
        overlayBtn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        startBtn.text = if (StreamService.isRunning) "■  Завершить стрим" else "●  Начать стрим"

        privacyBox.visibility = if (privacy) View.VISIBLE else View.GONE
        gameBtn.text = if (gamePkg.isEmpty()) "🎮 Выбрать игру" else "🎮 Игра: ${appName(gamePkg)}"
        usageBtn.text = (if (Access.hasUsageAccess(this)) "✓ " else "① ") + "Разрешить: скрывать экран вне игры"
    }

    private fun currentConfig() = StreamConfig(
        target = target,
        ytServer = serverField.text.toString().trim().ifEmpty { StreamConfig().ytServer },
        ytKey = keyField.text.toString().trim(),
        pcIp = ipField.text.toString().trim(),
        pcPort = pcPort,
        privacy = privacy,
        gamePkg = gamePkg,
        quality = quality,
        vertical = vertical,
        audio = audio,
    )

    // ---------- Запуск ----------

    private fun onStartClick() {
        if (StreamService.isRunning) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        val cfg = currentConfig()
        cfg.problem(this)?.let { return toast(it) }
        cfg.save(this)

        val needed = mutableListOf<String>()
        // Нужно всегда: Android требует это разрешение даже для записи звука игры
        needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), reqPerms)
        } else {
            askScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPerms) return
        val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (micIndex >= 0 && grantResults[micIndex] != PackageManager.PERMISSION_GRANTED) {
            return toast("Разрешение «Микрофон» обязательно: без него Android не даёт записывать даже звук игры")
        }
        askScreenCapture()
    }

    private fun askScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) return toast("Без записи экрана стрим невозможен")

        val intent = currentConfig().toIntent(Intent(this, StreamService::class.java))
            .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(StreamService.EXTRA_RESULT_DATA, data)
        startForegroundService(intent)
        toast("Подключаюсь… Открывай игру!")
        moveTaskToBack(true)
    }

    // ---------- Приватность ----------

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { toast("Не удалось открыть настройки — найди этот пункт вручную в «Специальный доступ»") }
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun pickGame() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this)
            .setTitle("Какую игру показывать зрителям?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                gamePkg = apps[i].first
                currentConfig().save(this)
                refresh()
            }
            .show()
    }

    // ---------- Обновления ----------

    private fun checkUpdates(manual: Boolean) {
        if (manual) toast("Проверяю…")
        thread {
            val r = runCatching { Updater.check(this) }
            runOnUiThread {
                val rel = r.getOrNull()
                release = rel
                if (rel != null) {
                    updateBox.visibility = View.VISIBLE
                    updateBtn.text = "⬇ Обновить до версии 1.${rel.version}"
                    updateBtn.isEnabled = true
                } else if (manual) {
                    toast(if (r.isFailure) "Не удалось проверить — нет интернета?" else "У тебя последняя версия")
                }
            }
        }
    }

    private fun installUpdate() {
        val rel = release ?: return
        if (StreamService.isRunning) return toast("Сначала заверши стрим")
        if (!Updater.ensureInstallAllowed(this)) return
        updateBtn.isEnabled = false
        updateBtn.text = "Скачиваю… 0%"
        Updater.downloadAndInstall(this, rel,
            onProgress = { p -> runOnUiThread { updateBtn.text = if (p < 100) "Скачиваю… $p%" else "Устанавливаю…" } },
            onError = { e -> runOnUiThread {
                updateBtn.isEnabled = true
                updateBtn.text = "⬇ Обновить до версии 1.${rel.version}"
                toast("Не удалось скачать: $e")
            } })
    }

    // ---------- Проверка перед эфиром ----------

    private fun runPreflight() {
        val cfg = currentConfig()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Проверка перед эфиром")
            .setMessage("Проверяю сеть, ПК, заряд и нагрев…")
            .setPositiveButton("OK", null)
            .show()
        thread {
            val result = runCatching { Preflight.run(this, cfg) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dialog.dismiss()
                val res = result.getOrNull()
                if (res == null) return@runOnUiThread toast("Проверка не удалась: ${result.exceptionOrNull()?.message}")
                val text = res.lines.joinToString("\n\n") { "${it.level.icon}  ${it.text}" } +
                    "\n\nРекомендуемое качество: ${res.recommended.title}" +
                    if (res.hasBlockers) "\n\nИсправь пункты с ✗ перед стримом." else ""
                val b = android.app.AlertDialog.Builder(this)
                    .setTitle(if (res.hasBlockers) "Есть проблемы" else "Можно стримить")
                    .setMessage(text)
                    .setNegativeButton("Закрыть", null)
                if (res.recommended != quality) {
                    b.setPositiveButton("Поставить ${res.recommended.title}") { _, _ ->
                        (qualityGroup.getChildAt(res.recommended.ordinal) as? RadioButton)?.isChecked = true
                        currentConfig().save(this)
                    }
                }
                b.show()
            }
        }
    }

    // ---------- Плитка и USB ----------

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(android.app.StatusBarManager::class.java)
        sbm.requestAddTileService(
            android.content.ComponentName(this, StreamTileService::class.java),
            "GameStream",
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.presence_video_online),
            mainExecutor
        ) { code ->
            if (code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast("Плитка уже есть в шторке")
        }
    }

    private fun openTetherSettings() {
        val direct = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                .onFailure { toast("Открой Настройки → Сеть → Точка доступа и модем → USB-модем") }
        }
    }

    // ---------- Поиск ПК ----------

    private fun findPc() {
        findStatus.text = "Ищу компьютер в сети…"
        thread {
            val pc = runCatching { PcFinder.find(this) }.getOrNull()
            runOnUiThread {
                if (pc != null) {
                    ipField.setText(pc.ip)
                    findStatus.text = "✓ Найден: ${pc.name} (${pc.ip})" + if (pc.usb) " — по USB-кабелю" else " — по Wi‑Fi"
                    pcPort = pc.port
                    currentConfig().save(this)
                } else {
                    findStatus.text = "Не нашёл. Проверь, что GameStream PC запущен и оба устройства в одной Wi‑Fi сети, или впиши IP вручную."
                }
            }
        }
    }

    // ---------- Мелкие помощники для интерфейса ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(textMain)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(textDim)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(value: String, secret: Boolean) = EditText(this).apply {
        setText(value)
        setTextColor(textMain)
        setHintTextColor(textDim)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun section() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
        layoutParams = spaced(10)
    }

    private fun radioGroup(options: List<String>, selected: Int, onPick: (Int) -> Unit) =
        RadioGroup(this).apply {
            options.forEachIndexed { i, title ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = title
                    textSize = 15f
                    setTextColor(textMain)
                    isChecked = i == selected
                    setOnCheckedChangeListener { _, checked -> if (checked) onPick(i) }
                })
            }
        }
}
