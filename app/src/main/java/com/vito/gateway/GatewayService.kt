package com.vito.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** 前景服務：跑 MCP server + cloudflared 通道 + 註冊到 PAI，背景持續可被連線。 */
class GatewayService : Service() {
    private var server: GatewayServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("啟動中…"))
        if (server == null) startGateway()
        return START_STICKY
    }

    private fun startGateway() {
        val prefs = Prefs(this)
        acquireWakeLock()
        // 本機 server（LAN 直連備用，反向模式下非必要但無害）
        try { server = GatewayServer(this, prefs.port, prefs.secret).also { it.start() } } catch (e: Throwable) {}
        GatewayState.localUrl.value = "reverse://${prefs.nodeName}"
        GatewayState.running.value = true
        updateNotification("反向連線中")

        // 反向連線（主要）：註冊為 reverse 節點 + 啟動 long-poll 迴圈
        if (prefs.registerToken.isBlank()) {
            GatewayState.regStatus.value = "缺註冊 token（請先配對）"
            return
        }
        Thread {
            val r = ReversePoller.register(prefs.paiBase, prefs.registerToken, prefs.nodeName)
            GatewayState.regStatus.value = r
            GatewayState.log(r)
            if (r.startsWith("✅")) {
                ReversePoller.start(this, prefs.paiBase, prefs.registerToken, prefs.nodeName)
            }
        }.start()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "gateway:poll").apply { acquire() }
        } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        ReversePoller.stop()
        try { server?.stop() } catch (e: Throwable) {}
        try { wakeLock?.release() } catch (e: Throwable) {}
        GatewayState.running.value = false
        GatewayState.log("已停止")
    }

    private fun channelId(): String {
        val id = "gateway"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, "Gateway", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return id
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId()) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("PAI Gateway").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification(text))
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, GatewayService::class.java)) }
    }
}
