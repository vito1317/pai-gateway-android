package com.vito.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    fun notify(ctx: Context, title: String, text: String): String {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "pai-msg"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "PAI 訊息", NotificationManager.IMPORTANCE_HIGH))
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            nm.notify((System.currentTimeMillis() % 100000).toInt(),
                b.setContentTitle(title.ifEmpty { "PAI" }).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true)
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
            val pkg = known[q] ?: known.entries.firstOrNull { q.contains(it.key) }?.value
            val intent = when {
                q.contains("設定") -> Intent(Settings.ACTION_SETTINGS)
                q.contains("相機") -> Intent("android.media.action.IMAGE_CAPTURE")
                pkg != null -> pm.getLaunchIntentForPackage(pkg)
                else -> null
            } ?: return "找不到 app「$query」（可試 LINE/YouTube/地圖/Chrome/Spotify/設定/相機）"
            ui { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "已開啟「$query」"
        } catch (e: Throwable) { "開啟 app 失敗：${e.message}" }
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
