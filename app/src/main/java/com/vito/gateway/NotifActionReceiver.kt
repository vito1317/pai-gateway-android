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
 * 通知/彈窗按鈕（如 HITL 接受/拒絕、通勤傳給主管）共用的送出邏輯：
 * 帶本機憑證 POST 到 PAI 的相對路徑，回傳結果以 Toast 顯示。通知欄按鈕走 Receiver，App 內彈窗直接呼叫 post()。
 */
object NotifAction {
    fun post(ctx: Context, path: String, body: String, label: String) {
        Thread {
            val msg = try {
                val prefs = Prefs(ctx)
                // 把本機節點名併進 body，讓後端把後續 agent 操作（如開 LINE）路由回這台手機
                val merged = runCatching {
                    org.json.JSONObject(body).put("node", prefs.nodeName).toString()
                }.getOrDefault(body)
                val c = (URL(prefs.paiBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(merged.toByteArray()) }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                runCatching { JSONObject(raw).optString("message").ifEmpty { "$label 完成" } }.getOrDefault("$label 完成")
            } catch (e: Throwable) {
                "$label 失敗：${e.message}"
            }
            Handler(ctx.mainLooper).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}

/** 通知欄動作按鈕：按下 → 收掉通知 → NotifAction.post。 */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val body = intent.getStringExtra("body") ?: "{}"
        val notifId = intent.getIntExtra("notif_id", -1)
        val label = intent.getStringExtra("label") ?: "處理中"
        if (notifId >= 0) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        }
        val pending = goAsync()
        try {
            NotifAction.post(ctx, path, body, label)
        } finally {
            pending.finish()
        }
    }
}
