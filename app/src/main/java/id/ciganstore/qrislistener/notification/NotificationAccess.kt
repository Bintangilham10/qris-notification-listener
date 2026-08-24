package id.ciganstore.qrislistener.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

object NotificationAccess {
    fun isGranted(context: Context): Boolean {
        val listener = ComponentName(context, GopayNotificationListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listener)
        }

        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()

        return enabledListeners
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == listener }
    }
}

