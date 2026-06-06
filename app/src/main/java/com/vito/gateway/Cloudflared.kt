package com.vito.gateway

import android.content.Context
import java.io.File
import java.util.regex.Pattern

/**
 * 用 cloudflared 建立公網通道，讓雲端 PAI 連得到這支手機（手機在 4G/NAT 後沒公網 IP）。
 *
 * Android 10+ 禁止執行 app 可寫目錄裡的二進位（W^X），但 **jniLibs 內的 .so 可執行**。
 * 因此把 cloudflared 的 linux-arm64 binary 放到：
 *   app/src/main/jniLibs/arm64-v8a/libcloudflared.so
 * 打包後它會出現在 applicationInfo.nativeLibraryDir，可用 ProcessBuilder 執行。
 * 下載： https://github.com/cloudflare/cloudflared/releases （cloudflared-linux-arm64，改名為 libcloudflared.so）
 */
object Cloudflared {
    @Volatile private var proc: Process? = null

    fun binaryPath(ctx: Context): String? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        return if (f.exists()) f.absolutePath else null
    }

    fun available(ctx: Context) = binaryPath(ctx) != null

    /** 啟動 quick tunnel 指向本機 port，回傳 trycloudflare 公網網址（失敗回 null）。 */
    fun start(ctx: Context, port: Int, onUrl: (String) -> Unit, onLog: (String) -> Unit): Boolean {
        stop()
        val bin = binaryPath(ctx) ?: run { onLog("找不到 cloudflared（請放 jniLibs/arm64-v8a/libcloudflared.so）"); return false }
        return try {
            val pb = ProcessBuilder(bin, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:$port")
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            Thread {
                val urlPat = Pattern.compile("https://[a-z0-9.-]+\\.trycloudflare\\.com")
                p.inputStream.bufferedReader().forEachLine { line ->
                    onLog(line)
                    val m = urlPat.matcher(line)
                    if (m.find()) onUrl(m.group())
                }
            }.start()
            true
        } catch (e: Throwable) {
            onLog("cloudflared 啟動失敗：${e.message}")
            false
        }
    }

    fun stop() {
        try { proc?.destroy() } catch (e: Throwable) {}
        proc = null
    }
}
