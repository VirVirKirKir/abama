package com.maxbridge

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Минимальный клиент Telegram Bot API на HttpURLConnection.
 * Без сторонних библиотек — чтобы APK собирался стабильно и весил мало.
 */
object Telegram {

    private const val TIMEOUT_MS = 15_000
    private const val MAX_TEXT = 3800

    fun send(token: String, chatId: String, text: String): Result<Unit> {
        if (token.isBlank() || chatId.isBlank()) {
            return Result.failure(IOException("Не задан токен бота или chat_id"))
        }

        val body = buildString {
            append("chat_id=").append(enc(chatId))
            append("&text=").append(enc(clip(text)))
            append("&parse_mode=HTML")
            append("&disable_web_page_preview=true")
        }

        var lastError: Throwable? = null

        for (attempt in 0 until 3) {
            try {
                val conn = (URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }

                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                    if (code in 200..299 && response.contains("\"ok\":true")) {
                        return Result.success(Unit)
                    }

                    lastError = IOException("HTTP $code: ${response.take(300)}")

                    // Неверный токен или chat_id — повторять бессмысленно.
                    if (code == 400 || code == 401 || code == 403) {
                        return Result.failure(lastError!!)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                lastError = t
            }

            // Пауза перед повтором: сеть могла моргнуть.
            try {
                Thread.sleep(1500L * (attempt + 1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return Result.failure(lastError ?: IOException("Неизвестная ошибка отправки"))
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Обрезаем длинный текст по границе строки, чтобы не разорвать
     * HTML-сущность (`&amp;` и подобные) и не получить 400 от Telegram.
     */
    private fun clip(value: String): String {
        if (value.length <= MAX_TEXT) return value
        val cut = value.lastIndexOf('\n', MAX_TEXT)
        return if (cut > MAX_TEXT / 2) {
            value.substring(0, cut) + "\n…"
        } else {
            value.substring(0, MAX_TEXT) + "…"
        }
    }
}
