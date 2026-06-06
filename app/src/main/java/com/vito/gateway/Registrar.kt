package com.vito.gateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 把本節點註冊到 PAI（POST /api/gateway/register），與桌面 gw reregister 一致。 */
object Registrar {
    fun register(paiBase: String, token: String, name: String, mcpUrl: String, secret: String): String {
        return try {
            val url = URL(paiBase.trimEnd('/') + "/api/gateway/register")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000; readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Register-Secret", token)
            }
            val payload = JSONObject().apply {
                put("name", name); put("url", mcpUrl); put("secret", secret)
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) "✅ 已註冊到 PAI（$mcpUrl）" else "註冊失敗 HTTP $code：$resp"
        } catch (e: Throwable) {
            "註冊失敗：${e.message}"
        }
    }
}
