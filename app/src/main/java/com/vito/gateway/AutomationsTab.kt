package com.vito.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 自動化流程 + AI 主動思考記錄（手機端；打 /api/automations）。 */
@Composable
fun AutomationsTab() {
    val ctx = LocalContext.current
    var autos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var thoughts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf("載入中…") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        status = "載入中…"
        Thread {
            try {
                val prefs = Prefs(ctx)
                val c = (URL(prefs.paiBase.trimEnd('/') + "/api/automations").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                val j = JSONObject(raw)
                autos = (j.optJSONArray("automations") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                thoughts = (j.optJSONArray("thoughts") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                status = ""
            } catch (e: Throwable) {
                status = "載入失敗：${e.message}"
            }
        }.start()
    }

    fun toggle(id: Int, action: String) {
        Thread {
            try {
                val prefs = Prefs(ctx)
                val c = (URL(prefs.paiBase.trimEnd('/') + "/api/automations/$id/toggle").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(JSONObject().put("action", action).toString().toByteArray()) }
                c.responseCode
            } catch (_: Throwable) {}
            refreshKey++
        }.start()
    }

    Column(Modifier.fillMaxSize().background(CyberBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("🤖 自動化 · AI 主動思考", color = CyberCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (status.isNotEmpty()) Text(status, color = CyberGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        Text("已建立的功能 / 流程", color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        if (autos.isEmpty() && status.isEmpty())
            Text("還沒有流程。跟 AI 說「幫我建立每天早上提醒上班的流程」，它會自己建到這。", color = CyberGray, fontSize = 13.sp)
        for (a in autos) {
            val enabled = a.optBoolean("enabled")
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CyberSurface).padding(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (enabled) "🟢 " else "⚪ ", fontSize = 13.sp)
                    Text(a.optString("name"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (a.optString("source") == "ai")
                        Text("AI建立", color = Color(0xFFE879F9), fontSize = 10.sp)
                }
                Text("⏱ ${a.optString("trigger")} · ${a.optString("actions")}", color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { toggle(a.optInt("id"), if (enabled) "disable" else "enable") },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.15f), contentColor = CyberCyan),
                        modifier = Modifier.padding(end = 8.dp)) { Text(if (enabled) "停用" else "啟用", fontSize = 13.sp) }
                    Button(onClick = { toggle(a.optInt("id"), "delete") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33F43F5E), contentColor = Color(0xFFFB7185))) { Text("刪除", fontSize = 13.sp) }
                }
            }
        }

        Text("🧠 AI 主動思考記錄", color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        if (thoughts.isEmpty() && status.isEmpty())
            Text("到「設定 → 自動化/主動」開啟「AI 主動思考」後，它會定期自己想，結果顯示在這。", color = CyberGray, fontSize = 13.sp)
        for (t in thoughts) {
            val acted = t.optBoolean("acted")
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                .background(if (acted) Color(0x22F59E0B) else CyberSurface).padding(10.dp)) {
                Row {
                    Text(t.optString("at"), color = CyberGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(if (acted) "採取行動" else "無動作", color = if (acted) Color(0xFFFBBF24) else CyberGray, fontSize = 11.sp)
                }
                Text(t.optString("text"), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
