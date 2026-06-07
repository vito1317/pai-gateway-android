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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

// ── 霓虹電馭風格主題 (Cyber/Neon) ──────────────────────────────────────────────────
val CyberBackground = Color(0xFF0F172A)
val CyberSurface = Color(0xFF1E293B)
val CyberCyan = Color(0xFF00F0FF)
val CyberPurple = Color(0xFF8B5CF6)
val CyberGray = Color(0xFF94A3B8)

@Composable
fun CyberTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = CyberCyan,
        secondary = CyberPurple,
        background = CyberBackground,
        surface = CyberSurface,
        onSurface = Color.White,
        onPrimary = Color.Black
    )
    MaterialTheme(colorScheme = colorScheme) {
        content()
    }
}

class MainActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePerms()
        handleNotice(intent)
        // 已配對過 → App 一開就自動把反向節點服務跑起來（long-poll 接 PAI 指令），
        // 不用每次手動去「節點」分頁按啟動；否則語音叫手機工具會逾時。
        if (Prefs(this).registerToken.isNotBlank()) {
            try { GatewayService.start(this) } catch (_: Throwable) {}
        }
        setContent { CyberTheme { RootScreen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotice(intent)
    }

    private fun handleNotice(intent: android.content.Intent?) {
        intent?.getStringExtra("notice_text")?.let { if (it.isNotEmpty()) GatewayState.noticeText.value = it }
    }

    private fun requestRuntimePerms() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
        }
    }
}

enum class NavItem(val label: String, val icon: ImageVector) {
    Node("節點", Icons.Default.Dns),
    Voice("語音", Icons.Default.Mic),
    Browser("瀏覽器", Icons.Default.Language)
}

