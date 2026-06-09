package com.vito.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.ByteArrayOutputStream

/**
 * 螢幕投影前景服務：用 MediaProjection（系統同意框可選「單一 App / 整個螢幕」，Android 14+）
 * 持續把畫面 render 到 ImageReader，VoiceEngine 投影迴圈再 grabB64() 抓最新一張給 AI。
 */
class MediaProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    @Volatile private var lastB64: String? = null   // 最後一張成功的幀（靜止畫面 keep-alive 用）

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14：必須先 startForeground(type=mediaProjection) 才能取得 MediaProjection
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startCapture(code, data)
        } catch (e: Throwable) {
            GatewayState.log("螢幕投影啟動失敗：${e.message}"); stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data) ?: run { stopSelf(); return }
        projection = proj
        // Android 14 強制要註冊 callback
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { instance = null; cleanup() }
        }, null)

        val dm = resources.displayMetrics
        // 縮到最大寬 720 擷取，省記憶體＋傳輸
        val scale = (720f / maxOf(dm.widthPixels, 1)).coerceAtMost(1f)
        capW = (dm.widthPixels * scale).toInt().coerceAtLeast(1)
        capH = (dm.heightPixels * scale).toInt().coerceAtLeast(1)
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "pai-cast", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
        instance = this
        GatewayState.log("🖥 螢幕投影已開始（${capW}x${capH}）")
    }

    /**
     * 抓最新一張畫面 → data URI（給 VoiceEngine 投影迴圈呼叫）。
     * VirtualDisplay 只在畫面「變動」時產生新幀；靜止畫面 acquireLatestImage 會回 null，
     * 此時回傳上一張成功的幀（keep-alive），避免 vision:pending 過期讓 AI 說「看不到畫面」。
     */
    fun grabB64(): String? {
        val r = reader ?: return lastB64
        val image = try { r.acquireLatestImage() } catch (e: Throwable) { null } ?: return lastB64
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * capW
            val bmpW = capW + (if (pixelStride > 0) rowPadding / pixelStride else 0)
            val full = Bitmap.createBitmap(bmpW.coerceAtLeast(capW), capH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            val bmp = if (bmpW > capW) Bitmap.createBitmap(full, 0, 0, capW, capH) else full
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            val uri = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            lastB64 = uri
            uri
        } catch (e: Throwable) {
            lastB64
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null; reader = null; projection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        cleanup()
    }

    private fun startFg() {
        val id = "projection"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, "螢幕投影", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, id) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = b.setContentTitle("PAI 螢幕投影")
            .setContentText("AI 正在看你分享的畫面")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, notif)
        }
    }

    companion object {
        @Volatile var instance: MediaProjectionService? = null

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, MediaProjectionService::class.java).putExtra("code", code).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MediaProjectionService::class.java))
        }
    }
}
