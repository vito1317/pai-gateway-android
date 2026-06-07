package com.vito.gateway

import android.content.Context
import android.os.Build

/** 設定儲存（SharedPreferences）。 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8099)
        set(v) = sp.edit().putInt("port", v).apply()

    var secret: String
        get() = sp.getString("secret", null) ?: ("gw-" + randomToken()).also { secret = it }
        set(v) { sp.edit().putString("secret", v).apply() }

    var paiBase: String
        get() = sp.getString("paiBase", "https://pai.vito1317.com") ?: ""
        set(v) = sp.edit().putString("paiBase", v).apply()

    var registerToken: String
        get() = sp.getString("registerToken", "") ?: ""
        set(v) = sp.edit().putString("registerToken", v).apply()

    var nodeName: String
        get() = sp.getString("nodeName", null) ?: (Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9_-]"), "-").also { nodeName = it }
        set(v) = sp.edit().putString("nodeName", v).apply()

    var useCloudflared: Boolean
        get() = sp.getBoolean("useCloudflared", true)
        set(v) = sp.edit().putBoolean("useCloudflared", v).apply()

    var lastPublicUrl: String
        get() = sp.getString("lastPublicUrl", "") ?: ""
        set(v) = sp.edit().putString("lastPublicUrl", v).apply()

    /** 語音對話的穩定 session id：持久化 → 關 App 再開也接續同一段對話（長期記憶）。 */
    var voiceSession: String
        get() = sp.getString("voiceSession", null) ?: ("android-" + randomToken()).also { voiceSession = it }
        set(v) = sp.edit().putString("voiceSession", v).apply()

    /** 開新對話：換一個 session id（清空雲端對話脈絡）。 */
    fun resetVoiceSession() { voiceSession = "android-" + randomToken() }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..24).map { chars.random() }.joinToString("")
    }
}
