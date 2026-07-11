package com.vito.gateway

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 安全哨兵：加速度計撞擊/跌倒偵測。
 * 關鍵設計：判斷與第一時間反應（震動＋TTS「你還好嗎」）全在手機本地、零網路延遲；
 * 之後才回報平台 /api/sensor/event 啟動「確認 → 沒回應自動求援（通知緊急聯絡＋定位）」流程。
 *
 * 偵測：
 *   撞擊 impact — 合成加速度瞬間 ≥ IMPACT_G（車禍/重摔手機都在此量級之上）
 *   跌倒 fall   — 自由落體（≈0g 持續一小段）後短窗內出現落地衝擊（人/手機墜落的特徵波形）
 * 註：手機掉落與人跌倒波形難以區分，會有誤報——但誤報成本低：
 * 手機問一聲「你還好嗎」，說「我沒事」即解除；預設求援也只通知你自己的頻道。
 */
object ImpactSentinel : SensorEventListener {
    @Volatile private var running = false
    private var ctx: Context? = null
    private var sm: SensorManager? = null

    private const val IMPACT_G = 5.0f          // 劇烈撞擊門檻（一般甩動/跑跳約 2~4g）
    private const val FALL_FREE_G = 0.35f      // 自由落體階段（接近 0g）
    private const val FALL_FREE_MS = 120L      // 自由落體需持續（≈0.5 公尺以上的墜落）
    private const val FALL_IMPACT_G = 2.8f     // 落地衝擊門檻
    private const val FALL_WINDOW_MS = 1200L   // 自由落體結束後，等待落地衝擊的時間窗
    private const val DEBOUNCE_MS = 60_000L    // 觸發後冷卻（平台端同時也有 already_pending 防重）

    private var freeFallStart = 0L
    @Volatile private var freeFallUntil = 0L
    @Volatile private var lastTrigger = 0L

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        if (!Prefs(app).impactGuard) return
        val mgr = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) { GatewayState.log("安全哨兵：此裝置沒有加速度計"); return }
        ctx = app; sm = mgr
        mgr.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        running = true
        GatewayState.log("🛡 安全哨兵已啟動（撞擊/跌倒偵測，本地判斷）")
    }

    fun stop() {
        if (!running) return
        running = false
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        GatewayState.log("安全哨兵已停止")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        val now = System.currentTimeMillis()
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
            SensorManager.GRAVITY_EARTH

        if (g < FALL_FREE_G) { // 自由落體階段：累積持續時間
            if (freeFallStart == 0L) freeFallStart = now
            else if (now - freeFallStart >= FALL_FREE_MS) freeFallUntil = now + FALL_WINDOW_MS
            return
        }
        freeFallStart = 0L
        if (now - lastTrigger < DEBOUNCE_MS) return
        if (now <= freeFallUntil && g >= FALL_IMPACT_G) { // 跌倒：自由落體後的落地衝擊
            freeFallUntil = 0L
            trigger("fall", g)
            return
        }
        if (g >= IMPACT_G) trigger("impact", g)
    }

    private fun trigger(type: String, g: Float) {
        lastTrigger = System.currentTimeMillis()
        val c = ctx ?: return
        val label = if (type == "fall") "疑似跌倒" else "劇烈撞擊"
        GatewayState.log("🚨 安全哨兵：$label（${"%.1f".format(g)}g）")
        // 本地零延遲反應：先震動＋開口問，不等任何網路
        DeviceTools.vibrate(c, 800)
        DeviceTools.speak(c, "偵測到$label，你還好嗎？跟我說「我沒事」就解除，沒回應我會自動求援。")
        // 回報平台：啟動確認倒數（語音待答＋通知按鈕＋逾時求援）
        thread(name = "impact-report") {
            postEvent(c, type, g.toDouble(), DeviceTools.latLng(c), spoken = true)
        }
    }

    /** 共用的事件回報（CollisionGuard 也用）。 */
    fun postEvent(c: Context, type: String, magnitude: Double, loc: Pair<Double, Double>?, spoken: Boolean): Boolean {
        return try {
            val p = Prefs(c)
            if (p.registerToken.isBlank()) return false
            val body = JSONObject().put("type", type).put("magnitude", magnitude).put("spoken", spoken)
            loc?.let { body.put("lat", it.first).put("lng", it.second) }
            val cc = (URL(p.paiBase.trimEnd('/') + "/api/sensor/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", p.registerToken)
            }
            cc.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = cc.responseCode in 200..299
            if (!ok) GatewayState.log("哨兵回報失敗：HTTP ${cc.responseCode}")
            ok
        } catch (e: Throwable) {
            GatewayState.log("哨兵回報失敗：${e.message}")
            false
        }
    }
}
