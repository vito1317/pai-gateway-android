package com.vito.gateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 反向連線：手機主動連 PAI（DNS/網路走 Android 正常的 Java 層），不需 cloudflared。
 *  迴圈 long-poll /api/gateway/poll 取待執行的工具呼叫 → 本地 McpTools 執行 → POST /api/gateway/result。
 *  斷線/錯誤自動重試（保活靠前景服務 + WakeLock）。
 */
object ReversePoller {
    @Volatile private var running = false
    private var ctx: android.content.Context? = null
    private lateinit var paiBase: String
    private lateinit var token: String
    private lateinit var node: String

    fun start(context: android.content.Context, paiBaseUrl: String, registerToken: String, nodeName: String) {
        if (running) return
        ctx = context.applicationContext
        paiBase = paiBaseUrl.trimEnd('/')
        token = registerToken
        node = nodeName
        running = true
        GatewayState.regStatus.value = "反向連線中…"
        GatewayState.log("反向連線啟動，輪詢 $paiBase")
        thread(name = "reverse-poll") { loop() }
    }

    fun stop() { running = false }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            try {
                val call = poll()
                backoff = 1000L
                GatewayState.regStatus.value = "🟢 已連線（反向）"
                if (call != null) {
                    val id = call.getString("id")
                    val tool = call.getString("tool")
                    val args = call.optJSONObject("arguments") ?: JSONObject()
                    GatewayState.log("收到指令：$tool")
                    val text = try {
                        McpTools.call(ctx!!, tool, args)
                    } catch (e: Throwable) { "工具執行失敗：${e.message}" }
                    submit(id, text)
                }
            } catch (e: Throwable) {
                GatewayState.regStatus.value = "重連中…(${e.message?.take(30)})"
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    /** long-poll 一次；回傳 call（JSONObject）或 null（沒有待執行）。 */
    private fun poll(): JSONObject? {
        val url = URL("$paiBase/api/gateway/poll?node=" + java.net.URLEncoder.encode(node, "UTF-8"))
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000; readTimeout = 40000   // > server 25s long-poll
            setRequestProperty("X-Register-Secret", token)
        }
        val code = c.responseCode
        if (code != 200) throw RuntimeException("poll HTTP $code")
        val body = c.inputStream.bufferedReader().readText()
        val o = JSONObject(body)
        return if (o.isNull("call")) null else o.getJSONObject("call")
    }

    private fun submit(id: String, text: String) {
        try {
            val c = (URL("$paiBase/api/gateway/result").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", token)
            }
            c.outputStream.use { it.write(JSONObject().put("id", id).put("text", text).toString().toByteArray()) }
            c.responseCode
        } catch (e: Throwable) {
            GatewayState.log("回傳結果失敗：${e.message}")
        }
    }

    /** 註冊為反向節點（帶上工具清單，PAI 不會反連我們）。 */
    fun register(paiBaseUrl: String, registerToken: String, nodeName: String): String {
        return try {
            val c = (URL(paiBaseUrl.trimEnd('/') + "/api/gateway/register").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", registerToken)
            }
            val payload = JSONObject().apply {
                put("name", nodeName); put("mode", "reverse")
                put("tools", McpTools.list())
            }.toString()
            c.outputStream.use { it.write(payload.toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) "✅ 已註冊（反向節點）" else "註冊失敗 HTTP $code：$resp"
        } catch (e: Throwable) { "註冊失敗：${e.message}" }
    }
}
