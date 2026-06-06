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
        try {
            server = GatewayServer(this, prefs.port, prefs.secret).also { it.start() }
        } catch (e: Throwable) {
            GatewayState.log("server 啟動失敗：${e.message}")
            return
        }
        val ip = Net.localIp(this)
        GatewayState.localUrl.value = "http://$ip:${prefs.port}/mcp"
        GatewayState.running.value = true
        GatewayState.log("MCP server 已啟動 :${prefs.port}")
        updateNotification("運行中 :${prefs.port}")

        if (prefs.useCloudflared && Cloudflared.available(this)) {
            GatewayState.log("啟動 cloudflared 通道…")
            Cloudflared.start(this, prefs.port,
                onUrl = { url ->
                    if (url != GatewayState.publicUrl.value) {
                        GatewayState.publicUrl.value = url
                        prefs.lastPublicUrl = url
                        GatewayState.log("公網網址：$url")
                        registerAsync(prefs, "$url/mcp")
                    }
                },
                onLog = { GatewayState.log(it) })
        } else {
            // 沒有 cloudflared → 用區域網址註冊（PAI 與手機同網段時可連）
            if (prefs.useCloudflared) GatewayState.log("未放入 cloudflared binary，改用區域網址")
            registerAsync(prefs, GatewayState.localUrl.value)
        }
    }

    fun registerAsync(prefs: Prefs, mcpUrl: String) {
        if (prefs.registerToken.isBlank()) {
            GatewayState.regStatus.value = "缺註冊 token（請在 UI 填入）"
            return
        }
        Thread {
            val r = Registrar.register(prefs.paiBase, prefs.registerToken, prefs.nodeName, mcpUrl, prefs.secret)
            GatewayState.regStatus.value = r
            GatewayState.log(r)
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (e: Throwable) {}
        Cloudflared.stop()
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
