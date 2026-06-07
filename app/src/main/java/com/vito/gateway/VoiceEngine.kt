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
    @Volatile private var recording = false
    @Volatile private var micMuteUntil = 0L
    @Volatile private var muted = false
    @Volatile private var bargeIn = true

    private const val IN_RATE = 16000
    private const val OUT_RATE = 24000
    private const val BARGE_RMS = 0.06f

    fun toggleMute() { muted = !muted }
    fun isMuted() = muted

    fun start(paiBase: String, wake: Boolean) {
        if (active.value) return
        active.value = true
        status.value = "連線中…"
        transcript.value = ""; userText.value = ""; steps.value = ""
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
                val p = JSONObject().apply {
                    put("mode", "hybrid"); put("conversation_id", JSONObject.NULL)
                    put("prompt", ""); put("session", "android-" + System.currentTimeMillis())
                    put("wake", wake); put("bargeIn", bargeIn)
                }
                s.emit("prompt_text", p)
                s.emit("recording-started")
                startRecording()
            }
            s.on(Socket.EVENT_DISCONNECT) { status.value = "已斷線" }
            s.on(Socket.EVENT_CONNECT_ERROR) { status.value = "連線失敗" }
            s.on("audio") { args -> (args.getOrNull(0) as? ByteArray)?.let { playPcm(it) } }
            s.on("stop_tts") { stopPlayback() }
            s.on("ai_text") { args -> transcript.value = args.getOrNull(0)?.toString() ?: "" }
            s.on("user_transcript") { args -> userText.value = args.getOrNull(0)?.toString() ?: "" }
            s.on("agent_step") { args -> steps.value = args.getOrNull(0)?.toString() ?: "" }
            s.connect()
        } catch (e: Throwable) {
            status.value = "錯誤：${e.message}"; active.value = false
        }
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
                volume.value = rms
                if (muted) continue
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
            t.write(bytes, 0, bytes.size)
            speaking.value = true
            // 播放期間靜音麥克風（加緩衝防回授）；barge-in 例外（明顯人聲仍送）
            val durMs = (bytes.size / 2.0 / OUT_RATE * 1000).toLong()
            micMuteUntil = System.currentTimeMillis() + durMs + 600
        } catch (e: Throwable) {}
    }

    private fun stopPlayback() {
        speaking.value = false
        try { track?.pause(); track?.flush() } catch (e: Throwable) {}
    }

    private fun Array<Any>.getOrNull(i: Int): Any? = if (i < size) this[i] else null
}
