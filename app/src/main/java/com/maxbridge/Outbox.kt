package com.maxbridge

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Простая файловая очередь исходящих сообщений.
 *
 * Если в момент уведомления пропал интернет, сообщение не теряется:
 * оно лежит в files/queue и уйдёт при следующем успешном соединении
 * (очередь проталкивается при каждом новом уведомлении и при открытии настроек).
 */
object Outbox {

    private const val MAX_PENDING = 200

    private fun dir(ctx: Context): File = File(ctx.filesDir, "queue").apply { mkdirs() }

    fun add(ctx: Context, text: String) {
        try {
            val folder = dir(ctx)
            val files = folder.listFiles()
            if (files != null && files.size >= MAX_PENDING) {
                files.sortedBy { it.name }.take(files.size - MAX_PENDING + 1).forEach { it.delete() }
            }
            val name = String.format(
                Locale.US,
                "%013d-%04d.txt",
                System.currentTimeMillis(),
                Random.nextInt(10_000)
            )
            File(folder, name).writeText(text)
        } catch (_: Throwable) {
            // Если даже файл записать не удалось — ничего не поделать.
        }
    }

    fun pendingCount(ctx: Context): Int = dir(ctx).listFiles()?.size ?: 0

    /**
     * Отправляет всё, что накопилось, по порядку.
     * Возвращает пару: (сколько отправлено, текст последней ошибки или null).
     */
    fun flush(ctx: Context, token: String, chatId: String): Pair<Int, String?> {
        val files = dir(ctx).listFiles()?.sortedBy { it.name } ?: return 0 to null
        var sent = 0

        for (file in files) {
            val text = try {
                file.readText()
            } catch (_: Throwable) {
                file.delete()
                continue
            }

            if (text.isBlank()) {
                file.delete()
                continue
            }

            val result = Telegram.send(token, chatId, text)
            if (result.isSuccess) {
                file.delete()
                sent++
            } else {
                return sent to (result.exceptionOrNull()?.message ?: "ошибка отправки")
            }
        }

        return sent to null
    }
}
