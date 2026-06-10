package com.vito.gateway

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.runtime.mutableStateOf
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 原生全雙工語音：Socket.IO 連 voice_server（/voice-rt/socket.io），
 * AudioRecord 錄 16kHz PCM → emit audio；收 24kHz PCM → AudioTrack 播放。
 * 協定對齊 web useVoiceChat：prompt_text{mode,session,wake,bargeIn} / audio / recording-started。
 */
object VoiceEngine {
    // UI 觀察狀態
    val active = mutableStateOf(false)
    val status = mutableStateOf("")
    val speaking = mutableStateOf(false)
    val volume = mutableStateOf(0f)
    val transcript = mutableStateOf("")   // AI 回覆字幕
    val userText = mutableStateOf("")      // 使用者逐字稿
    val steps = mutableStateOf("")         // 執行步驟
    val standby = mutableStateOf(false)    // 待機中（喚醒模式下睡著）→ 球變暗
    val liveVision = mutableStateOf("off") // off / screen —— 即時把畫面給 AI 看（說話時用當前畫面回覆）
    @Volatile private var liveVisionRunning = false
    val drivingMode = mutableStateOf(false) // 開車模式：免手操作、念新通知、主動問目的地

    /**
     * 切換即時畫面投影。
     * mode="screen" → 從 MediaProjectionService 拉畫面（使用者已在系統框選好 App/整個螢幕）；
     * mode="camera" → 從 CameraCapture 拉鏡頭畫面；
     * 每 ~2 秒 attach 到語音 session（短 TTL），說話時 AI 用當前畫面回覆。off=停止。
     */
    fun setLiveVision(ctx: android.content.Context, mode: String) {
        val app = ctx.applicationContext
        if (mode != "screen" && mode != "camera") {
            liveVision.value = "off"; liveVisionRunning = false
            try { MediaProjectionService.stop(app) } catch (_: Throwable) {}
            try { CameraCapture.stop() } catch (_: Throwable) {}
            try { VisionOverlay.hide() } catch (_: Throwable) {}
            sendPromptUpdate()   // 通知 voice_server：vision 關閉
            return
        }
        // 投影中 App 多半在背景 → 用懸浮視窗顯示 AI 回應
        try {
            if (!VisionOverlay.canDraw(app)) VisionOverlay.requestPermission(app)
            else { VisionOverlay.show(app); VisionOverlay.update(app, transcript.value, userText.value) }
        } catch (_: Throwable) {}
        if (mode == "camera") {
            try { CameraCapture.start(app) } catch (_: Throwable) {}
            try { MediaProjectionService.stop(app) } catch (_: Throwable) {}   // 與螢幕投影互斥
        } else if (mode == "screen") {
            try { CameraCapture.stop() } catch (_: Throwable) {}
        }
        liveVision.value = mode
        sendPromptUpdate()   // 通知 voice_server：vision 開啟 → 一律帶當前畫面回答
        if (liveVisionRunning) return   // 已在跑 → 只切來源（liveVision.value 已更新）
        liveVisionRunning = true
        thread(name = "live-vision") {
            var warned = false
            while (liveVisionRunning && active.value) {
                try {
                    val img: String? = when (liveVision.value) {
                        "screen" -> MediaProjectionService.instance?.grabB64()
                        "camera" -> CameraCapture.grabB64()
                        else -> null
                    }
                    if (img != null) { VisionClient.attachB64Sync(app, img, 20); warned = false }
                    else if (!warned) { warned = true; GatewayState.log("即時畫面：尚未取得畫面（準備中…）") }
                } catch (_: Throwable) {}
                try { Thread.sleep(2000) } catch (_: Throwable) {}
            }
            liveVisionRunning = false
        }
    }

    private var socket: Socket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    val muted = mutableStateOf(false)   // Compose 可觀察 → 靜音按鈕 icon 會即時更新
    @Volatile private var recording = false
    @Volatile private var micMuteUntil = 0L
    @Volatile private var bargeIn = true
    @Volatile private var loggedAudioType = false
    @Volatile private var loggedAudio = false
    private var appCtx: android.content.Context? = null
    @Volatile private var wakeMode = false
    @Volatile private var session = ""
    @Volatile private var nodeName = ""
    @Volatile private var geo: Pair<Double, Double>? = null

    private const val IN_RATE = 16000
    private const val OUT_RATE = 24000
    private const val BARGE_RMS = 0.06f

    fun toggleMute() { muted.value = !muted.value }
    fun isMuted() = muted.value

