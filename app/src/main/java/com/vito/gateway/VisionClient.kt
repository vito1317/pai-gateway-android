package com.vito.gateway

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 把照片/畫面送到 PAI /api/vision（Gemma 4 看圖），結果用通知彈窗顯示 + 念出。 */
object VisionClient {
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
