package com.maxbridge

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Слушатель уведомлений Android.
 *
 * Android официально разрешает приложению читать уведомления других
 * приложений — это NotificationListenerService (пользователь выдаёт доступ
 * вручную в настройках). Именно поэтому эта схема работает на iPhone-задаче:
 * Android читает уведомления MAX, а мы отправляем их в Telegram, откуда
 * на iPhone приходит обычный push.
 */
class MaxNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs(this).log("Служба подключена к системе")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Prefs(this).log("Служба отключена системой, пробую переподключиться")
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, MaxNotificationListener::class.java)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "rebind failed", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            Log.e(TAG, "handle failed", t)
            Prefs(this).log("Ошибка обработки: ${t.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val verbose = prefs.debugAll

        val allowed = prefs.packages
        val selected = sbn.packageName in allowed

        val parsed = parse(notification)
        val brief = "${parsed?.title.orEmpty()} / ${parsed?.text.orEmpty().take(70)}"

        // Не выбранное приложение: в обычном режиме молчим,
        // в режиме отладки пишем в журнал — так видно пакет MAX.
        if (!selected) {
            if (verbose) prefs.log("· мимо ${sbn.packageName} | $brief")
            return
        }

        prefs.log("→ ${sbn.packageName} | flags=${notification.flags} | $brief")

        if (parsed == null) {
            prefs.log("   стоп: не удалось разобрать уведомление")
            return
        }
        if (parsed.title.isBlank() && parsed.text.isBlank()) {
            val keys = try {
                notification.extras?.keySet()?.joinToString(",").orEmpty()
            } catch (_: Throwable) {
                ""
            }
            prefs.log("   стоп: пустой текст (extras: $keys)")
            return
        }

        val isCall = parsed.isCall || parsed.isMissedCall
        if (isCall && !prefs.forwardCalls) {
            prefs.log("   стоп: звонки отключены в настройках")
            return
        }

        // Групповые "сводки" дублируют содержимое — пропускаем.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            prefs.log("   стоп: групповая сводка")
            return
        }

        // Постоянные (ongoing) уведомления — это сервисы, плееры, "подключено".
        // Звонки тоже ongoing, поэтому их не отсекаем.
        if (!isCall && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            prefs.log("   стоп: постоянное (ongoing) уведомление")
            return
        }

        val token = prefs.botToken
        val chatId = prefs.chatId
        if (token.isBlank() || chatId.isBlank()) {
            prefs.log("Пропущено (нет токена или chat_id): ${parsed.title}")
            return
        }

        val signature = buildString {
            append(sbn.key).append('|')
            append(sbn.postTime).append('|')
            append(parsed.title).append('|')
            append(parsed.text).append('|')
            append(parsed.isCall).append('|')
            append(parsed.isMissedCall)
        }
        if (!prefs.markIfNew(signature)) {
            if (verbose) prefs.log("   стоп: дубликат")
            return
        }

        val message = buildMessage(parsed, sbn)
        val logTitle = parsed.title.ifBlank { sbn.packageName }

        worker.execute {
            Outbox.add(this, message)
            val (sent, error) = Outbox.flush(this, token, chatId)
            if (error == null && sent > 0) {
                val extra = if (sent > 1) " (+${sent - 1} из очереди)" else ""
                prefs.log("Отправлено: $logTitle$extra")
            } else {
                prefs.log("Не отправлено, оставлено в очереди: $error")
            }
        }
    }

    private data class Parsed(
        val title: String,
        val text: String,
        val isCall: Boolean,
        val isMissedCall: Boolean
    )

    @Suppress("DEPRECATION")
    private fun parse(n: Notification): Parsed? {
        val extras = n.extras ?: return null

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()

        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()

        // Некоторые приложения кладут текст не в EXTRA_TEXT, а сюда.
        if (text.isEmpty()) {
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            if (!subText.isNullOrEmpty()) text = subText
        }
        if (text.isEmpty()) {
            @Suppress("DEPRECATION")
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null && lines.isNotEmpty()) {
                text = lines.joinToString("\n") { it.toString().trim() }
            }
        }

        // MessagingStyle: даёт точное имя отправителя и текст последнего сообщения.
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.lastOrNull() as? Bundle
            if (last != null) {
                val sender = last.getCharSequence("sender")?.toString()?.trim().orEmpty()
                val body = last.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (sender.isNotEmpty()) title = sender
                if (body.isNotEmpty()) text = body
            }
        }

        // В мессенджерах title часто = название чата, а имя автора в subText.
        if (conversation.isNotEmpty() && conversation != title) {
            title = if (title.isEmpty()) conversation else "$title ($conversation)"
        }

        val category = n.category
        val isCall = category == Notification.CATEGORY_CALL
        val isMissedCall = Build.VERSION.SDK_INT >= 31 &&
            category == Notification.CATEGORY_MISSED_CALL

        // Если прошивка/приложение не ставит CATEGORY_CALL, ловим по тексту,
        // но только когда категория не похожа на обычное сообщение.
        val haystack = "$title $text".lowercase(Locale.getDefault())
        val looksLikeCall = haystack.contains("звон") ||
            haystack.contains("calling") ||
            haystack.contains("incoming call") ||
            haystack.contains("missed call")
        val messageLike = category == Notification.CATEGORY_MESSAGE ||
            category == Notification.CATEGORY_SOCIAL ||
            category == Notification.CATEGORY_EMAIL

        return Parsed(
            title = title,
            text = text,
            isCall = isCall || (looksLikeCall && !messageLike),
            isMissedCall = isMissedCall
        )
    }

    private fun buildMessage(parsed: Parsed, sbn: StatusBarNotification): String {
        val sb = StringBuilder()

        when {
            parsed.isMissedCall -> sb.append("📵 <b>ПРОПУЩЕННЫЙ ЗВОНОК</b>\n")
            parsed.isCall -> sb.append("📞 <b>ВХОДЯЩИЙ ЗВОНОК</b>\n")
            else -> sb.append("💬 <b>").append(esc(appLabel(sbn.packageName))).append("</b>\n")
        }

        if (parsed.title.isNotBlank()) {
            sb.append("👤 ").append(esc(parsed.title)).append('\n')
        }
        if (parsed.text.isNotBlank()) {
            sb.append("📝 ").append(esc(parsed.text)).append('\n')
        }
        sb.append("🕒 ").append(timeFormat.format(Date(sbn.postTime)))

        if (parsed.isCall || parsed.isMissedCall) {
            sb.append("\n\n<i>Откройте MAX на телефоне, чтобы ответить.</i>")
        }

        return sb.toString()
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val TAG = "MaxBridge"
    }
}