    fun start(ctx: android.content.Context, paiBase: String, wake: Boolean) {
        if (active.value) return
        active.value = true
        status.value = "連線中…"
        transcript.value = ""; userText.value = ""; steps.value = ""; standby.value = false
        appCtx = ctx.applicationContext
        wakeMode = wake
        session = Prefs(ctx).voiceSession   // 持久化 session → 關 App 再開接續同一段對話
        nodeName = Prefs(ctx).nodeName      // 這台裝置的節點名 → 操作預設跑「當前裝置」（平板說話就跑平板）
        // 抓定位給 geo（附近搜尋/天氣用）：先用 lastKnown，沒有就主動要一次新定位再補送
        geo = DeviceTools.latLng(appCtx!!)
        if (geo == null) DeviceTools.requestFreshLocation(appCtx!!) { la, lo -> geo = la to lo; sendPromptUpdate() }
        try {
            val origin = paiBase.trimEnd('/')
            val opts = IO.Options().apply {
                path = "/voice-rt/socket.io"
                transports = arrayOf("polling", "websocket")
                reconnectionAttempts = 5
                timeout = 12000
            }
            val s = IO.socket(URI.create(origin), opts)
            socket = s
            s.on(Socket.EVENT_CONNECT) {
                status.value = "已連線 · 請說話"
                s.emit("prompt_text", buildPrompt())
                s.emit("recording-started")
                startRecording()
            }
            s.on(Socket.EVENT_DISCONNECT) { status.value = "已斷線" }
            s.on(Socket.EVENT_CONNECT_ERROR) { status.value = "連線失敗" }
            s.on("audio") { args ->
                when (val d = args.getOrNull(0)) {
                    is ByteArray -> playPcm(d)
                    is org.json.JSONObject -> {
                        // 某些情況 binary 被包成 {audio:[...]} 或 base64
                        d.optJSONArray("audio")?.let { ja ->
                            val b = ByteArray(ja.length()) { i -> (ja.getInt(i) and 0xFF).toByte() }
                            playPcm(b)
                        }
                    }
                    is String -> try { playPcm(android.util.Base64.decode(d, android.util.Base64.DEFAULT)) } catch (e: Throwable) {}
                    else -> if (!loggedAudioType) { loggedAudioType = true; GatewayState.log("audio 型別: ${d?.javaClass?.simpleName}") }
                }
            }
            s.on("stop_tts") { stopPlayback() }
            s.on("ai_text") { args ->
                val t = args.getOrNull(0)?.toString() ?: ""
                transcript.value = t
                // 待機提示 → 進待機；其他正常回覆 → 解除待機
                standby.value = t.contains("待機") || t.contains("💤")
                if (liveVision.value != "off") appCtx?.let { VisionOverlay.update(it, t, userText.value) }
            }
            s.on("user_transcript") { args ->
                // 新的一輪使用者輸入 → 清空上一輪的處理序列、解除待機
                userText.value = args.getOrNull(0)?.toString() ?: ""
                steps.value = ""
                standby.value = false
                if (liveVision.value != "off") appCtx?.let { VisionOverlay.update(it, transcript.value, userText.value) }
            }
            s.on("agent_step") { args ->
                val t = args.getOrNull(0)?.toString() ?: ""
                if (t.contains("待機")) standby.value = true
                appendStep(t)
            }
            s.connect()
        } catch (e: Throwable) {
            status.value = "錯誤：${e.message}"; active.value = false
        }
    }

    /** 組 prompt_text（帶 geo）。session 維持不變 → 重送只更新 geo 不會另開對話。 */
    private fun buildPrompt(): JSONObject = JSONObject().apply {
        put("mode", "hybrid"); put("conversation_id", JSONObject.NULL)
        put("prompt", ""); put("session", session)
        put("wake", wakeMode); put("bargeIn", bargeIn)
        put("driving", drivingMode.value)
        put("vision", liveVision.value != "off")   // 投影/鏡頭開啟 → 語音一律上 PAI 帶當前畫面回答
        put("node", nodeName)                       // 當前裝置 → 操作預設跑這台
        geo?.let { put("geo", JSONObject().put("lat", it.first).put("lng", it.second)) }
    }

    /** 拿到新定位後補送（同 session，伺服器只更新 geo）。 */
    private fun sendPromptUpdate() {
        try { if (active.value) socket?.emit("prompt_text", buildPrompt()) } catch (_: Throwable) {}
    }

