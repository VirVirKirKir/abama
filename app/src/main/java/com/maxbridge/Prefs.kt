package com.maxbridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Все настройки и журнал. Специально без AndroidX и DataStore,
 * чтобы сборка не зависела от лишних библиотек.
 */
class Prefs(context: Context) {

    private val appCtx: Context = context.applicationContext
    private val sp = appCtx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    var forwardCalls: Boolean
        get() = sp.getBoolean("forward_calls", true)
        set(value) = sp.edit().putBoolean("forward_calls", value).apply()

    /** Пересылать уведомления от любых приложений (для отладки и поиска пакета MAX). */
    var debugAll: Boolean
        get() = sp.getBoolean("debug_all", false)
        set(value) = sp.edit().putBoolean("debug_all", value).apply()

    var botToken: String
        get() = sp.getString("bot_token", "").orEmpty().trim()
        set(value) = sp.edit().putString("bot_token", value.trim()).apply()

    var chatId: String
        get() = sp.getString("chat_id", "").orEmpty().trim()
        set(value) = sp.edit().putString("chat_id", value.trim()).apply()

    var packages: Set<String>
        get() = sp.getStringSet("packages", emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("packages", value).apply()

    /**
     * Возвращает true, если такое уведомление ещё не пересылали.
     * Защита от двойных вызовов onNotificationPosted и от повторных
     * обновлений того же уведомления системой.
     */
    fun markIfNew(signature: String): Boolean = synchronized(lock) {
        val seen = sp.getStringSet("seen", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (seen.contains(signature)) return false
        seen.add(signature)
        while (seen.size > MAX_SEEN) {
            val it = seen.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        sp.edit().putStringSet("seen", seen).apply()
        true
    }

    fun log(line: String) {
        synchronized(lock) {
            try {
                val file = File(appCtx.filesDir, LOG_FILE)
                val old = if (file.exists()) file.readLines().takeLast(MAX_LOG_LINES - 1) else emptyList()
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                file.writeText((old + "[$ts] $line").joinToString("\n"))
            } catch (_: Throwable) {
                // журнал не критичен
            }
        }
    }

    fun readLog(): String = synchronized(lock) {
        try {
            val file = File(appCtx.filesDir, LOG_FILE)
            if (!file.exists()) "" else file.readText()
        } catch (_: Throwable) {
            ""
        }
    }

    fun clearLog() {
        synchronized(lock) {
            try {
                File(appCtx.filesDir, LOG_FILE).writeText("")
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val NAME = "max_bridge"
        private const val LOG_FILE = "bridge.log"
        private const val MAX_LOG_LINES = 250
        private const val MAX_SEEN = 300
    }
}
