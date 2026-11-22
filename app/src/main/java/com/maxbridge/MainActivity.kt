package com.maxbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var etToken: EditText
    private lateinit var etChat: EditText
    private lateinit var cbEnabled: CheckBox
    private lateinit var cbCalls: CheckBox
    private lateinit var cbDebug: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvApps: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        loadValues()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderApps()
        renderLog()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        tvStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvStatus)

        root.addView(label("Токен бота Telegram"))
        etToken = EditText(this).apply {
            hint = "123456789:AA..."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(etToken)

        root.addView(label("Ваш chat_id"))
        etChat = EditText(this).apply {
            hint = "например 123456789"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        root.addView(etChat)

        cbEnabled = CheckBox(this).apply { text = "Пересылка включена" }
        cbCalls = CheckBox(this).apply { text = "Пересылать звонки" }
        cbDebug = CheckBox(this).apply { text = "Отладка: слать уведомления всех приложений" }
        root.addView(cbEnabled)
        root.addView(cbCalls)
        root.addView(cbDebug)

        root.addView(label("Откуда пересылать"))
        tvApps = TextView(this).apply { textSize = 13f }
        root.addView(tvApps)
        root.addView(button("Выбрать приложения (MAX и др.)") { pickApps() })

        root.addView(button("Сохранить настройки") { save() })
        root.addView(button("Тест: отправить сообщение в Telegram") { sendTest() })
        root.addView(button("Протолкнуть очередь") { flushQueue() })
        root.addView(button("Разрешить доступ к уведомлениям") { openListenerSettings() })
        root.addView(button("Отключить оптимизацию батареи") { requestBatteryExemption() })

        root.addView(label("Журнал"))
        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(tvLog)
        root.addView(button("Обновить журнал") { renderLog() })
        root.addView(button("Очистить журнал") {
            prefs.clearLog()
            renderLog()
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ values

    private fun loadValues() {
        etToken.setText(prefs.botToken)
        etChat.setText(prefs.chatId)
        cbEnabled.isChecked = prefs.enabled
        cbCalls.isChecked = prefs.forwardCalls
        cbDebug.isChecked = prefs.debugAll
    }

    private fun save() {
        prefs.botToken = etToken.text.toString()
        prefs.chatId = etChat.text.toString()
        prefs.enabled = cbEnabled.isChecked
        prefs.forwardCalls = cbCalls.isChecked
        prefs.debugAll = cbDebug.isChecked
        toast("Сохранено")
        renderStatus()
    }

    private fun renderStatus() {
        val listener = if (isListenerEnabled()) "✅ доступ к уведомлениям выдан"
        else "⚠️ доступ к уведомлениям НЕ выдан"

        val battery = if (isBatteryOptimized()) "⚠️ оптимизация батареи включена"
        else "✅ оптимизация батареи отключена"

        val forward = if (prefs.enabled) "✅ пересылка включена" else "⚠️ пересылка выключена"

        val pending = Outbox.pendingCount(this)
        val queue = "📤 в очереди: $pending"

        tvStatus.text = listOf(listener, battery, forward, queue).joinToString("\n")
    }

    private fun renderApps() {
        val pkgs = prefs.packages
        tvApps.text = if (pkgs.isEmpty()) {
            if (prefs.debugAll) "Выбрано: все приложения (режим отладки)"
            else "Ничего не выбрано — пересылать нечего. Нажмите «Выбрать приложения»."
        } else {
            "Выбрано: " + pkgs.joinToString(", ") { pkg ->
                try {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + " (" + pkg + ")"
                } catch (_: Throwable) {
                    pkg
                }
            }
        }
    }

    private fun renderLog() {
        val text = prefs.readLog()
        tvLog.text = text.ifBlank { "Пока пусто." }
    }

    // ------------------------------------------------------------ actions

    private fun pickApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            toast("Список приложений пуст")
            return
        }

        val selected = prefs.packages
        val checked = apps.map { it.second in selected }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Уведомления каких приложений пересылать")
            .setMultiChoiceItems(
                apps.map { "${it.first}\n${it.second}" as CharSequence }.toTypedArray(),
                checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Сохранить") { _, _ ->
                val picked = apps.filterIndexed { index, _ -> checked[index] }
                    .map { it.second }
                    .toSet()
                prefs.packages = picked
                renderApps()
                toast("Выбрано приложений: ${picked.size}")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendTest() {
        save()
        val token = prefs.botToken
        val chatId = prefs.chatId
        if (token.isBlank() || chatId.isBlank()) {
            toast("Сначала укажите токен и chat_id")
            return
        }
        toast("Отправляю тест…")
        Thread {
            val result = Telegram.send(
                token,
                chatId,
                "✅ <b>MAX Bridge</b>\nТестовое сообщение доставлено.\nЕсли вы его видите — связка работает."
            )
            prefs.log(
                if (result.isSuccess) "Тест отправлен успешно"
                else "Тест не отправлен: ${result.exceptionOrNull()?.message}"
            )
            runOnUiThread {
                toast(
                    if (result.isSuccess) "Тест доставлен"
                    else "Ошибка: ${result.exceptionOrNull()?.message}"
                )
                renderLog()
            }
        }.start()
    }

    private fun flushQueue() {
        save()
        val token = prefs.botToken
        val chatId = prefs.chatId
        if (token.isBlank() || chatId.isBlank()) {
            toast("Сначала укажите токен и chat_id")
            return
        }
        val before = Outbox.pendingCount(this)
        if (before == 0) {
            toast("Очередь пуста")
            return
        }
        toast("Отправляю $before сообщ. из очереди…")
        Thread {
            val (sent, error) = Outbox.flush(this, token, chatId)
            prefs.log("Ручная отправка очереди: $sent из $before, ошибка: ${error ?: "нет"}")
            runOnUiThread {
                toast(
                    if (error == null) "Отправлено: $sent из $before"
                    else "Отправлено: $sent из $before, ошибка: $error"
                )
                renderStatus()
                renderLog()
            }
        }.start()
    }

    private fun openListenerSettings() {        val intents = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                toast("Включите «MAX Bridge» в списке")
                return
            } catch (_: Throwable) {
                // пробуем следующий вариант
            }
        }
        toast("Не удалось открыть настройки, откройте вручную: Настройки → Уведомления → Доступ к уведомлениям")
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) {
            toast("На этой версии Android не требуется")
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Уже отключено")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("Откройте вручную: Настройки → Батарея → Оптимизация")
            }
        }
    }

    // ------------------------------------------------------------- checks

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':').any { part ->
            ComponentName.unflattenFromString(part)?.packageName == packageName
        }
    }

    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
