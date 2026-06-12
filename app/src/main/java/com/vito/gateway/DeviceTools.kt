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

    fun notify(ctx: Context, title: String, text: String, actions: org.json.JSONArray? = null): String {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "pai-msg"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "PAI 訊息", NotificationManager.IMPORTANCE_HIGH))
            // 點通知 → 開 App 顯示完整內容（任務結果可能很長，通知列看不完整）
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("notice_title", title); putExtra("notice_text", text)
                if (actions != null) putExtra("notice_actions", actions.toString()) // in-app 彈窗也顯示按鈕
            }
            val flag = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, text.hashCode(), intent, flag)
            val notifId = (System.currentTimeMillis() % 100000).toInt()
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            b.setContentTitle(title.ifEmpty { "PAI" }).setContentText(text.lineSequence().firstOrNull() ?: text)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true)
                .setContentIntent(pi)
                .setStyle(Notification.BigTextStyle().bigText(text))
            // 動作按鈕（如 HITL 接受/拒絕）：按下 → NotifActionReceiver 帶憑證 POST 到 path
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val a = actions.optJSONObject(i) ?: continue
                    val label = a.optString("label"); val path = a.optString("path")
                    if (label.isEmpty() || path.isEmpty()) continue
                    val ai = Intent(ctx, NotifActionReceiver::class.java).apply {
                        action = "com.vito.gateway.NOTIF_ACTION.$notifId.$i" // 唯一 → PendingIntent 不互蓋
                        putExtra("path", path)
                        putExtra("body", a.optJSONObject("body")?.toString() ?: "{}")
                        putExtra("label", label)
                        putExtra("notif_id", notifId)
                    }
                    val api = PendingIntent.getBroadcast(ctx, (notifId * 31 + i), ai, flag)
                    b.addAction(Notification.Action.Builder(null, label, api).build())
                }
            }
            nm.notify(notifId, b.build())
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

    /** 設定鬧鐘（AlarmClock intent，SKIP_UI 直接設定到系統時鐘 App）。 */
    fun setAlarm(ctx: Context, hour: Int, minutes: Int, message: String): String {
        if (hour !in 0..23 || minutes !in 0..59) return "時間格式錯誤（時 0-23、分 0-59）"
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minutes)
                if (message.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ui { ctx.startActivity(intent) }
            "已設定鬧鐘 %02d:%02d%s".format(hour, minutes, if (message.isNotBlank()) "（$message）" else "")
        } catch (e: Throwable) { "設定鬧鐘失敗：${e.message}（可能是裝置時鐘 App 不支援）" }
    }

    /** 設定倒數計時器（秒）。 */
    fun setTimer(ctx: Context, seconds: Int, message: String): String {
        if (seconds <= 0) return "請提供大於 0 的秒數"
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                if (message.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ui { ctx.startActivity(intent) }
            val m = seconds / 60; val s = seconds % 60
            "已設定計時器 ${if (m > 0) "$m 分" else ""}${if (s > 0) "$s 秒" else ""}${if (message.isNotBlank()) "（$message）" else ""}"
        } catch (e: Throwable) { "設定計時器失敗：${e.message}" }
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

    /** 讀手機行事曆未來 N 天的事件（不需任何 API/iCal，直接讀裝置同步好的行事曆）。json=true 回機器可解析的 JSON。 */
    fun calendarRead(ctx: Context, days: Int, json: Boolean = false): String {
        return try {
            val now = System.currentTimeMillis()
            val end = now + days.coerceIn(1, 60) * 86400000L
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, now)
            android.content.ContentUris.appendId(uri, end)
            val proj = arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.EVENT_LOCATION,
                android.provider.CalendarContract.Instances.ALL_DAY
            )
            val sb = StringBuilder()
            val arr = org.json.JSONArray()
            val fmt = java.text.SimpleDateFormat("MM/dd(EEE) HH:mm", java.util.Locale.TAIWAN)
            val fmtDay = java.text.SimpleDateFormat("MM/dd(EEE)", java.util.Locale.TAIWAN)
            ctx.contentResolver.query(uri.build(), proj, null, null,
                android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 50) {
                    val title = c.getString(0)?.ifBlank { "(無標題)" } ?: "(無標題)"
                    val begin = c.getLong(1)
                    val loc = c.getString(2).orEmpty()
                    val allDay = c.getInt(3) == 1
                    if (json) {
                        arr.put(org.json.JSONObject().put("title", title).put("begin", begin).put("location", loc).put("all_day", allDay))
                    } else {
                        val whenTxt = if (allDay) fmtDay.format(java.util.Date(begin)) + " 整天" else fmt.format(java.util.Date(begin))
                        sb.append("・$whenTxt $title").append(if (loc.isNotBlank()) "（$loc）" else "").append("\n")
                    }
                    n++
                }
            }
            if (json) return arr.toString()
            if (sb.isEmpty()) "未來 $days 天沒有行事曆事件。" else "未來 $days 天的行程：\n$sb"
        } catch (e: SecurityException) {
            if (json) "[]" else "需要行事曆讀取權限（請到 App 設定開啟）。"
        } catch (e: Throwable) {
            if (json) "[]" else "讀行事曆失敗：${e.message}"
        }
    }

    /** 在手機行事曆新增事件（免 API）。start 格式「yyyy-MM-dd HH:mm」；durationMin 預設 60。 */
    fun calendarAdd(ctx: Context, title: String, start: String, durationMin: Int, location: String): String {
        return try {
            if (title.isBlank() || start.isBlank()) return "需要事件標題與開始時間。"
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.TAIWAN)
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Taipei")
            val begin = (fmt.parse(start.trim()) ?: return "時間格式需為 yyyy-MM-dd HH:mm。").time
            val dur = (if (durationMin <= 0) 60 else durationMin)
            // 找主要行事曆 id
            var calId = -1L
            ctx.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.IS_PRIMARY),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (calId < 0) calId = id
                    if (c.getInt(1) == 1) { calId = id; break }
                }
            }
            if (calId < 0) return "找不到可寫入的行事曆。"
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, begin)
                put(android.provider.CalendarContract.Events.DTEND, begin + dur * 60000L)
                if (location.isNotBlank()) put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "Asia/Taipei")
            }
            val u = ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            if (u != null) "已新增行事曆：$title（$start）" + (if (location.isNotBlank()) "（$location）" else "") else "新增失敗。"
        } catch (e: SecurityException) {
            "需要行事曆寫入權限（請到 App 設定開啟）。"
        } catch (e: Throwable) {
            "新增行事曆失敗：${e.message}"
        }
    }

    /** 讀手機便箋（先試小米/HyperOS 便箋 provider；讀不到回提示，讓 AI 改用開 App+看畫面）。 */
    fun notesRead(ctx: Context, limit: Int): String {
        val uris = listOf(
            "content://com.miui.notes.provider/note",
            "content://notes/note",
            "content://com.miui.notes.api.provider/note"
        )
        for (u in uris) {
            try {
                val cur = ctx.contentResolver.query(android.net.Uri.parse(u), null, null, null, null) ?: continue
                cur.use { c ->
                    val ci = c.getColumnIndex("content").let { if (it >= 0) it else c.getColumnIndex("snippet") }
                    if (ci < 0) return@use
                    val sb = StringBuilder()
                    var n = 0
                    while (c.moveToNext() && n < limit.coerceIn(1, 50)) {
                        val t = c.getString(ci)?.trim().orEmpty()
                        if (t.isNotEmpty()) { sb.append("・").append(t.take(200)).append("\n"); n++ }
                    }
                    if (sb.isNotEmpty()) return "便箋（最近 $n 則）：\n$sb"
                }
            } catch (_: SecurityException) {
            } catch (_: Throwable) {
            }
        }
        return "讀不到內建便箋（此手機的筆記 App 沒開放直接讀）。可改用『開啟筆記 App + 看畫面』的方式：先 open_app 打開筆記，再用 screen_shot/screen_read 讀內容。"
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
    /** 已知導航 App 名稱 → 候選 package（依序試，裝了哪個用哪個）。 */
    private fun navPackages(app: String): List<String> = when (app.trim().lowercase()) {
        "waze" -> listOf("com.waze")
        "導航王", "导航王", "naviking", "navi king", "navigation king", "kingwaytek" ->
            listOf("com.kingwaytek.naviking", "com.kingwaytek.naviking.std", "com.kingwaytek.naviking3d", "com.kingwaytek.tw.naviking")
        "papago", "papago!", "研勤" -> listOf("com.mactiwe.papago", "com.papago", "com.maction.papago")
        "here", "here maps", "heremaps" -> listOf("com.here.app.maps")
        "osmand" -> listOf("net.osmand.plus", "net.osmand")
        "maps.me", "mapsme" -> listOf("com.mapswithme.maps.pro")
        "", "ask", "chooser", "選擇", "詢問", "選單" -> emptyList() // 空=讓使用者選
        else -> listOf(app) // 其他：直接當 package name
    }

    fun mapsRoute(ctx: Context, origin: String, destination: String, waypoints: String, mode: String, app: String = ""): String {
        val a = app.trim().lowercase()
        // 非 Google 地圖 → 用通用 geo: scheme，各家導航 App（導航王/Waze/PaPaGO…）都能接手
        if (a.isNotEmpty() && a != "google" && a != "google maps" && a != "google 地圖" && a != "googlemaps") {
            return try {
                val geo = android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(destination))
                val pkgs = navPackages(app)
                ui {
                    var ok = false
                    for (p in pkgs) {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true; break
                        } catch (_: Throwable) {}
                    }
                    if (!ok) { // 指定的沒裝 or 要求選單 → 跳 App 選擇器
                        try {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, geo), "選擇導航 App").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Throwable) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                "已開啟導航到 $destination" + (if (pkgs.isNotEmpty()) "（$app）" else "（請選擇導航 App）")
            } catch (e: Throwable) { "開啟導航失敗：${e.message}" }
        }
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
