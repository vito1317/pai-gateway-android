package com.vito.gateway

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通知按鈕（如 HITL 接受/拒絕）：按下 → 帶本機憑證 POST 到 PAI 的相對路徑 → 取消該通知 + 顯示結果。
 * 通用設計：任何 phone_notify 帶的 actions[{label,path,body}] 都走這裡，不限 HITL。
 */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val body = intent.getStringExtra("body") ?: "{}"
        val notifId = intent.getIntExtra("notif_id", -1)
        val label = intent.getStringExtra("label") ?: "處理中"

        // 先收掉通知，給即時回饋（網路請求在背景跑）
        if (notifId >= 0) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        }
        val pending = goAsync()
        Thread {
            val msg = try {
                val prefs = Prefs(ctx)
                val url = URL(prefs.paiBase.trimEnd('/') + path)
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                runCatching { JSONObject(raw).optString("message").ifEmpty { "$label 完成" } }
                    .getOrDefault("$label 完成")
            } catch (e: Throwable) {
                "$label 失敗：${e.message}"
            }
            Handler(ctx.mainLooper).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            pending.finish()
        }.start()
    }
}
