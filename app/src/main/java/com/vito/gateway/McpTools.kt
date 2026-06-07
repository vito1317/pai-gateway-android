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
        tool("device_info", "回報本機（Android 裝置）型號、系統版本、IP。", JSONObject())
        fun n(d: String) = JSONObject().apply { put("type", "integer"); put("description", d) }
        tool("device_location", "取得手機目前 GPS 定位（緯經度）。", JSONObject())
        tool("phone_notify", "在手機發一則通知。", JSONObject().put("title", s("標題")).put("text", s("內容")), JSONArray().put("text"))
        tool("clipboard_set", "把文字複製到手機剪貼簿。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("clipboard_get", "讀取手機剪貼簿（App 在前景才保證可讀）。", JSONObject())
        tool("flashlight", "開關手機手電筒。", JSONObject().put("on", b("true=開")), JSONArray().put("on"))
        tool("set_volume", "設定手機媒體音量（0-100）。", JSONObject().put("percent", n("百分比")), JSONArray().put("percent"))
        tool("set_brightness", "設定手機螢幕亮度（0-100，首次需授權修改系統設定）。", JSONObject().put("percent", n("百分比")), JSONArray().put("percent"))
        tool("vibrate", "讓手機震動。", JSONObject().put("ms", n("毫秒，預設500")))
        tool("battery_status", "查手機電量與充電狀態。", JSONObject())
        tool("open_app", "開啟手機上的 app（LINE/YouTube/地圖/Chrome/Spotify/設定/相機…）。", JSONObject().put("name", s("app 名稱")), JSONArray().put("name"))
        tool("share_text", "用手機分享選單把文字/連結分享出去。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("phone_speak", "用手機系統語音念出文字（手機 TTS）。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("phone_toast", "在手機螢幕顯示一則浮動提示。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        return arr
    }

    fun call(ctx: Context, name: String, args: JSONObject): String {
        // 操作瀏覽器時：確保常駐 WebView 已建（背景也能操作）+ 自動切到瀏覽器分頁顯示（語音背景續跑）
        if (name.startsWith("browser_")) {
            BrowserController.ensureWebView(ctx)
            GatewayState.requestTab.value = "browser"
        }
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
            "device_location" -> DeviceTools.location(ctx)
            "phone_notify" -> DeviceTools.notify(ctx, args.optString("title"), args.optString("text"))
            "clipboard_set" -> DeviceTools.clipboardSet(ctx, args.optString("text"))
            "clipboard_get" -> DeviceTools.clipboardGet(ctx)
            "flashlight" -> DeviceTools.flashlight(ctx, args.optBoolean("on"))
            "set_volume" -> DeviceTools.setVolume(ctx, args.optInt("percent"))
            "set_brightness" -> DeviceTools.setBrightness(ctx, args.optInt("percent"))
            "vibrate" -> DeviceTools.vibrate(ctx, args.optLong("ms", 500))
            "battery_status" -> DeviceTools.battery(ctx)
            "open_app" -> DeviceTools.openApp(ctx, args.optString("name"))
            "share_text" -> DeviceTools.shareText(ctx, args.optString("text"))
            "phone_speak" -> DeviceTools.speak(ctx, args.optString("text"))
            "phone_toast" -> DeviceTools.toast(ctx, args.optString("text"))
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
