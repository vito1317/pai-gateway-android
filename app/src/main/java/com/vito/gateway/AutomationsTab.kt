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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/* ---------- Agent Ops：每個運行中 agent 目前在幹嘛（打 /api/agent-ops，3 秒輪詢） ---------- */

private val OpsGreen = Color(0xFF3FDC97)
private val OpsAmber = Color(0xFFE6B450)

/** action 名稱 → (中文動作, 顏色)，與 web 端 AgentOpsFlow 同一套分類。 */
private fun categoryOf(action: String): Pair<String, Color> {
    val a = action.lowercase()
    return when {
        Regex("browse|web|url|http|fetch|crawl|surf|page").containsMatchIn(a) -> "瀏覽網頁" to CyberCyan
        Regex("gui|tap|click|swipe|screenshot|screen_|type|keyboard|open_app|adb|device|android|mobile|control").containsMatchIn(a) -> "操作裝置" to OpsGreen
        Regex("recall|memory|remember").containsMatchIn(a) -> "記憶檢索" to CyberCyan
        Regex("""\b(call|dial|twilio|outbound)""").containsMatchIn(a) -> "撥打電話" to OpsAmber
        Regex("watch|vision|look|see|observe_screen|image").containsMatchIn(a) -> "視覺判讀" to CyberCyan
        Regex("notify|message|mail|send|telegram|line|slack|discord").containsMatchIn(a) -> "發送訊息" to OpsGreen
        Regex("log|read_|file|repo").containsMatchIn(a) -> "讀取資料" to CyberGray
        Regex("query|lookup|match|search|list").containsMatchIn(a) -> "查詢比對" to CyberCyan
        Regex("propose|record_finding|plan|ticket").containsMatchIn(a) -> "規劃提案" to OpsAmber
        Regex("handoff").containsMatchIn(a) -> "移交領域" to OpsAmber
        Regex("finish|reflect|summar").containsMatchIn(a) -> "收尾反思" to OpsGreen
        else -> "工具呼叫" to CyberGray
    }
}

private fun opsStatusLabel(s: String): Pair<String, Color> = when (s) {
    "running" -> "執行中" to OpsGreen
    "awaiting_hitl" -> "待核准" to OpsAmber
    "active" -> "守望中" to CyberCyan
    "pending" -> "撥號中" to OpsAmber
    "in_progress" -> "通話中" to OpsGreen
    else -> s to CyberGray
}

