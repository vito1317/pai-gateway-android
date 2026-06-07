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
        tool("browser_reload", "重新載入當前網頁（頁面空白/載入失敗/地圖沒渲染時用）。", JSONObject())
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
        tool("open_app", "開啟手機上的 app（LINE/YouTube/地圖/Chrome/Spotify/設定/相機…，會查實際安裝的 App 模糊比對名稱）。", JSONObject().put("name", s("app 名稱")), JSONArray().put("name"))
        tool("list_apps", "列出手機已安裝、可開啟的 App（顯示名稱＋套件名）。不確定某 App 在不在、或要給使用者選時用。", JSONObject())
        tool("phone_call", "用手機撥打電話。to=電話號碼或聯絡人名稱（會自動查通訊錄）。有授權直接撥出，否則開撥號畫面帶好號碼。",
            JSONObject().put("to", s("電話號碼或聯絡人名稱")), JSONArray().put("to"))
        tool("notifications_list", "讀取手機最近通知（LINE/訊息等，最多15則，標示哪些可直接回覆）。需開啟「通知存取」權限（未開會自動帶去設定頁）。", JSONObject())
        tool("notification_reply", "直接回覆通知訊息（如 LINE，不用打開 App）。target=App名或通知標題關鍵字（如 LINE 或對方名字）；message=回覆內容。先用 notifications_list 確認有可回覆的通知。",
            JSONObject().put("target", s("App名或通知標題關鍵字")).put("message", s("回覆內容")), JSONArray().put("target").put("message"))
        tool("screen_snapshot", "讀取手機目前畫面的可互動元素清單（[sN] 編號＋文字，任何 App 都行）。操作 LINE 等手機 App 的起點（先 open_app 再 snapshot）。需開啟「協助工具」權限。", JSONObject())
        tool("screen_click", "點擊手機畫面元素。target=sN 編號（最準）或可見文字。", JSONObject().put("target", s("sN 或可見文字")), JSONArray().put("target"))
        tool("screen_type", "在手機畫面輸入框輸入文字。target=sN 或文字（會先點它聚焦，可留空=目前焦點）；text=要輸入的內容。",
            JSONObject().put("target", s("sN 或文字，可空")).put("text", s("輸入內容")), JSONArray().put("text"))
        tool("screen_swipe", "滑動手機畫面。direction=up/down/left/right（up=往上滑看更多）。", JSONObject().put("direction", s("up|down|left|right")), JSONArray().put("direction"))
        tool("screen_back", "按手機返回鍵。", JSONObject())
        tool("screen_home", "回手機主畫面。", JSONObject())
        tool("screen_shot", "手機截圖給你「看」（你能直接看圖）。screen_snapshot 元素讀不懂、畫面是圖片/地圖/遊戲、或想確認畫面長怎樣時用。", JSONObject())
        tool("play_music", "在手機播放音樂：叫起預設音樂 App（YouTube Music/Spotify）直接播。query=歌名/歌手；留空=開 YouTube Music。",
            JSONObject().put("query", s("歌名或歌手")))
        tool("media_control", "控制手機媒體播放（對任何在播的音樂 App 有效）：pause/play/next/previous。",
            JSONObject().put("action", s("pause|play|next|previous")), JSONArray().put("action"))
        tool("maps_route", "在原生 Google 地圖 App【顯示路線/導航】（要在地圖上看到路線就用這個，內建瀏覽器渲染不出地圖）。destination 必填；origin 留空=從目前位置；waypoints 多個途經點用「|」分隔；mode=driving/walking/transit/bicycling。",
            JSONObject().put("origin", s("起點，留空=目前位置")).put("destination", s("終點")).put("waypoints", s("途經點，用|分隔")).put("mode", s("交通方式 driving/walking/transit/bicycling")),
            JSONArray().put("destination"))
        tool("share_text", "用手機分享選單把文字/連結分享出去。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("phone_speak", "用手機系統語音念出文字（手機 TTS）。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("phone_toast", "在手機螢幕顯示一則浮動提示。", JSONObject().put("text", s("文字")), JSONArray().put("text"))
        tool("show_document", "把一份文件/報告/長內容『自動彈出』顯示在使用者手機（App 在前景直接彈窗顯示完整內容，背景則發通知點開）。整理報告、行程、總結、產生文件輸出給使用者時用這個——比 phone_notify 適合長內容。可選 url 附上可下載/分享的連結。",
            JSONObject().put("title", s("標題")).put("content", s("文件完整內容（支援 markdown）")).put("url", s("可選：檔案/分享連結")),
            JSONArray().put("content"))
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
            "browser_reload" -> BrowserController.reload()
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
            "list_apps" -> DeviceTools.listApps(ctx)
            "play_music" -> DeviceTools.playMusic(ctx, args.optString("query"))
            "media_control" -> DeviceTools.mediaControl(ctx, args.optString("action"))
            "phone_call" -> DeviceTools.phoneCall(ctx, args.optString("to"))
            "notifications_list" -> {
                if (!NotificationListener.isEnabled(ctx)) {
                    NotificationListener.openSettings(ctx)
                    "尚未開啟「通知存取」——已開啟設定頁，請啟用「PAI Gateway 通知存取」後再試一次"
                } else NotificationListener.recent.take(15)
                    .joinToString("\n") { n -> "[${n.app}] ${n.title}：${n.text}" + (if (n.canReply) "（可回覆）" else "") }
                    .ifEmpty { "（最近沒有收到通知）" }
            }
            "notification_reply" -> {
                val inst = NotificationListener.instance
                if (inst == null) {
                    NotificationListener.openSettings(ctx)
                    "通知存取未連接——已開啟設定頁，請啟用「PAI Gateway 通知存取」後再試"
                } else {
                    val t = args.optString("target"); val msg = args.optString("message")
                    val note = NotificationListener.recent.firstOrNull {
                        (it.app.contains(t, true) || it.title.contains(t, true) || it.text.contains(t, true)) && it.canReply
                    }
                    if (note == null) "找不到可回覆的「$t」通知（先用 notifications_list 看清單）"
                    else inst.reply(note.key, msg).also { GatewayState.log("回覆 ${note.app}/${note.title}：$it") }
                }
            }
            "screen_snapshot" -> PhoneAccessibilityService.instance?.snapshot() ?: accNeed(ctx)
            "screen_click" -> PhoneAccessibilityService.instance?.click(args.optString("target")) ?: accNeed(ctx)
            "screen_type" -> PhoneAccessibilityService.instance?.type(args.optString("target"), args.optString("text")) ?: accNeed(ctx)
            "screen_swipe" -> PhoneAccessibilityService.instance?.swipe(args.optString("direction")) ?: accNeed(ctx)
            "screen_back" -> PhoneAccessibilityService.instance?.back() ?: accNeed(ctx)
            "screen_home" -> PhoneAccessibilityService.instance?.home() ?: accNeed(ctx)
            "screen_shot" -> PhoneAccessibilityService.instance?.screenshotB64() ?: accNeed(ctx)
            "maps_route" -> DeviceTools.mapsRoute(ctx, args.optString("origin"), args.optString("destination"), args.optString("waypoints"), args.optString("mode"))
            "share_text" -> DeviceTools.shareText(ctx, args.optString("text"))
            "phone_speak" -> DeviceTools.speak(ctx, args.optString("text"))
            "phone_toast" -> DeviceTools.toast(ctx, args.optString("text"))
            "show_document" -> {
                val content = args.optString("content").ifEmpty { args.optString("text") }
                val title = args.optString("title").ifEmpty { "PAI 文件" }
                GatewayState.noticeUrl.value = args.optString("url")
                GatewayState.noticeTitle.value = title
                GatewayState.noticeText.value = content   // App 前景 → dialog 自動彈出
                DeviceTools.notify(ctx, title, content)    // App 背景 → 發通知點開
                "已在使用者手機顯示文件"
            }
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    /** 輔助使用服務未連接 → 區分「沒開」與「開了但服務沒連上（常見於小米/HyperOS 把它殺掉）」。 */
    private fun accNeed(ctx: Context): String {
        return if (PhoneAccessibilityService.isEnabled(ctx)) {
            // 設定裡是開的，但服務沒連上來（被系統殺掉/尚未啟動）
            "「協助工具」在設定裡是開啟的，但服務沒有在執行（常見於小米/HyperOS 等把它背景關閉）。" +
                "請：①到「設定→協助工具」把『PAI Gateway 螢幕操作』關掉再重新開啟一次；" +
                "②到 App 設定給『自動啟動』權限、電池設為『不限制』，避免被系統殺掉。"
        } else {
            PhoneAccessibilityService.openSettings(ctx)
            "尚未開啟「協助工具（輔助使用）」權限——已開啟設定頁，請啟用「PAI Gateway 螢幕操作」後再試"
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
