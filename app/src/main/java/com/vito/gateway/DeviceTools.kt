package com.vito.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** 手機特有能力（定位/通知/剪貼簿/手電筒/音量/亮度/震動/電量/開App/分享/TTS）。 */
object DeviceTools {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) = main.post(block)

    fun location(ctx: Context): String {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provs = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: android.location.Location? = null
            for (p in provs) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.time > best!!.time) best = l
                } catch (e: SecurityException) { return "缺定位權限（請到 App 設定開啟）" }
            }
            best?.let { "緯度 ${it.latitude}, 經度 ${it.longitude}（精度約 ${it.accuracy.toInt()} 公尺）" }
                ?: "暫時取不到定位（請確認已開 GPS 並到戶外/開過地圖一次）"
        } catch (e: Throwable) { "定位失敗：${e.message}" }
    }

    /** 取最近一次已知座標（即時、可能為 null）。給語音 geo 用。 */
    fun latLng(ctx: Context): Pair<Double, Double>? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try { val l = lm.getLastKnownLocation(p) ?: continue; if (best == null || l.time > best!!.time) best = l } catch (_: SecurityException) {}
            }
            best?.let { it.latitude to it.longitude }
        } catch (_: Throwable) { null }
    }

    /** 主動要一次新定位（lastKnown 為空時用），拿到就回呼一次。 */
    fun requestFreshLocation(ctx: Context, cb: (Double, Double) -> Unit) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(l: android.location.Location) { cb(l.latitude, l.longitude); try { lm.removeUpdates(this) } catch (_: Throwable) {} }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            }
            val prov = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            main.post { try { @Suppress("DEPRECATION") lm.requestSingleUpdate(prov, listener, null) } catch (_: SecurityException) {} catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    fun notify(ctx: Context, title: String, text: String): String {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "pai-msg"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "PAI 訊息", NotificationManager.IMPORTANCE_HIGH))
            // 點通知 → 開 App 顯示完整內容（任務結果可能很長，通知列看不完整）
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("notice_title", title); putExtra("notice_text", text)
            }
            val flag = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, text.hashCode(), intent, flag)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            nm.notify((System.currentTimeMillis() % 100000).toInt(),
                b.setContentTitle(title.ifEmpty { "PAI" }).setContentText(text.lineSequence().firstOrNull() ?: text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true)
                    .setContentIntent(pi)
                    .setStyle(Notification.BigTextStyle().bigText(text)).build())
            "已發送通知到手機"
        } catch (e: Throwable) { "通知失敗：${e.message}" }
    }

    fun clipboardSet(ctx: Context, text: String): String {
        return try {
            ui { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("PAI", text)) }
            "已複製到手機剪貼簿"
        } catch (e: Throwable) { "複製失敗：${e.message}" }
    }

    fun clipboardGet(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (t.isNullOrEmpty()) "剪貼簿是空的，或 Android 限制背景讀取（需 App 在前景）" else t
        } catch (e: Throwable) { "讀剪貼簿失敗：${e.message}" }
    }

    fun flashlight(ctx: Context, on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "此裝置沒有閃光燈"
            cm.setTorchMode(id, on)
            if (on) "已開啟手電筒" else "已關閉手電筒"
        } catch (e: Throwable) { "手電筒控制失敗：${e.message}" }
    }

    fun setVolume(ctx: Context, percent: Int): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = (percent.coerceIn(0, 100) * max / 100.0).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            "已把音量設為 $percent%"
        } catch (e: Throwable) { "音量設定失敗：${e.message}" }
    }

    fun setBrightness(ctx: Context, percent: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            ui {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {}
            }
            return "需要「修改系統設定」權限——已開啟設定頁，請授權後再試一次"
        }
        return try {
            val v = (percent.coerceIn(0, 100) * 255 / 100.0).toInt()
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "已把螢幕亮度設為 $percent%"
        } catch (e: Throwable) { "亮度設定失敗：${e.message}" }
    }

    fun vibrate(ctx: Context, ms: Long): String {
        return try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
            "已震動 ${ms}ms"
        } catch (e: Throwable) { "震動失敗：${e.message}" }
    }

    fun battery(ctx: Context): String {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val lvl = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            "電量 $lvl%${if (charging) "（充電中）" else ""}"
        } catch (e: Throwable) { "讀電量失敗：${e.message}" }
    }

    fun openApp(ctx: Context, query: String): String {
        return try {
            val pm = ctx.packageManager
            val known = mapOf(
                "line" to "jp.naver.line.android", "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome", "地圖" to "com.google.android.apps.maps",
                "maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm",
                "相機" to null, "設定" to null, "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana", "messenger" to "com.facebook.orca",
                "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
            )
            val q = query.trim().lowercase()
            // 1) 系統功能
            if (q.contains("設定")) { ui { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return "已開啟設定" }
            if (q.contains("相機")) { ui { ctx.startActivity(Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return "已開啟相機" }
            // 2) 常見 App 套件對照
            var pkg = known[q] ?: known.entries.firstOrNull { q.isNotEmpty() && q.contains(it.key) }?.value
            // 3) 找不到 → 查所有已安裝 App，用顯示名稱模糊比對（需 QUERY_ALL_PACKAGES）
            if (pkg == null) pkg = findInstalledByLabel(ctx, query.trim())
            val intent = pkg?.let { pm.getLaunchIntentForPackage(it) }
                ?: return "找不到 app「$query」（可先用 list_apps 看手機裝了哪些 App）"
            ui { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "已開啟「$query」"
        } catch (e: Throwable) { "開啟 app 失敗：${e.message}" }
    }

    /** 列出已安裝（可啟動）的 App：顯示名稱（套件名）。給 AI 知道手機有哪些 App 可開。 */
    fun listApps(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(main, 0)
                .mapNotNull { ri ->
                    val label = ri.loadLabel(pm)?.toString()?.trim() ?: return@mapNotNull null
                    if (label.isEmpty()) null else label to ri.activityInfo.packageName
                }
                .distinctBy { it.second }
                .sortedBy { it.first }
            if (apps.isEmpty()) return "讀不到 App 清單（可能缺套件可見性權限）"
            "已安裝 App（共 ${apps.size}）：\n" + apps.joinToString("\n") { "・${it.first}（${it.second}）" }
        } catch (e: Throwable) { "讀取 App 清單失敗：${e.message}" }
    }

    /** 依顯示名稱模糊找已安裝 App 的套件名（先精準、再包含）。 */
    private fun findInstalledByLabel(ctx: Context, name: String): String? {
        return try {
            val pm = ctx.packageManager
            val n = name.trim().lowercase()
            if (n.isEmpty()) return null
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0).mapNotNull { ri ->
                val label = ri.loadLabel(pm)?.toString()?.trim()?.lowercase() ?: return@mapNotNull null
                label to ri.activityInfo.packageName
            }
            list.firstOrNull { it.first == n }?.second
                ?: list.firstOrNull { it.first.contains(n) || n.contains(it.first) }?.second
        } catch (_: Throwable) { null }
    }

    fun openUrlPublic(ctx: Context, url: String): String {
        return try {
            val u = if (Regex("^https?://").containsMatchIn(url)) url else "https://$url"
            ui { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "已開啟連結"
        } catch (e: Throwable) { "開啟失敗：${e.message}" }
    }

    /** 在原生日曆 App 建立事件（ACTION_INSERT 預填，使用者確認後儲存）。
     *  beginMillis/endMillis = epoch 毫秒；endMillis 留 0 = 開始後 1 小時。 */
    fun addCalendarEvent(ctx: Context, title: String, beginMillis: Long, endMillis: Long, location: String): String {
        if (title.isBlank()) return "請提供事件標題"
        if (beginMillis <= 0) return "請提供事件時間"
        val end = if (endMillis > beginMillis) endMillis else beginMillis + 3600_000L
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, title)
                if (location.isNotBlank()) putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ui { ctx.startActivity(intent) }
            val t = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(beginMillis))
            "已開啟日曆預填事件「$title」（$t），請按儲存"
        } catch (e: Throwable) { "建立行事曆事件失敗：${e.message}" }
    }

    /** 撥打電話：to 可以是號碼或聯絡人名稱（自動查通訊錄）。
     *  有 CALL_PHONE 權限 → 直接撥出；沒有 → 開撥號畫面帶好號碼（按一下撥出）。 */
    fun phoneCall(ctx: Context, to: String): String {
        val target = to.trim()
        if (target.isEmpty()) return "請提供電話號碼或聯絡人名稱"
        val digits = target.count { it.isDigit() }
        val number = if (digits >= 3) target.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        else lookupContact(ctx, target) ?: return "通訊錄找不到「$target」（或未授權讀取聯絡人）"
        return try {
            val uri = android.net.Uri.parse("tel:$number")
            val canCall = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.CALL_PHONE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ui {
                try {
                    ctx.startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {
                    try { ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                }
            }
            val who = if (number != target) "$target（$number）" else number
            if (canCall) "正在撥打 $who" else "已開啟撥號畫面：$who（請按撥出鍵）"
        } catch (e: Throwable) { "撥打失敗：${e.message}" }
    }

    /** 依名稱查通訊錄電話（模糊比對 display name，取第一筆）。 */
    private fun lookupContact(ctx: Context, name: String): String? {
        return try {
            val p = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            ctx.contentResolver.query(
                p,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Throwable) { null }
    }

    /** 播放音樂：用 Android 標準「依搜尋播放」intent 叫起預設音樂 App（YouTube Music/Spotify）直接播；
     *  沒有 App 接手就退回開 YouTube 搜尋。query 留空＝開 YouTube Music 首頁。 */
    fun playMusic(ctx: Context, query: String): String {
        if (query.isBlank()) return openUrlPublic(ctx, "https://music.youtube.com/")
        val fallback = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)
        ui {
            try {
                ctx.startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, query)
                    putExtra(android.app.SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Throwable) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {}
            }
        }
        return "已請音樂 App 播放「$query」"
    }

    /** 媒體控制（暫停/繼續/下一首/上一首）：送系統媒體鍵，對任何正在播的音樂 App 都有效。 */
    fun mediaControl(ctx: Context, action: String): String {
        return try {
            val key = when (action.lowercase().trim()) {
                "pause", "stop", "暫停", "停止" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "play", "resume", "繼續", "播放" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "next", "下一首" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev", "上一首" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            "已送出媒體控制：$action"
        } catch (e: Throwable) { "媒體控制失敗：${e.message}" }
    }

    /** 在原生 Google 地圖 App 顯示路線（WebView 渲染不出地圖，原生 App 一定行）。
     *  destination 必填；origin 空白＝從目前位置；waypoints 用「|」分隔多個途經點。 */
    fun mapsRoute(ctx: Context, origin: String, destination: String, waypoints: String, mode: String): String {
        return try {
            val enc = { s: String -> android.net.Uri.encode(s) }
            val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
            if (origin.isNotBlank()) sb.append("&origin=").append(enc(origin))
            sb.append("&destination=").append(enc(destination))
            if (waypoints.isNotBlank()) sb.append("&waypoints=").append(enc(waypoints))
            sb.append("&travelmode=").append(if (mode.isBlank()) "driving" else mode)
            val uri = android.net.Uri.parse(sb.toString())
            ui {
                // 優先丟給 Google 地圖 App；沒裝才退回一般瀏覽器
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)
                        .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            "已在 Google 地圖顯示路線：" + (if (origin.isBlank()) "目前位置" else origin) +
                " → $destination" + (if (waypoints.isNotBlank()) "（途經 ${waypoints.replace("|", "、")}）" else "")
        } catch (e: Throwable) { "開啟地圖路線失敗：${e.message}" }
    }

    fun shareText(ctx: Context, text: String): String {
        return try {
            ui {
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "已開啟分享選單"
        } catch (e: Throwable) { "分享失敗：${e.message}" }
    }

    @Volatile private var tts: TextToSpeech? = null
    fun speak(ctx: Context, text: String): String {
        return try {
            val t = tts
            if (t != null) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pai"); return "已用手機念出" }
            tts = TextToSpeech(ctx.applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.TAIWAN
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pai")
                }
            }
            "已用手機念出"
        } catch (e: Throwable) { "語音播放失敗：${e.message}" }
    }

    fun toast(ctx: Context, text: String): String {
        ui { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
        return "已顯示提示"
    }
}
