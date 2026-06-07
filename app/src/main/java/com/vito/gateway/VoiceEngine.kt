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
        transcript.value = ""; userText.value = ""; steps.value = ""
        appCtx = ctx.applicationContext
        wakeMode = wake
        session = Prefs(ctx).voiceSession   // 持久化 session → 關 App 再開接續同一段對話
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
            s.on("ai_text") { args -> transcript.value = args.getOrNull(0)?.toString() ?: "" }
            s.on("user_transcript") { args ->
                // 新的一輪使用者輸入 → 清空上一輪的處理序列
                userText.value = args.getOrNull(0)?.toString() ?: ""
                steps.value = ""
            }
            s.on("agent_step") { args -> appendStep(args.getOrNull(0)?.toString() ?: "") }
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
        geo?.let { put("geo", JSONObject().put("lat", it.first).put("lng", it.second)) }
    }

    /** 拿到新定位後補送（同 session，伺服器只更新 geo）。 */
    private fun sendPromptUpdate() {
        try { if (active.value) socket?.emit("prompt_text", buildPrompt()) } catch (_: Throwable) {}
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