/** 即時作業流：顯示每個運行中 agent（協調者/視覺守望/外撥電話）與當前動作細節。 */
@Composable
fun AgentOpsSection() {
    val ctx = LocalContext.current
    var agents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 分頁顯示期間每 3 秒輪詢；離開分頁（LaunchedEffect 取消）就停
    LaunchedEffect(Unit) {
        while (true) {
            Thread {
                try {
                    val prefs = Prefs(ctx)
                    val c = (URL(prefs.paiBase.trimEnd('/') + "/api/agent-ops").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 8000; readTimeout = 15000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("X-Register-Secret", prefs.registerToken)
                    }
                    val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                    val arr = JSONObject(raw).optJSONArray("agents") ?: JSONArray()
                    agents = (0 until arr.length()).map { arr.getJSONObject(it) }
                    loaded = true
                } catch (_: Throwable) { /* 斷線時保留舊畫面 */ }
            }.start()
            delay(3000)
        }
    }

    Text("⚡ 即時作業流 · Agent 在幹嘛", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))

    if (agents.isEmpty()) {
        Text(if (loaded) "沒有運行中的 agent。交辦任務、開視覺守望、或叫 AI 打電話就會顯示在這。" else "掃描中…",
            color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        return
    }

    for (a in agents) {
        val kind = a.optString("kind")
        val (stLabel, stColor) = opsStatusLabel(a.optString("status"))
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CyberSurface).padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(when (kind) { "watch" -> "👁 "; "call" -> "📞 "; else -> "🧠 " }, fontSize = 14.sp)
                Text(a.optString("name"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("● $stLabel", color = stColor, fontSize = 11.sp)
            }
            Text(a.optString("title"), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))

            when (kind) {
                // 協調者：步驟鏈（分類標籤）＋ 當前動作的想法/觀察
                "coordinator" -> {
                    val steps = a.optJSONArray("steps") ?: JSONArray()
                    if (steps.length() > 0) {
                        val chain = (0 until steps.length()).joinToString(" ▸ ") { i ->
                            categoryOf(steps.getJSONObject(i).optString("action")).first
                        }
                        Text(chain, color = CyberGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        val cur = steps.getJSONObject(steps.length() - 1)
                        val (catLabel, catColor) = categoryOf(cur.optString("action"))
                        Text("▶ $catLabel · ${cur.optString("action")}", color = catColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        cur.optString("thought").takeIf { it.isNotEmpty() && it != "null" }?.let {
                            Text("💭 $it", color = CyberGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        cur.optString("observation").takeIf { it.isNotEmpty() && it != "null" }?.let {
                            Text("👁 $it", color = OpsGreen.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    val meta = buildList {
                        a.optString("domain").takeIf { it.isNotEmpty() }?.let { add("領域 $it") }
                        if (a.optInt("tokens") > 0) add("${a.optInt("tokens")} tok")
                        if (a.has("elapsed") && !a.isNull("elapsed")) add("已跑 ${a.optInt("elapsed")}s")
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, color = CyberGray, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
                // 視覺守望：掃描頻率/次數 ＋ AI 目前看到的畫面
                "watch" -> {
                    Text("每 ${a.optInt("interval")}s 截圖判讀 · 已掃 ${a.optInt("runs")} 次" +
                        (a.optString("node").takeIf { it.isNotEmpty() && it != "null" }?.let { " · 節點 $it" } ?: ""),
                        color = CyberGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    a.optString("detail").takeIf { it.isNotEmpty() && it != "null" }?.let {
                        Text("👁 目前畫面：$it", color = OpsGreen.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                // 外撥電話：對象/回合 ＋ 最後一句
                "call" -> {
                    Text("→ ${a.optString("to")} · ${a.optInt("turns")} 回合", color = CyberGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    a.optString("lastLine").takeIf { it.isNotEmpty() && it != "null" }?.let {
                        Text("💬 $it", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 自動化流程 + AI 主動思考記錄（手機端；打 /api/automations）。 */
@Composable
fun AutomationsTab() {
    val ctx = LocalContext.current
    var autos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var thoughts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var builtins by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
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
                builtins = (j.optJSONArray("builtins") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
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

    fun toggleBuiltin(key: String, enabled: Boolean) {
        Thread {
            try {
                val prefs = Prefs(ctx)
                val c = (URL(prefs.paiBase.trimEnd('/') + "/api/automations/builtin").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Register-Secret", prefs.registerToken)
                }
                c.outputStream.use { it.write(JSONObject().put("key", key).put("enabled", enabled).toString().toByteArray()) }
                c.responseCode
            } catch (_: Throwable) {}
            refreshKey++
        }.start()
    }

    Column(Modifier.fillMaxSize().background(CyberBackground).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("🤖 自動化 · AI 主動思考", color = CyberCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (status.isNotEmpty()) Text(status, color = CyberGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(12.dp))
        AgentOpsSection()

        Text("內建功能（可開關）", color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        for (b in builtins) {
            val on = b.optBoolean("enabled")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CyberSurface).padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.optString("name"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(b.optString("desc"), color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Button(onClick = { toggleBuiltin(b.optString("key"), !on) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) Color(0x3310B981) else Color(0x22FFFFFF),
                        contentColor = if (on) Color(0xFF34D399) else CyberGray)) {
                    Text(if (on) "已啟用" else "已停用", fontSize = 12.sp)
                }
            }
        }

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
