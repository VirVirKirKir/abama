package com.maxbridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/**
 * После перезагрузки система обычно сама поднимает слушатель, но на
 * некоторых прошивках (Xiaomi, Huawei, Samsung) он теряется. Пробуем
 * переподключиться вручную.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, MaxNotificationListener::class.java)
            )
            Prefs(context).log("Загрузка/обновление: запрошен перезапуск слушателя")
        } catch (t: Throwable) {
            Prefs(context).log("Не удалось перезапустить слушатель: ${t.message}")
        }
    }
}
