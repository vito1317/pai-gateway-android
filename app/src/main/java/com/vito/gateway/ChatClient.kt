package com.vito.gateway

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 一則訊息。 */
data class ChatMsg(val id: Long, val role: String, val content: String, val image: String?)

/** 對話摘要（列表用）。 */
data class ConvSummary(val id: Long, val title: String, val preview: String, val role: String?)

/**
 * 與 PAI 訊息對話 API（/api/chat/*）溝通的狀態中心。
 * 與 web 主控台共用同一批對話。認證用 X-Register-Secret（同 VisionClient）。
 */
object ChatStore {
    val conversations = mutableStateListOf<ConvSummary>()
    val messages = mutableStateListOf<ChatMsg>()
    val currentConvId = mutableStateOf<Long?>(null)   // null = 停在對話列表
    val title = mutableStateOf("")
    val loadingList = mutableStateOf(false)
    val loadingMsgs = mutableStateOf(false)
    val sending = mutableStateOf(false)
    val error = mutableStateOf("")

    private fun base(ctx: Context) = Prefs(ctx).paiBase.trimEnd('/')
    private fun token(ctx: Context) = Prefs(ctx).registerToken

    private fun post(ctx: Context, path: String, body: JSONObject, readMs: Int = 60000): JSONObject {
        val c = (URL("${base(ctx)}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10000; readTimeout = readMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Register-Secret", token(ctx))
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return try { JSONObject(txt) } catch (e: Throwable) { JSONObject().put("error", "HTTP $code") }
    }

    /** 抓對話列表。 */
    fun refreshList(ctx: Context) {
        loadingList.value = true
        thread {
            try {
                val r = post(ctx, "/api/chat/list", JSONObject(), 20000)
                val arr = r.optJSONArray("conversations") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ConvSummary(o.optLong("id"), o.optString("title", "新對話"),
                        o.optString("preview", ""), o.optString("role").ifEmpty { null })
                }
                conversations.clear(); conversations.addAll(list)
                error.value = ""
            } catch (e: Throwable) { error.value = "載入對話失敗：${e.message}" }
            finally { loadingList.value = false }
        }
    }

    /** 開一個對話、載入歷史。 */
    fun openConv(ctx: Context, id: Long) {
        currentConvId.value = id
        messages.clear()
        loadingMsgs.value = true
        thread {
            try {
                val r = post(ctx, "/api/chat/history", JSONObject().put("conversation_id", id), 20000)
                title.value = r.optString("title", "")
                val arr = r.optJSONArray("messages") ?: JSONArray()
                val msgs = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ChatMsg(o.optLong("id"), o.optString("role"), o.optString("content"),
                        o.optString("image").ifEmpty { null })
                }
                messages.clear(); messages.addAll(msgs)
                error.value = ""
            } catch (e: Throwable) { error.value = "載入訊息失敗：${e.message}" }
            finally { loadingMsgs.value = false }
        }
    }

    /** 開新對話（停在空白對話，送出第一則時才真正建立）。 */
    fun newConv() {
        currentConvId.value = 0L   // 0 = 新對話（後端會建）
        title.value = "新對話"
        messages.clear()
        error.value = ""
    }

    /** 回對話列表。 */
    fun back(ctx: Context) {
        currentConvId.value = null
        messages.clear()
        refreshList(ctx)
    }

    /**
     * 送出訊息（文字，或附一張圖）。樂觀先上自己的氣泡，再等 AI 回覆。
     */
    fun send(ctx: Context, text: String, image: Bitmap?) {
        if (sending.value) return
        if (text.isBlank() && image == null) return
        sending.value = true
        val dataUri = image?.let { encodeImage(it) }
        // 樂觀顯示使用者訊息
        messages.add(ChatMsg(-1, "user", text.ifBlank { "（圖片）" }, dataUri))
        thread {
            try {
                val body = JSONObject().apply {
                    currentConvId.value?.takeIf { it > 0 }?.let { put("conversation_id", it) }
                    if (text.isNotBlank()) put("message", text)
                    if (dataUri != null) put("image", dataUri)
                }
                val r = post(ctx, "/api/chat/send", body, 180000)   // 多步任務可能久
                val reply = r.optString("reply").ifEmpty { r.optString("error").ifEmpty { "（無回應）" } }
                val cid = r.optLong("conversation_id", currentConvId.value ?: 0L)
                if (cid > 0) currentConvId.value = cid
                messages.add(ChatMsg(-2, "assistant", reply, null))
                error.value = ""
            } catch (e: Throwable) {
                messages.add(ChatMsg(-3, "assistant", "送出失敗：${e.message}", null))
            } finally { sending.value = false }
        }
    }

    /** 終止這個對話進行中的回覆/背景任務。 */
    fun stop(ctx: Context) {
        val id = currentConvId.value ?: return
        if (id <= 0) return
        thread { try { post(ctx, "/api/chat/stop", JSONObject().put("conversation_id", id), 10000) } catch (_: Throwable) {} }
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val scale = (1280f / bitmap.width).coerceAtMost(1f)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, 1280, (bitmap.height * scale).toInt(), true) else bitmap
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, bos)
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }
}