@Composable
fun RootScreen() {
    var selectedItem by remember { mutableStateOf(NavItem.Node) }

    // 工具呼叫（browser_*）請求切到瀏覽器分頁時自動切換（語音仍在背景跑）
    LaunchedEffect(GatewayState.requestTab.value) {
        when (GatewayState.requestTab.value) {
            "browser" -> selectedItem = NavItem.Browser
            "voice" -> selectedItem = NavItem.Voice
            "node" -> selectedItem = NavItem.Node
        }
        if (GatewayState.requestTab.value.isNotEmpty()) GatewayState.requestTab.value = ""
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = CyberBackground, contentColor = CyberCyan) {
                NavItem.entries.forEach { item ->
                    NavigationBarItem(
                        selected = selectedItem == item,
                        onClick = { selectedItem = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberCyan,
                            unselectedIconColor = CyberGray,
                            indicatorColor = CyberSurface
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(CyberBackground)) {
            // 瀏覽器 WebView【永遠掛載】：不在瀏覽器分頁時用全螢幕但透明(alpha 0)墊在底層，
            // 保持 attached、有 surface → 背景的 evaluateJavascript/頁面載入才不會卡死（偵錯實證）。
            val browserVisible = selectedItem == NavItem.Browser
            Box(Modifier.fillMaxSize().alpha(if (browserVisible) 1f else 0f).zIndex(if (browserVisible) 1f else 0f)) {
                BrowserTab()
            }
            // 其他分頁畫在上層（不透明背景會蓋住底層透明的瀏覽器）
            when (selectedItem) {
                NavItem.Node -> Box(Modifier.fillMaxSize().zIndex(2f).background(CyberBackground)) { GatewayTab() }
                NavItem.Voice -> Box(Modifier.fillMaxSize().zIndex(2f).background(CyberBackground)) { VoiceTab() }
                NavItem.Browser -> {}
            }
        }
    }

    // show_document / 點通知 → 自動彈出完整內容（markdown、可滑、可開連結/分享）
    if (GatewayState.noticeText.value.isNotEmpty()) {
        val ctx = LocalContext.current
        val url = GatewayState.noticeUrl.value
        AlertDialog(
            onDismissRequest = { GatewayState.noticeText.value = "" },
            confirmButton = { TextButton(onClick = { GatewayState.noticeText.value = "" }) { Text("關閉", color = CyberCyan) } },
            dismissButton = {
                Row {
                    if (url.isNotEmpty()) TextButton(onClick = { DeviceTools.openUrlPublic(ctx, url) }) { Text("開啟連結", color = CyberCyan) }
                    TextButton(onClick = { DeviceTools.shareText(ctx, GatewayState.noticeText.value) }) { Text("分享", color = CyberGray) }
                }
            },
            title = { Text("📋 ${GatewayState.noticeTitle.value}", color = CyberCyan) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(renderMarkdown(GatewayState.noticeText.value), color = Color.White, fontSize = 14.sp)
                }
            },
            containerColor = CyberSurface
        )
    }
}

// ── 節點儀表板 ────────────────────────────────────────────────────────────────
@Composable
fun GatewayTab() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var paiBase by remember { mutableStateOf(prefs.paiBase) }
    var token by remember { mutableStateOf(prefs.registerToken) }
    var node by remember { mutableStateOf(prefs.nodeName) }
    var pairCode by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { res ->
        val c = res.contents
        if (c.isNullOrBlank()) {
            GatewayState.log("掃描取消或未取得內容")
        } else if (applyPairCode(c, prefs)) {
            paiBase = prefs.paiBase; token = prefs.registerToken
            GatewayState.log("✅ 已配對 ${prefs.nodeName}，啟動中…")
            pairWithStart(ctx, prefs)
        } else {
            GatewayState.log("QR 內容不是有效配對碼：" + c.take(24))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("PAI GATEWAY", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CyberCyan, letterSpacing = 2.sp)
                Spacer(Modifier.width(8.dp))
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = CyberGray)
            }
        }

        // 狀態卡片
        item {
            CyberCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPulse(GatewayState.running.value)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (GatewayState.running.value) "服務運行中" else "服務已停止",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    StatusDetail("公網網址", GatewayState.publicUrl.value.ifEmpty { GatewayState.localUrl.value.ifEmpty { "未分配" } })
                    StatusDetail("註冊狀態", GatewayState.regStatus.value)
                    
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { pairWithStart(ctx, prefs) },
                            enabled = !GatewayState.running.value,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                            Text("啟動")
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { GatewayService.stop(ctx) },
                            enabled = GatewayState.running.value,
                            modifier = Modifier.weight(1f),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(CyberPurple)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberPurple),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp))
                            Text("停止")
                        }
                    }
                }
            }
        }

        // 一鍵配對卡片
        item {
            CyberCard {
                Column(Modifier.padding(16.dp)) {
                    Text("快速配對", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scanLauncher.launch(ScanOptions().apply {
                                setPrompt("掃描 PAI 配對 QR")
                                setBeepEnabled(false)
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberSurface, contentColor = CyberCyan),
                        border = BorderStroke(1.dp, CyberCyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text("掃描 QR Code")
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it },
                        label = { Text("貼上配對碼", color = CyberGray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberPurple,
                            unfocusedBorderColor = CyberSurface,
                            focusedLabelColor = CyberPurple
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (applyPairCode(pairCode, prefs)) {
                                    paiBase = prefs.paiBase; token = prefs.registerToken; pairWithStart(ctx, prefs)
                                } else GatewayState.log("配對碼格式錯誤")
                            }) { Icon(Icons.Default.Link, contentDescription = null, tint = CyberCyan) }
                        }
                    )
                }
            }
        }

        // 手動設定（可摺疊）
        item {
            Column {
                TextButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showManual) "收起手動設定 ▲" else "展開手動設定 ▼", color = CyberGray, fontSize = 12.sp)
                }
                AnimatedVisibility(visible = showManual) {
                    CyberCard {
                        Column(Modifier.padding(16.dp)) {
                            ManualField("PAI 網址", paiBase) { paiBase = it; prefs.paiBase = it }
                            ManualField("註冊 Token", token) { token = it; prefs.registerToken = it }
                            ManualField("節點名稱", node) { node = it; prefs.nodeName = it }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { pairWithStart(ctx, prefs) },
                                    enabled = !GatewayState.running.value,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("配對並啟動") }
                                OutlinedButton(
                                    onClick = { GatewayService.stop(ctx) },
                                    enabled = GatewayState.running.value,
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, CyberGray),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("停止", color = CyberGray) }
                            }
                        }
                    }
                }
            }
        }

        // 日誌終端機
        item {
            Text("日誌輸出", color = CyberGray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, CyberSurface, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(GatewayState.logs) { log ->
                        Text(
                            log,
                            color = if (log.contains("Err", true)) Color.Red else Color.Green,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CyberCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CyberSurface,
        border = BorderStroke(1.dp, CyberBackground.copy(alpha = 0.5f)),
        shadowElevation = 4.dp
    ) {
        content()
    }
}

@Composable
fun StatusPulse(active: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                    .background(CyberCyan, CircleShape)
            )
        }
        Box(
            Modifier
                .size(10.dp)
                .background(if (active) CyberCyan else Color.Red, CircleShape)
                .border(2.dp, Color.Black.copy(alpha = 0.2f), CircleShape)
        )
    }
}

@Composable
fun StatusDetail(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = CyberGray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ManualField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = CyberBackground,
            focusedBorderColor = CyberCyan
        )
    )
}

