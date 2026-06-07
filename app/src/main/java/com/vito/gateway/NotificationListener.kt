package com.vito.gateway

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知存取：讀取手機所有 App 通知（LINE/訊息…），並可用通知上的「快速回覆」(RemoteInput)
 * 直接回覆訊息（不用打開 App，智慧手錶同款機制）。
 * 需使用者在系統設定手動開啟「通知存取」權限（特殊權限，無法用一般 runtime 請求）。
 */
class NotificationListener : NotificationListenerService() {

    data class Note(
        val key: String, val pkg: String, val app: String,
        val title: String, val text: String, val canReply: Boolean, val time: Long,
    )

    companion object {
        /** 最近通知（新到舊，最多 30 則）。 */
        val recent = java.util.concurrent.CopyOnWriteArrayList<Note>()

        @Volatile var instance: NotificationListener? = null

        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true

        /** 開系統「通知存取」設定頁讓使用者授權。 */
        fun openSettings(ctx: Context) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
    }

    override fun onListenerConnected() {
        instance = this
        GatewayState.log("🔔 通知存取已連接")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return // 自己的通知不收
            val n = sbn.notification ?: return
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            val canReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Throwable) { sbn.packageName }
            recent.removeAll { it.key == sbn.key }
            recent.add(0, Note(sbn.key, sbn.packageName, app, title, text, canReply, System.currentTimeMillis()))
            while (recent.size > 30) recent.removeAt(recent.size - 1)
        } catch (_: Throwable) {}
    }

    /** 用通知的快速回覆動作直接回訊息（LINE 等支援 RemoteInput 的通知都可）。 */
    fun reply(key: String, message: String): String {
        val sbn = try { activeNotifications?.firstOrNull { it.key == key } } catch (_: Throwable) { null }
            ?: return "這則通知已消失（可能已讀/被清掉），無法回覆"
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: return "這則通知不支援快速回覆"
        return try {
            val intent = Intent()
            val results = Bundle()
            action.remoteInputs!!.forEach { ri -> results.putCharSequence(ri.resultKey, message) }
            android.app.RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.actionIntent.send(this, 0, intent)
            "已回覆「$message」"
        } catch (e: Throwable) { "回覆失敗：${e.message}" }
    }
}
