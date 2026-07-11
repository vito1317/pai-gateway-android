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
    @Volatile private var confirmStreak = 0        // 連續判定逼近的輪數
    @Volatile private var bigGrowingAt = 0L        // 最近一次看到「大且持續放大」物體的時間（貼近推斷用）

    private const val PROCESS_INTERVAL_MS = 180L   // 偵測節奏 ~5fps（夠即時又省電）
    private const val TRACK_WINDOW_MS = 1500L      // 放大率的觀察窗
    private const val MIN_AREA = 0.08f             // 物體至少佔畫面 8% 才評估（太遠不管）
    private const val GROWTH_MIN = 1.35f           // 窗內面積至少放大 35% 才算逼近（1.15 會被手晃誤觸）
    private const val TTC_ALERT_S = 1.2f           // 預估碰撞時間低於 1.2 秒 → 警示
    private const val CONFIRM_FRAMES = 2           // 連續 N 輪都判定逼近才警示（濾掉單幀抖動）
    private const val ALERT_COOLDOWN_MS = 8000L
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
        confirmStreak = 0
        bigGrowingAt = 0L
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
                    var approaching = false
                    var biggest = 0f
                    var anyOnPath = false
                    val seen = HashSet<Int>()
                    for (o in objs) {
                        val id = o.trackingId ?: continue
                        seen.add(id)
                        val area = o.boundingBox.width().toFloat() * o.boundingBox.height().toFloat() / frameArea
                        // 只管「正前方路徑上」的物體：邊框中心在畫面中間帶（左右 20%~80%）
                        val cx = o.boundingBox.exactCenterX() / bmp.width
                        val onPath = cx in 0.2f..0.8f
                        if (onPath) anyOnPath = true
                        if (area > biggest) biggest = area
                        val h = tracks.getOrPut(id) { ArrayDeque() }
                        h.addLast(now to area)
                        while (h.isNotEmpty() && now - h.first().first > TRACK_WINDOW_MS) h.removeFirst()
                        if (!onPath || h.size < 3) continue
                        // 有逼近史的大物件（曾比現在小 20%+，非一開鏡頭就大）
                        val minPast = h.minOf { it.second }
                        val grewInto = minPast > 0f && area / minPast >= 1.2f
                        if (area >= 0.18f && h.last().second > h.elementAt(h.size - 2).second && grewInto) {
                            bigGrowingAt = now // 大且還在放大 → 記下時間（等等消失就是貼近）
                        }
                        if (area < MIN_AREA || h.size < 4) continue
                        // ① 已經很近且是逼近而來 → 警示
                        if (area >= 0.30f && grewInto) { approaching = true; continue }
                        // ② 中距離：必須「持續」放大（窗內 ≥70% 相鄰樣本變大，濾掉手晃）且 TTC 過短
                        var inc = 0; var cmp = 0
                        var prev = -1f
                        for ((_, a) in h) { if (prev >= 0f) { cmp++; if (a > prev) inc++ }; prev = a }
                        if (cmp == 0 || inc.toFloat() / cmp < 0.7f) continue
                        val (t0, a0) = h.first()
                        val dt = (now - t0) / 1000f
                        if (dt < 0.5f || a0 <= 0.004f) continue
                        val growth = area / a0
                        if (growth >= GROWTH_MIN) {
                            val ttc = dt / (sqrt(growth) - 1f)  // 面積 ∝ 1/距離² → 由放大率估 TTC
                            if (ttc in 0f..TTC_ALERT_S) approaching = true
                        }
                    }
                    // ③ 貼近推斷：剛剛還有「大且持續放大」的物體，現在正前方偵測整個消失
                    //   ＝它塞滿畫面被偵測器丟失（很近時偵測器看不到邊界）→ 這正是最危險的時刻
                    if (!anyOnPath && bigGrowingAt > 0L && now - bigGrowingAt < 1500L) {
                        approaching = true
                        biggest = 1f
                    }
                    tracks.keys.retainAll(seen) // 消失的物體清掉，避免記憶體累積
                    // 連續 CONFIRM_FRAMES 輪都判定逼近才真的警示（單幀抖動不叫）
                    if (approaching) {
                        confirmStreak++
                        if (confirmStreak >= CONFIRM_FRAMES) fireAlert(biggest)
                    } else confirmStreak = 0
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
