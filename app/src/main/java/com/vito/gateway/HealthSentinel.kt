package com.vito.gateway

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/**
 * 健康守護：從 Health Connect 讀手錶寫入的資料（Pixel Watch/Fitbit → 心率/靜息心率/睡眠/步數）。
 * 每 10 分鐘輪詢一次，規則在本地判斷：
 *   心率偏高 — 近 15 分鐘平均 ≥ HR_HIGH 且同窗步數 < 40（排除運動中）→ 本地念提醒＋回報平台
 *   心率偏低 — 近 15 分鐘平均 ≤ HR_LOW → 同上
 * 遲滯（恢復正常才會再次觸發）＋每小時節流。注意 Health Connect 非即時串流，
 * 資料是手錶同步過來的（分鐘級延遲）——秒級心律警報仍由手錶原生功能負責，這裡做的是「接手警報之後」。
 */
object HealthSentinel {
    val PERMS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    private const val CHECK_MS = 10 * 60_000L
    private const val WINDOW_MIN = 15L
    private const val EXERCISE_STEPS = 40L     // 窗內步數 ≥ 此值視為運動中，不判高心率
    private const val THROTTLE_MS = 60 * 60_000L

    // 門檻由平台設定同步（/api/sensor/config），改設定頁或叫 AI 改都會在下一輪生效
    @Volatile private var hrHigh = 110.0
    @Volatile private var hrLow = 40.0
    @Volatile private var serverEnabled = true

    @Volatile private var running = false
    private var ctx: Context? = null
    private val inBreach = HashSet<String>()   // 遲滯：已通報、等恢復
    private val lastSent = HashMap<String, Long>()
    @Volatile private var permWarned = false

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        if (!Prefs(app).healthGuard) return
        ctx = app
        running = true
        GatewayState.log("❤️ 健康守護已啟動（Health Connect 輪詢）")
        thread(name = "health-sentinel") {
            while (running) {
                try { poll() } catch (e: Throwable) { GatewayState.log("健康守護檢查失敗：${e.message}") }
                try { Thread.sleep(CHECK_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    fun stop() {
        running = false
        GatewayState.log("健康守護已停止")
    }

    /** 從平台同步門檻設定（失敗就沿用上次值）。 */
    private fun syncConfig(c: Context) {
        try {
            val p = Prefs(c)
            if (p.registerToken.isBlank()) return
            val conn = (java.net.URL(p.paiBase.trimEnd('/') + "/api/sensor/config").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 10000
                setRequestProperty("X-Register-Secret", p.registerToken)
            }
            if (conn.responseCode in 200..299) {
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                hrHigh = j.optDouble("hr_high", hrHigh)
                hrLow = j.optDouble("hr_low", hrLow)
                serverEnabled = j.optBoolean("enabled", true)
            }
        } catch (_: Throwable) {}
    }

    private fun poll() {
        val c = ctx ?: return
        syncConfig(c)
        if (!serverEnabled) return
        val client = try { HealthConnectClient.getOrCreate(c) } catch (e: Throwable) {
            if (!permWarned) { permWarned = true; GatewayState.log("健康守護：此裝置沒有 Health Connect（${e.message}）") }
            return
        }
        val granted = runBlocking { client.permissionController.getGrantedPermissions() }
        if (!granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            if (!permWarned) { permWarned = true; GatewayState.log("健康守護：尚未授權讀取心率（請在語音頁重新開啟開關授權）") }
            return
        }
        permWarned = false

        val now = Instant.now()
        val from = now.minus(WINDOW_MIN, ChronoUnit.MINUTES)
        val hr = runBlocking {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(from, now)))
        }
        val samples = hr.records.flatMap { it.samples }.sortedBy { it.time }
        if (samples.size < 3) return  // 資料還沒同步過來/樣本太少 → 不判

        val avg = samples.map { it.beatsPerMinute.toDouble() }.average()
        val steps = runBlocking {
            try {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, now)))[StepsRecord.COUNT_TOTAL] ?: 0L
            } catch (_: Throwable) { 0L }
        }

        gauge("heart_rate_high", avg >= hrHigh && steps < EXERCISE_STEPS, avg,
            "靜息心率偏高：近 ${WINDOW_MIN} 分鐘平均 ${avg.toInt()} bpm（幾乎沒有活動，警戒值 ${hrHigh.toInt()}）")
        gauge("heart_rate_low", avg <= hrLow, avg,
            "心率偏低：近 ${WINDOW_MIN} 分鐘平均 ${avg.toInt()} bpm（警戒值 ${hrLow.toInt()}）")
    }

    /** 遲滯＋節流：跨入異常只報一次，恢復正常後才能再觸發。 */
    private fun gauge(type: String, breach: Boolean, bpm: Double, rationale: String) {
        if (!breach) { inBreach.remove(type); return }
        if (!inBreach.add(type)) return
        val now = System.currentTimeMillis()
        if (now - (lastSent[type] ?: 0L) < THROTTLE_MS) return
        lastSent[type] = now
        val c = ctx ?: return
        GatewayState.log("❤️ 健康守護：$rationale")
        // 本地先開口關心（不等雲端），平台端負責推播/後續動作
        DeviceTools.speak(c, "注意，偵測到你的$rationale。還好嗎？記得休息一下。")
        thread(name = "health-report") {
            ImpactSentinel.postEvent(c, type, bpm, DeviceTools.latLng(c), spoken = true)
        }
    }
}