    /** 開車模式切換：重送 prompt（換 persona）；開啟時主動問目的地。 */
    fun setDriving(ctx: android.content.Context, on: Boolean) {
        drivingMode.value = on
        sendPromptUpdate()
        if (on) announceToVoice(ctx, "開車模式開啟，請問要導航去哪裡？")
    }

    /** 主動讓語音念一句（開車模式問目的地、念通知用）。POST /api/voice/announce。 */
    fun announceToVoice(ctx: android.content.Context, text: String) {
        val app = ctx.applicationContext
        val sess = session
        kotlin.concurrent.thread {
            try {
                val prefs = Prefs(app)
                val body = org.json.JSONObject().apply { put("session", sess); put("text", text) }.toString()
                val c = (java.net.URL("${prefs.paiBase.trimEnd('/')}/api/voice/announce").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 8000; readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
            } catch (_: Throwable) {}
        }
    }

    /** 累積處理序列：每步一行、去掉連續重複、最多保留最近 12 步。 */
    private fun appendStep(step: String) {
        val s = step.trim()
        if (s.isEmpty()) return
        val lines = steps.value.split("\n").filter { it.isNotBlank() }.toMutableList()
        if (lines.lastOrNull() == s) return   // 與上一步相同就不重複
        lines.add(s)
        steps.value = lines.takeLast(12).joinToString("\n")
    }

    fun stop() {
        active.value = false; recording = false; speaking.value = false; status.value = ""
        liveVisionRunning = false; liveVision.value = "off"
        try { appCtx?.let { MediaProjectionService.stop(it) } } catch (_: Throwable) {}
        try { CameraCapture.stop() } catch (_: Throwable) {}
        try { VisionOverlay.hide() } catch (_: Throwable) {}
        drivingMode.value = false
        try { socket?.emit("recording-stopped") } catch (e: Throwable) {}
        try { socket?.disconnect() } catch (e: Throwable) {}
        socket = null
        try { recorder?.stop(); recorder?.release() } catch (e: Throwable) {}
        recorder = null
        stopPlayback()
    }

    private fun startRecording() {
        recording = true
        thread(name = "voice-mic") {
            val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = 2048
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 2))
            } catch (e: Throwable) { status.value = "麥克風開啟失敗"; return@thread }
            recorder = rec
            val buf = ShortArray(chunk)
            try { rec.startRecording() } catch (e: Throwable) { status.value = "需要麥克風權限"; return@thread }
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // 計算音量（RMS）
                var sum = 0.0
                for (i in 0 until n) { val f = buf[i] / 32768.0; sum += f * f }
                val rms = sqrt(sum / n).toFloat()
                // 播放預計時間已過（最後一段音訊播完）→ 關閉「回應中」，回到聆聽
                if (speaking.value && System.currentTimeMillis() > micMuteUntil) speaking.value = false
                // 靜音：能量歸零（停止動畫，不要看起來還在抓聲音）+ 不送音訊
                if (muted.value) { volume.value = 0f; continue }
                volume.value = rms
                // AI 播放期間：只送明顯人聲（打斷），其餘丟棄防回授
                if (System.currentTimeMillis() < micMuteUntil && (!bargeIn || rms < BARGE_RMS)) continue
                // PCM16 → JSON int array（與 web 協定一致）
                val arr = JSONArray()
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    arr.put(v and 0xFF); arr.put((v shr 8) and 0xFF) // little-endian bytes
                }
                socket?.emit("audio", JSONObject().apply { put("audio", arr); put("sample_rate", IN_RATE) })
            }
        }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minBuf, OUT_RATE), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        t.play()
        track = t
        return t
    }

    private fun playPcm(bytes: ByteArray) {
        try {
            val t = ensureTrack()
            if (!loggedAudio) {
                loggedAudio = true
                GatewayState.log("▶ 收到音訊 ${bytes.size}B, playState=${t.playState}, state=${t.state}")
            }
            val n = t.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
            if (n < 0) GatewayState.log("AudioTrack write 失敗: $n")
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            speaking.value = true
            val durMs = (bytes.size / 2.0 / OUT_RATE * 1000).toLong()
            micMuteUntil = System.currentTimeMillis() + durMs + 600
        } catch (e: Throwable) { GatewayState.log("播放失敗: ${e.message}") }
    }

    private fun stopPlayback() {
        speaking.value = false
        try { track?.pause(); track?.flush() } catch (e: Throwable) {}
    }

    private fun Array<Any>.getOrNull(i: Int): Any? = if (i < size) this[i] else null
}