// ── 語音對話：內嵌 /voice（喚醒/打斷/操控全功能）+ 麥克風授權 ─────────────────────
// ── 原生語音對話（能量球 + 字幕，直連 voice_server，免登入）────────────────────
@Composable
fun VoiceTab() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var wake by remember { mutableStateOf(false) }
    val active = VoiceEngine.active.value
    val speaking = VoiceEngine.speaking.value
    val vol = VoiceEngine.volume.value
    // 注意：不在 onDispose 停止語音——切到瀏覽器分頁時語音要繼續在背景跑

    // 能量球脈動
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    val userSpeaking = active && !speaking && vol > 0.045f   // 偵測使用者正在說話
    val orbScale = when {
        speaking -> 1.15f * pulse                            // AI 回應：穩定脈動
        active -> (1f + vol * 2.2f).coerceAtMost(1.7f)       // 聆聽：隨音量即時放大（語音輸入回饋）
        else -> 1f
    }
    val orbColor = when {
        speaking -> CyberPurple        // AI 回應中（紫）
        userSpeaking -> Color(0xFF22D3EE) // 使用者說話中（亮青）
        active -> CyberCyan            // 聆聽中
        else -> CyberGray              // 停止
    }
    val phase = when {
        speaking -> "🔊 回應中…"
        userSpeaking -> "🎤 聆聽中…"
        active -> "● 已連線 · 請說話"
        else -> VoiceEngine.status.value
    }

    Column(
        Modifier.fillMaxSize().background(CyberBackground).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("語音對話", color = CyberCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("喚醒", color = CyberGray, fontSize = 12.sp)
                Switch(checked = wake, onCheckedChange = { wake = it }, enabled = !active)
            }
        }

        Spacer(Modifier.weight(1f))
        // 能量球（隨語音輸入即時脈動 = 語音輸入回饋）
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            // 說話時的外環光暈
            if (userSpeaking || speaking) {
                Box(Modifier.size((160 + vol * 240).dp.coerceAtMost(200.dp)).clip(CircleShape)
                    .background(orbColor.copy(alpha = 0.12f)))
            }
            Box(
                Modifier.size(150.dp).scale(orbScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(orbColor.copy(alpha = 0.95f), orbColor.copy(alpha = 0.15f))))
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(phase, color = if (userSpeaking) Color(0xFF22D3EE) else CyberGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))

        // 字幕區（佔剩餘空間、可上下滑；內容更新自動捲到最新）
        val scroll = rememberScrollState()
        LaunchedEffect(VoiceEngine.transcript.value, VoiceEngine.steps.value) { scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            // 處理序列：AI 正在做的每一步（查資料、開瀏覽器、讀結果…）即時逐行顯示
            val stepText = VoiceEngine.steps.value
            if (stepText.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberSurface.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("處理序列", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    stepText.split("\n").forEach { line ->
                        Text("· $line", color = CyberCyan.copy(alpha = 0.75f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            if (VoiceEngine.userText.value.isNotEmpty())
                Text("你：${VoiceEngine.userText.value}", color = CyberGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (VoiceEngine.transcript.value.isNotEmpty())
                Text(renderMarkdown(VoiceEngine.transcript.value), color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(8.dp))
        // 控制列
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (active) {
                IconButton(
                    onClick = { VoiceEngine.toggleMute() },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(CyberSurface)
                ) { Icon(if (VoiceEngine.muted.value) Icons.Default.MicOff else Icons.Default.Mic, "mute",
                    tint = if (VoiceEngine.muted.value) Color(0xFFEF4444) else CyberGray) }
            }
            // 主按鈕：開始/結束通話
            IconButton(
                onClick = { if (active) VoiceEngine.stop() else VoiceEngine.start(ctx, prefs.paiBase, wake) },
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .background(if (active) Color(0xFFEF4444) else CyberCyan)
            ) { Icon(if (active) Icons.Default.Close else Icons.Default.Call, "call", tint = Color.Black, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── 瀏覽器：顯示「常駐」受控 WebView（同一實例，不隨分頁重建，背景也持續可操作）────────
@Composable
fun BrowserTab() {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize().background(CyberBackground), factory = { c ->
            val wv = BrowserController.ensureWebView(c)
            BrowserController.attachContext(c) // 把 WebView base context 換成 Activity → JS 引擎正常
            (wv.parent as? android.view.ViewGroup)?.removeView(wv) // 脫離上次的 parent 再掛載
            wv
        })
        // 重新載入鈕（頁面空白/地圖沒渲染時用）。reload 內有 sleep，丟到背景執行緒避免卡 UI。
        FloatingActionButton(
            onClick = { Thread { try { BrowserController.reload() } catch (_: Throwable) {} }.start() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = CyberSurface
        ) { Icon(Icons.Default.Refresh, contentDescription = "重新載入", tint = CyberCyan) }
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
