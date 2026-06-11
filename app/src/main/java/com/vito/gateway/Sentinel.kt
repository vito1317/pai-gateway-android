package com.vito.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// paigent 主動感知通道（新版連接方式）：裝置哨兵——電量低 / 儲存空間不足時，
// 以 paigent WebhookNotifier 的 JSON 格式 POST 到 {paiBase}/webhooks/{node}，
// 平台 WebhookController 原生認得（自動轉 PaiEvent → 意圖分類 → 領域協調者）。
// 指令通道（ReversePoller long-poll）不受影響，這是平行的「節點主動回報」通道。
//
// 比照 paigent ThresholdTrigger 的遲滯（hysteresis）語意：
// 跨入超標狀態只發一次，恢復正常後才會再次觸發；外加每小時打擾上限（本地節流）。
object Sentinel {
    @Volatile private var running = false
    private var ctx: Context? = null
    private lateinit var paiBase: String
    private lateinit var node: String

    private const val CHECK_INTERVAL_MS = 5 * 60_000L      // 5 分鐘檢查一次
    private const val BATTERY_LOW_PCT = 15
    private const val STORAGE_LOW_FREE_PCT = 10.0
    private const val MAX_PER_HOUR = 6                      // 本地節流（ProactivityPolicy 同款）

    private val inBreach = HashSet<String>()                // 遲滯：已在超標狀態的指標
    private val sentAt = ArrayDeque<Long>()                 // 最近一小時送出的時間戳

    fun start(context: Context, paiBaseUrl: String, nodeName: String) {
        if (running) return
        ctx = context.applicationContext
        paiBase = paiBaseUrl.trimEnd('/')
        node = nodeName
        running = true
        GatewayState.log("哨兵啟動（paigent 通道）→ $paiBase/webhooks/$node")
        thread(name = "sentinel") { loop() }
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { checkOnce() } catch (e: Throwable) { GatewayState.log("哨兵檢查失敗：${e.message}") }
            Thread.sleep(CHECK_INTERVAL_MS)
        }
    }

    private fun checkOnce() {
        val c = ctx ?: return

        // 電量：低於門檻且未充電 → 超標
        val batt = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batt != null) {
            val level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            gauge(
                key = "battery-low",
                breach = pct in 0 until BATTERY_LOW_PCT && !charging,
                urgency = 0.7, confidence = 0.95,
                rationale = "節點 $node 電量 $pct%（未充電），低於 $BATTERY_LOW_PCT%",
            )
        }

        // 儲存空間：可用比例過低 → 超標
        val stat = StatFs(Environment.getDataDirectory().path)
        val freePct = stat.availableBytes.toDouble() / stat.totalBytes.toDouble() * 100
        gauge(
            key = "storage-low",
            breach = freePct < STORAGE_LOW_FREE_PCT,
            urgency = 0.6, confidence = 0.95,
            rationale = "節點 $node 儲存空間僅剩 ${"%.1f".format(freePct)}%，低於 $STORAGE_LOW_FREE_PCT%",
        )
    }

    // 遲滯 + 節流後決定要不要發；breach=false 時解除狀態（下次超標可再發）
    private fun gauge(key: String, breach: Boolean, urgency: Double, confidence: Double, rationale: String) {
        if (!breach) { inBreach.remove(key); return }
        if (!inBreach.add(key)) return                       // 已通報過，等恢復
        if (!allowInterruption()) { GatewayState.log("哨兵節流：$key 暫不通報"); return }
        if (notifyPlatform(key, urgency, confidence, rationale)) {
            GatewayState.log("哨兵通報：$rationale")
        } else {
            inBreach.remove(key)                             // 送失敗 → 下輪重試
        }
    }

    private fun allowInterruption(): Boolean {
        val now = System.currentTimeMillis()
        while (sentAt.isNotEmpty() && now - sentAt.first() > 3600_000L) sentAt.removeFirst()
        if (sentAt.size >= MAX_PER_HOUR) return false
        sentAt.addLast(now)
        return true
    }

    // paigent WebhookNotifier 同款 payload：{title, body, intent:{action, urgency, confidence, …}}
    private fun notifyPlatform(action: String, urgency: Double, confidence: Double, rationale: String): Boolean {
        return try {
            val intent = JSONObject()
                .put("action", action).put("params", JSONObject())
                .put("confidence", confidence).put("urgency", urgency)
                .put("rationale", rationale).put("requested_level", 1)
                .put("event_id", JSONObject.NULL)
            val payload = JSONObject()
                .put("title", "建議：$action").put("body", rationale).put("intent", intent)
            val c = (URL("$paiBase/webhooks/$node").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            c.responseCode in 200..299
        } catch (e: Throwable) {
            GatewayState.log("哨兵通報失敗：${e.message}")
            false
        }
    }
}
