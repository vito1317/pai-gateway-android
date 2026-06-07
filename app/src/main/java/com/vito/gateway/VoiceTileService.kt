package com.vito.gateway

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * 快速設定磚：下拉通知欄點一下 → 開 App 到語音分頁並啟動語音助理（隨處一鍵叫語音）。
 * 使用者需先把「PAI 語音」磚從編輯區拖到快速設定面板。
 */
class VoiceTileService : TileService() {
    override fun onClick() {
        super.onClick()
        GatewayState.requestTab.value = "voice"
        GatewayState.autoStartVoice.value = true
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Android 14+ 需用 PendingIntent 從磚啟動 Activity
        try {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } catch (_: Throwable) {
            try { startActivityAndCollapse(intent) } catch (_: Throwable) {}
        }
    }
}
