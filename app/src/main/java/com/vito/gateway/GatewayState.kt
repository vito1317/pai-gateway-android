package com.vito.gateway

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** 全域狀態（Compose 直接觀察），service 更新、UI 顯示。 */
object GatewayState {
    var running = mutableStateOf(false)
    var localUrl = mutableStateOf("")
    var publicUrl = mutableStateOf("")
    var regStatus = mutableStateOf("尚未註冊")
    val logs = mutableStateListOf<String>()

    fun log(s: String) {
        logs.add(0, s)
        while (logs.size > 60) logs.removeAt(logs.size - 1)
    }
}
