package com.vito.gateway

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 把照片/畫面送到 PAI /api/vision（Gemma 4 看圖），結果用通知彈窗顯示 + 念出。 */
object VisionClient {
    /** 縮圖 + 轉 base64 data URI。 */
    private fun encode(bitmap: Bitmap): String {
        val scale = (1080f / bitmap.width).coerceAtMost(1f)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, 1080, (bitmap.height * scale).toInt(), true) else bitmap
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * 即時投影用：把一張畫面（已是 data URI）attach 到語音 session，帶短 TTL（停止推送後自動過期）。
     * 同步執行（給擷取迴圈的背景執行緒呼叫）；失敗回 false。
     */
    fun attachB64Sync(ctx: android.content.Context, dataUri: String, ttlSec: Int): Boolean {
        if (dataUri.isBlank()) return false
        return try {
            val prefs = Prefs(ctx)
            val body = JSONObject().apply {
                put("image", dataUri); put("session", prefs.voiceSession); put("ttl", ttlSec)
            }.toString()
            val c = (URL("${prefs.paiBase.trimEnd('/')}/api/vision/attach").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", prefs.registerToken)
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode in 200..299
        } catch (e: Throwable) { false }
    }

    /** 把照片掛進「語音對話」session → 之後用語音追問都會帶這張圖。 */
    fun attachToVoice(ctx: android.content.Context, bitmap: Bitmap) {
        val prefs = Prefs(ctx)
        val base = prefs.paiBase.trimEnd('/')
        GatewayState.log("📷 照片已附到語音對話，直接開口問吧")
        thread {
            try {
                val body = JSONObject().apply {
                    put("image", encode(bitmap)); put("session", prefs.voiceSession)
                }.toString()
                val c = (URL("$base/api/vision/attach").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 10000; readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode in 200..299) {
                    try { DeviceTools.toast(ctx, "📷 照片已給 AI，直接問它吧") } catch (_: Throwable) {}
                } else GatewayState.log("附圖失敗 HTTP ${c.responseCode}")
            } catch (e: Throwable) { GatewayState.log("附圖失敗：${e.message}") }
        }
    }

    fun analyze(ctx: android.content.Context, bitmap: Bitmap, prompt: String) {
        val prefs = Prefs(ctx)
        val base = prefs.paiBase.trimEnd('/')
        val token = prefs.registerToken
        GatewayState.log("📷 送出照片給 AI 看…")
        thread {
            try {
                // 縮到寬 1080、JPEG 75
                val scale = (1080f / bitmap.width).coerceAtMost(1f)
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, 1080, (bitmap.height * scale).toInt(), true) else bitmap
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                val body = JSONObject().apply {
                    put("image", "data:image/jpeg;base64,$b64")
                    put("prompt", prompt)
                    put("session", "android-vision-${prefs.nodeName}")
                }.toString()
                val c = (URL("$base/api/vision").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 10000; readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Register-Secret", token)
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                val reply = try { JSONObject(resp).optString("reply").ifEmpty { JSONObject(resp).optString("error") } } catch (e: Throwable) { resp.take(200) }
                GatewayState.noticeTitle.value = "👁 看圖結果"
                GatewayState.noticeUrl.value = ""
                GatewayState.noticeText.value = reply.ifEmpty { "（無回應）" }
                try { DeviceTools.speak(ctx, reply) } catch (_: Throwable) {}
            } catch (e: Throwable) {
                GatewayState.noticeTitle.value = "👁 看圖失敗"
                GatewayState.noticeText.value = "送出照片失敗：${e.message}"
            }
        }
    }
}
