package app.bluebox.home.data

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One notification as the quick-settings panel shows it. [clearable] is false for ongoing ones (VPN, downloads). */
data class NoticeItem(
    val key: String, val title: String, val text: String, val pkg: String, val open: PendingIntent?,
    val clearable: Boolean = true, val tag: String? = null, val postTime: Long = 0L,
)

/**
 * Notification access: the user enables Home in "Доступ к уведомлениям" once; then the panel mirrors the system
 * notifications (VPN active, developer mode, …) like the stock Google TV shade. Without access the list stays empty.
 * "Read" = dismissed from the system shade ([dismiss] / [dismissAll]).
 */
class NotificationFeed : NotificationListenerService() {
    override fun onListenerConnected() { service = this; refresh() }
    override fun onListenerDisconnected() { service = null; items = emptyList(); changed?.invoke() }
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
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE) != 0
        return NoticeItem(sbn.key, title, text, sbn.packageName, n.contentIntent, !ongoing, sbn.tag, sbn.postTime)
    }

    companion object {
        @Volatile var items: List<NoticeItem> = emptyList()
            private set
        /** Called on the thread that posted the change; the panel hops to the main thread. */
        @Volatile var changed: (() -> Unit)? = null
        @Volatile private var service: NotificationFeed? = null

        /** The system "VPN is active" notification: its text names the app, its intent opens the system dialog with time/traffic. */
        fun vpn(): NoticeItem? = items.firstOrNull { it.tag == "VpnStatusObserver" }

        /** App name out of «Через приложение «X»»; null when the text has no quotes. */
        fun vpnApp(): String? = vpn()?.text?.let { Regex("[«\"](.+?)[»\"]").find(it)?.groupValues?.get(1) }

        /** Marks one notification as read (removes it from the system shade). */
        fun dismiss(key: String) { runCatching { service?.cancelNotification(key) } }

        /** Marks every clearable notification as read. */
        fun dismissAll() { items.filter { it.clearable }.forEach { dismiss(it.key) } }

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationFeed::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
