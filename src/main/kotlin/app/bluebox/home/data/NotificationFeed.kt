package app.bluebox.home.data

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One notification as the quick-settings panel shows it. */
data class NoticeItem(val key: String, val title: String, val text: String, val pkg: String, val open: PendingIntent?)

/**
 * Notification access: the user enables Home in "Доступ к уведомлениям" once; then the panel mirrors the system
 * notifications (VPN active, developer mode, …) like the stock Google TV shade. Without access the list stays empty.
 */
class NotificationFeed : NotificationListenerService() {
    override fun onListenerConnected() { refresh() }
    override fun onListenerDisconnected() { items = emptyList(); changed?.invoke() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { refresh() }

    private fun refresh() {
        items = runCatching { activeNotifications.orEmpty().mapNotNull(::toItem) }.getOrDefault(emptyList())
        changed?.invoke()
    }

    private fun toItem(sbn: StatusBarNotification): NoticeItem? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        return NoticeItem(sbn.key, title, text, sbn.packageName, n.contentIntent)
    }

    companion object {
        @Volatile var items: List<NoticeItem> = emptyList()
            private set
        /** Called on the thread that posted the change; the panel hops to the main thread. */
        @Volatile var changed: (() -> Unit)? = null

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationFeed::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
