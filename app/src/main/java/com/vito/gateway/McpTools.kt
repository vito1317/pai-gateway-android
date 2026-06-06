package com.vito.gateway

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** MCP 工具定義 + 分派。對應桌面 gateway 的 MCP_TOOLS / _mcp_call_tool。 */
object McpTools {
    fun list(): JSONArray {
        val arr = JSONArray()
        fun tool(name: String, desc: String, props: JSONObject, required: JSONArray = JSONArray()) {
            arr.put(JSONObject().apply {
                put("name", name); put("description", desc)
                put("inputSchema", JSONObject().apply {
                    put("type", "object"); put("properties", props); put("required", required)
                })
            })
        }
        fun s(d: String) = JSONObject().apply { put("type", "string"); put("description", d) }
        fun b(d: String) = JSONObject().apply { put("type", "boolean"); put("description", d) }

        tool("browser_navigate", "用內建瀏覽器開啟網址，回頁面可互動元素清單（每個有 [eN] 編號）。實際操控瀏覽器的起點。",
            JSONObject().put("url", s("網址")), JSONArray().put("url"))
        tool("browser_snapshot", "回當前頁面的可互動元素清單（[eN] role \"name\"）。", JSONObject())
        tool("browser_click", "點擊元素。target 用 snapshot 的編號 eN（最準）或可見文字。",
            JSONObject().put("target", s("eN 或文字")), JSONArray().put("target"))
        tool("browser_type", "在輸入框輸入文字。target=eN或文字；submit=true 按 Enter 送出。",
            JSONObject().put("target", s("eN或文字")).put("text", s("要輸入的文字")).put("submit", b("是否送出")),
            JSONArray().put("text"))
        tool("browser_read", "讀取當前頁面可見文字（擷取/摘要用）。", JSONObject())
        tool("browser_back", "瀏覽器上一頁。", JSONObject())
        tool("browser_current_url", "取得目前網址。", JSONObject())
        tool("open_url", "用系統預設 app 開啟網址（例如丟給 Chrome、地圖、YouTube）。",
            JSONObject().put("url", s("網址")), JSONArray().put("url"))
        tool("device_info", "回報本機（Android 裝置）型號、系統版本、電量等資訊。", JSONObject())
        return arr
    }

    fun call(ctx: Context, name: String, args: JSONObject): String {
        return when (name) {
            "browser_navigate" -> BrowserController.navigate(args.optString("url"))
            "browser_snapshot" -> BrowserController.snapshot()
            "browser_click" -> BrowserController.click(args.optString("target"))
            "browser_type" -> BrowserController.type(args.optString("target"), args.optString("text"), args.optBoolean("submit"))
            "browser_read" -> BrowserController.readText()
            "browser_back" -> BrowserController.back()
            "browser_current_url" -> BrowserController.currentUrl().ifEmpty { "（尚未開啟網頁）" }
            "open_url" -> openUrl(ctx, args.optString("url"))
            "device_info" -> deviceInfo(ctx)
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    private fun openUrl(ctx: Context, url: String): String {
        return try {
            val u = if (Regex("^https?://").containsMatchIn(url)) url else "https://$url"
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "已用系統瀏覽器開啟 $u"
        } catch (e: Throwable) { "開啟失敗：${e.message}" }
    }

    private fun deviceInfo(ctx: Context): String {
        return "型號：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nIP：${Net.localIp(ctx)}"
    }
}
