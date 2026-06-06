package com.vito.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePerms()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { RootScreen() } }
    }

    private fun requestRuntimePerms() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun RootScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("節點") })
            Tab(tab == 1, { tab = 1 }, text = { Text("語音對話") })
            Tab(tab == 2, { tab = 2 }, text = { Text("瀏覽器") })
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> GatewayTab()
                1 -> VoiceTab()
                2 -> BrowserTab()
            }
        }
    }
}

// ── 節點：一鍵配對 + 狀態 ──────────────────────────────────────────────────────
@Composable
fun GatewayTab() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var paiBase by remember { mutableStateOf(prefs.paiBase) }
    var token by remember { mutableStateOf(prefs.registerToken) }
    var node by remember { mutableStateOf(prefs.nodeName) }
    var pairCode by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("PAI Gateway · Android", fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        StatusRow("狀態", if (GatewayState.running.value) "🟢 運行中" else "🔴 停止")
        StatusRow("公網網址", GatewayState.publicUrl.value.ifEmpty { GatewayState.localUrl.value.ifEmpty { "—" } })
        StatusRow("註冊", GatewayState.regStatus.value)

        Spacer(Modifier.height(10.dp))
        // 一鍵配對：貼上 PAI 後台的「配對碼」自動填好設定
        OutlinedTextField(pairCode, { pairCode = it },
            label = { Text("配對碼（PAI 後台複製）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val ok = applyPairCode(pairCode, prefs)
                if (ok) { paiBase = prefs.paiBase; token = prefs.registerToken; pairWithStart(ctx, prefs) }
                else GatewayState.log("配對碼格式不正確")
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("🔗 一鍵配對並啟動") }

        Spacer(Modifier.height(12.dp))
        Text("或手動設定：", fontSize = 12.sp)
        OutlinedTextField(paiBase, { paiBase = it; prefs.paiBase = it },
            label = { Text("PAI 網址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it; prefs.registerToken = it },
            label = { Text("註冊 Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(node, { node = it; prefs.nodeName = it },
            label = { Text("節點名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { pairWithStart(ctx, prefs) }, enabled = !GatewayState.running.value) { Text("啟動並串接") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { GatewayService.stop(ctx) }, enabled = GatewayState.running.value) { Text("停止") }
        }

        Spacer(Modifier.height(8.dp))
        Text("日誌", fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(GatewayState.logs) { Text(it, fontSize = 11.sp) }
        }
    }
}

// ── 語音對話：內嵌 /voice（喚醒/打斷/操控全功能）+ 麥克風授權 ─────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VoiceTab() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false  // 允許自動播放 TTS
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // 授予網頁麥克風權限（語音對話需要）
                    request.grant(request.resources)
                }
            }
            webViewClient = WebViewClient()
            loadUrl(prefs.paiBase.trimEnd('/') + "/voice")
        }
    })
}

// ── 瀏覽器：AI 操作會顯示在這（內建受控瀏覽器）─────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserTab() {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "").plus(" PAIGateway")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(BrowserJs.HELPERS, null) // 安裝 __gwClick/__gwType
                }
            }
            loadUrl("https://www.google.com")
            BrowserController.attach(this)
        }
    })
}

@Composable
private fun StatusRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$k：", fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(v, fontSize = 13.sp)
    }
}

/** 配對碼：base64 或 JSON of {pai, token, name?}。回 true=解析成功並已寫入 prefs。 */
private fun applyPairCode(code: String, prefs: Prefs): Boolean {
    val raw = code.trim()
    if (raw.isEmpty()) return false
    return try {
        val json = if (raw.startsWith("{")) raw else String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
        val o = JSONObject(json)
        prefs.paiBase = o.optString("pai", prefs.paiBase)
        prefs.registerToken = o.optString("token", prefs.registerToken)
        if (o.has("name")) prefs.nodeName = o.getString("name")
        prefs.registerToken.isNotBlank()
    } catch (e: Throwable) { false }
}

private fun pairWithStart(ctx: android.content.Context, prefs: Prefs) {
    if (prefs.registerToken.isBlank()) { GatewayState.log("請先填註冊 Token 或貼配對碼"); return }
    GatewayService.start(ctx)
}
