package com.vito.gateway

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.mutableStateOf
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 前向警戒：鏡頭本地物體偵測（ML Kit，完全離線），偵測「逼近中的物體」即時警示。
 * 原理：追蹤每個物體的邊框佔畫面比例，面積 ∝ 1/距離²；短時間內面積快速放大＝正在逼近，
 * 由放大率估碰撞時間 TTC ≈ dt/(√growth−1)，TTC 過短或已佔滿畫面 → 立刻嗶聲＋震動＋喊「小心」。
 * 警示全在手機本地（毫秒級），只把 collision_warning 節流回報平台記錄。
 * 使用時把手機鏡頭朝前（車架/胸前/手持）。
 */
object CollisionGuard {
    @Volatile private var running = false
    private var ctx: Context? = null
    private var detector: ObjectDetector? = null
    @Volatile private var busy = false
    @Volatile private var lastProcess = 0L
    @Volatile private var lastAlert = 0L
    @Volatile private var lastReport = 0L
    private val tracks = HashMap<Int, ArrayDeque<Pair<Long, Float>>>() // trackingId → (時間, 面積比) 歷史

    private const val PROCESS_INTERVAL_MS = 180L   // 偵測節奏 ~5fps（夠即時又省電）
    private const val TRACK_WINDOW_MS = 1200L      // 放大率的觀察窗
    private const val MIN_AREA = 0.06f             // 物體至少佔畫面 6% 才評估（太遠不管）
    private const val NEAR_AREA = 0.22f            // 佔畫面 22%＝已經很近，直接警示
    private const val GROWTH_MIN = 1.15f           // 窗內面積至少放大 15% 才算逼近
    private const val TTC_ALERT_S = 1.6f           // 預估碰撞時間低於 1.6 秒 → 警示
    private const val ALERT_COOLDOWN_MS = 4000L
    private const val REPORT_THROTTLE_MS = 60_000L

    val active = mutableStateOf(false)   // UI 狀態

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        ctx = app
        detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE) // 串流模式：有 trackingId 可跨幀追蹤
                .enableMultipleObjects()
                .build()
        )
        CameraCapture.lensBack = true
        CameraCapture.start(app)
        CameraCapture.frameListener = { bmp -> onFrame(bmp) }
        running = true; active.value = true
        GatewayState.log("👁 前向警戒已啟動（本地偵測，不經雲端）")
        DeviceTools.speak(app, "前向警戒開啟，請把手機鏡頭朝向前方。")
    }

    fun stop() {
        if (!running) return
        running = false; active.value = false
        CameraCapture.frameListener = null
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
        tracks.clear()
        // 鏡頭投影若沒在用鏡頭就順手關掉（省電）
        if (VoiceEngine.liveVision.value != "camera") CameraCapture.stop()
        GatewayState.log("前向警戒已停止")
    }

    private fun onFrame(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        if (!running || busy || now - lastProcess < PROCESS_INTERVAL_MS) return
        val d = detector ?: return
        busy = true; lastProcess = now
        try {
            d.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { objs ->
                    if (!running) return@addOnSuccessListener
                    val frameArea = (bmp.width * bmp.height).toFloat()
                    var alert = false
                    var biggest = 0f
                    val seen = HashSet<Int>()
                    for (o in objs) {
                        val id = o.trackingId ?: continue
                        seen.add(id)
                        val area = o.boundingBox.width().toFloat() * o.boundingBox.height().toFloat() / frameArea
                        if (area > biggest) biggest = area
                        val h = tracks.getOrPut(id) { ArrayDeque() }
                        h.addLast(now to area)
                        while (h.isNotEmpty() && now - h.first().first > TRACK_WINDOW_MS) h.removeFirst()
                        if (area < MIN_AREA || h.size < 3) continue
                        if (area >= NEAR_AREA) { alert = true; continue }
                        val (t0, a0) = h.first()
                        val dt = (now - t0) / 1000f
                        if (dt < 0.4f || a0 <= 0.004f) continue
                        val growth = area / a0
                        if (growth >= GROWTH_MIN) {
                            val ttc = dt / (sqrt(growth) - 1f)  // 面積 ∝ 1/距離² → 由放大率估 TTC
                            if (ttc in 0f..TTC_ALERT_S) alert = true
                        }
                    }
                    tracks.keys.retainAll(seen) // 消失的物體清掉，避免記憶體累積
                    if (alert) fireAlert(biggest)
                }
                .addOnCompleteListener { busy = false }
        } catch (_: Throwable) { busy = false }
    }

    private fun fireAlert(area: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAlert < ALERT_COOLDOWN_MS) return
        lastAlert = now
        val c = ctx ?: return
        // 全本地即時警示：嗶聲（鬧鈴音量）＋震動＋喊話
        try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400) } catch (_: Throwable) {}
        DeviceTools.vibrate(c, 500)
        DeviceTools.speak(c, "小心！前方物體逼近！")
        GatewayState.log("⚠️ 前向警戒：偵測到逼近物體（佔畫面 ${"%.0f".format(area * 100)}%）")
        if (now - lastReport > REPORT_THROTTLE_MS) { // 平台只做記錄，節流送
            lastReport = now
            thread(name = "collision-report") {
                ImpactSentinel.postEvent(c, "collision_warning", area.toDouble(), DeviceTools.latLng(c), spoken = true)
            }
        }
    }
}
