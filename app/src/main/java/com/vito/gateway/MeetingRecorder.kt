package com.vito.gateway

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 會議模式錄音：16kHz 單聲道 PCM，每 20 秒包成 WAV 上傳 /api/meeting/chunk
 * （伺服器轉寫累積逐字稿）。「結束會議」→ 停止並沖出最後一段。
 * 與全雙工語音互斥（同一顆麥克風）。
 */
object MeetingRecorder {
    @Volatile private var running = false
    private var rec: AudioRecord? = null

    private const val SR = 16000
    private const val CHUNK_SEC = 20

    fun start(ctx: Context): String {
        if (running) return "已在記錄會議中"
        if (VoiceEngine.active.value) return "語音對話使用中（同一顆麥克風），先關閉語音再記會議"
        val app = ctx.applicationContext
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "沒有麥克風權限，請開啟 App → 權限 → 麥克風"
        }
        return try {
            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR * 2))
            r.startRecording()
            rec = r; running = true
            GatewayState.log("🎙️ 會議記錄開始（每 ${CHUNK_SEC}s 轉寫一段）")
            thread(name = "meeting-rec") { loop(app) }
            "會議錄音已開始"
        } catch (e: Throwable) {
            running = false; rec = null
            "錄音啟動失敗：${e.message}"
        }
    }

    fun stop(): String {
        if (!running) return "沒有在記錄會議"
        running = false
        GatewayState.log("🛑 會議記錄結束，上傳最後段落")
        return "會議錄音已結束（最後一段上傳中）"
    }

    private fun loop(app: Context) {
        val buf = ShortArray(SR) // 一次讀 ~1 秒
        var pcm = ByteArrayOutputStream()
        var secs = 0
        while (running) {
            val n = try { rec?.read(buf, 0, buf.size) ?: break } catch (_: Throwable) { break }
            if (n <= 0) continue
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(buf[i])
            pcm.write(bb.array(), 0, n * 2)
            secs++
            if (secs >= CHUNK_SEC) {
                val data = pcm.toByteArray()
                pcm = ByteArrayOutputStream(); secs = 0
                Thread { upload(app, data) }.start() // 上傳不擋錄音
            }
        }
        val tail = pcm.toByteArray()
        if (tail.size > SR) Thread { upload(app, tail) }.start() // 尾段（>0.5s 才值得傳）
        try { rec?.stop(); rec?.release() } catch (_: Throwable) {}
        rec = null
    }

    private fun upload(ctx: Context, pcm: ByteArray) {
        try {
            val p = Prefs(ctx)
            if (p.registerToken.isBlank()) return
            val body = org.json.JSONObject()
                .put("audio_base64", android.util.Base64.encodeToString(wavWrap(pcm, SR), android.util.Base64.NO_WRAP))
            val c = (java.net.URL(p.paiBase.trimEnd('/') + "/api/meeting/chunk")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 150000 // 轉寫要時間
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", p.registerToken)
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode
        } catch (e: Throwable) {
            GatewayState.log("會議段落上傳失敗：${e.message}")
        }
    }

    /** PCM16 → 標準 44-byte header WAV。 */
    private fun wavWrap(pcm: ByteArray, sr: Int): ByteArray {
        val total = 36 + pcm.size
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(total).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
