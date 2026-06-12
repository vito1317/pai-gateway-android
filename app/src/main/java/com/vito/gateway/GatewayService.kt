package com.vito.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

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

        // paigent 主動感知通道：裝置事件（電量低/儲存不足）推回平台 /webhooks/{node}
        Sentinel.start(this, prefs.paiBase, prefs.nodeName)

        // 解鎖手機 → 早晨通勤檢查（醒來即提醒，避免時間輪詢時你還沒醒沒看到）
        registerUnlockReceiver()
    }

    private var unlockReceiver: BroadcastReceiver? = null
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour < 4 || hour >= 12) return // 粗略早晨閘門，精準判斷在後端
                val prefs = Prefs(c)
                if (prefs.registerToken.isBlank()) return
                Thread {
                    try {
                        val cc = (URL(prefs.paiBase.trimEnd('/') + "/api/commute/wake").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 45000
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("X-Register-Secret", prefs.registerToken)
                        }
                        cc.outputStream.use { it.write("{}".toByteArray()) }
                        cc.responseCode
                    } catch (_: Throwable) {}
                }.start()
            }
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
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
        Sentinel.stop()
        try { unlockReceiver?.let { unregisterReceiver(it) } } catch (e: Throwable) {}
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
