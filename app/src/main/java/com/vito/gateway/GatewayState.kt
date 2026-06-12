package com.vito.gateway

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** 全域狀態（Compose 直接觀察），service 更新、UI 顯示。 */
object GatewayState {
    var running = mutableStateOf(false)
    var localUrl = mutableStateOf("")
    var publicUrl = mutableStateOf("")
    var regStatus = mutableStateOf("尚未註冊")
    val autoStartVoice = mutableStateOf(false) // 快速設定磚啟動 → 進語音分頁後自動開語音
    val requestTab = mutableStateOf("")   // 工具呼叫請求切換的分頁（如 "browser"）；UI 處理後清空
    val requestProjection = mutableStateOf(false) // 語音頁按「螢幕投影」→ 請 MainActivity 跳系統同意框
    val noticeText = mutableStateOf("")   // 點通知/show_document 時要顯示的完整內容
    val noticeTitle = mutableStateOf("PAI 通知")
    val noticeUrl = mutableStateOf("")    // 文件可下載/分享連結（可選）
    val noticeActions = mutableStateOf("")  // 通知動作按鈕 JSON 陣列（HITL 接受/拒絕等），in-app 彈窗也顯示
    val logs = mutableStateListOf<String>()

    fun log(s: String) {
        logs.add(0, s)
        while (logs.size > 60) logs.removeAt(logs.size - 1)
    }
}
