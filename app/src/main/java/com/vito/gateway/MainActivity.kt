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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.platform.LocalConfiguration
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
        intent?.getStringExtra("notice_text")?.let {
            if (it.isNotEmpty()) {
                // 先設標題（沒帶就用預設），避免沿用上一則（如「看圖結果」）的舊標題
                GatewayState.noticeTitle.value = intent.getStringExtra("notice_title")?.ifEmpty { "PAI 通知" } ?: "PAI 通知"
                GatewayState.noticeUrl.value = ""
                GatewayState.noticeActions.value = intent.getStringExtra("notice_actions") ?: ""
                GatewayState.noticeText.value = it
            }
        }
    }

    private fun requestRuntimePerms() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
        }
    }
}

enum class NavItem(val label: String, val icon: ImageVector) {
    Node("節點", Icons.Default.Dns),
    Chat("訊息", Icons.Default.Chat),
    Voice("語音", Icons.Default.Mic),
    Auto("自動化", Icons.Default.Bolt),
    Browser("瀏覽器", Icons.Default.Language)
}

@Composable
fun RootScreen() {
    var selectedItem by remember { mutableStateOf(NavItem.Node) }
    val rootCtx = LocalContext.current

    // 螢幕投影：系統同意框（Android 14+ 可選「單一 App / 整個螢幕」）→ 啟動投影服務 + 開始拉幀
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            MediaProjectionService.start(rootCtx, result.resultCode, result.data!!)
            VoiceEngine.setLiveVision(rootCtx, "screen")
        } else {
            VoiceEngine.setLiveVision(rootCtx, "off")
        }
        GatewayState.requestProjection.value = false
    }
    LaunchedEffect(GatewayState.requestProjection.value) {
        if (GatewayState.requestProjection.value) {
            try {
                val mgr = rootCtx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                projLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (e: Throwable) {
                GatewayState.requestProjection.value = false
                GatewayState.log("無法開啟螢幕投影：${e.message}")
            }
        }
    }

    // 工具呼叫（browser_*）請求切到瀏覽器分頁時自動切換（語音仍在背景跑）
    LaunchedEffect(GatewayState.requestTab.value) {
        when (GatewayState.requestTab.value) {
            "browser" -> selectedItem = NavItem.Browser
            "voice" -> selectedItem = NavItem.Voice
            "node" -> selectedItem = NavItem.Node
            "chat" -> selectedItem = NavItem.Chat
            "auto" -> selectedItem = NavItem.Auto
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
                NavItem.Chat -> Box(Modifier.fillMaxSize().zIndex(2f).background(CyberBackground)) { ChatTab() }
                NavItem.Voice -> Box(Modifier.fillMaxSize().zIndex(2f).background(CyberBackground)) { VoiceTab() }
                NavItem.Auto -> Box(Modifier.fillMaxSize().zIndex(2f).background(CyberBackground)) { AutomationsTab() }
                NavItem.Browser -> {}
            }
            // 操作瀏覽器時：右下角顯示語音迷你卡（即時狀態/處理步驟/回覆摘要），點一下回語音分頁
            if (browserVisible && VoiceEngine.active.value) {
                VoiceMiniOverlay(
                    onTap = { selectedItem = NavItem.Voice },
                    modifier = Modifier.align(Alignment.BottomEnd).zIndex(3f)
                )
            }
        }
    }

    // show_document / 點通知 → 自動彈出完整內容（markdown、可滑、可開連結/分享）
    if (GatewayState.noticeText.value.isNotEmpty()) {
        val ctx = LocalContext.current
        val url = GatewayState.noticeUrl.value
        // 動作按鈕（HITL 接受/拒絕等）：與通知欄相同，按下帶憑證 POST 到 path
        val noticeActions = remember(GatewayState.noticeActions.value) {
            runCatching { org.json.JSONArray(GatewayState.noticeActions.value) }.getOrNull()
        }
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
                    if (noticeActions != null && noticeActions.length() > 0) {
                        Spacer(Modifier.height(12.dp))
                        for (i in 0 until noticeActions.length()) {
                            val a = noticeActions.optJSONObject(i) ?: continue
                            val label = a.optString("label"); val path = a.optString("path")
                            if (label.isEmpty() || path.isEmpty()) continue
                            val body = a.optJSONObject("body")?.toString() ?: "{}"
                            Button(
                                onClick = {
                                    NotifAction.post(ctx, path, body, label)
                                    GatewayState.noticeText.value = ""
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.18f), contentColor = CyberCyan)
                            ) { Text(label) }
                        }
                    }
                }
            },
            containerColor = CyberSurface
        )
    }
}

/** 權限中心：列出特殊權限狀態 + 一鍵開啟（這些系統規定要手動授權，無法程式給）。 */
@Composable
fun PermissionCenter(ctx: android.content.Context) {
    val notiOn = NotificationListener.isEnabled(ctx)
    val accOn = PhoneAccessibilityService.isEnabled(ctx)
    val writeOn = android.provider.Settings.System.canWrite(ctx)
    val overlayOn = ControlOverlay.canDraw(ctx)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CyberSurface).padding(14.dp)
    ) {
        Text("🔓 權限中心", color = CyberCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("特殊權限需手動開啟（系統規定）。開齊後 AI 才能操作 App、回 LINE、調亮度。",
            color = CyberGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        PermRow("通知存取（讀取/回覆 LINE 等通知）", notiOn) { NotificationListener.openSettings(ctx) }
        PermRow("協助工具（讀畫面＋點擊操作任何 App）", accOn) { PhoneAccessibilityService.openSettings(ctx) }
        PermRow("懸浮窗（AI 操作時顯示進度浮框）", overlayOn) { ControlOverlay.requestPermission(ctx) }
        // 電池最佳化豁免（避免反向連線/語音被系統殺掉）
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        PermRow("電池不限制（避免背景被殺）", battOk) {
            try {
                ctx.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
        // 小米/HyperOS 自動啟動（找不到就開 App 詳情頁讓使用者手動）
        Text("小米/HyperOS 請另到「設定→應用程式→PAI Gateway」開啟『自動啟動』，服務才不會被關。",
            color = CyberGray, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        PermRow("修改系統設定（調整螢幕亮度）", writeOn) {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (granted) "✅" else "⚠️", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onOpen) { Text("開啟", color = CyberCyan, fontSize = 12.sp) }
        } else {
            Text("已開啟", color = CyberGray, fontSize = 11.sp)
        }
    }
}

/** 操作瀏覽器時右下角的語音迷你卡：迷你能量球 + 即時狀態 + 最新處理步驟 + AI 回覆摘要。 */
@Composable
fun VoiceMiniOverlay(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val speaking = VoiceEngine.speaking.value
    val vol = VoiceEngine.volume.value
    val status = VoiceEngine.status.value
    val step = VoiceEngine.steps.value.split("\n").lastOrNull { it.isNotBlank() } ?: ""
    val ai = VoiceEngine.transcript.value
    Column(
        modifier
            .padding(end = 12.dp, bottom = 12.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CyberSurface.copy(alpha = 0.93f))
            .clickable { onTap() }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 迷你能量球（隨音量/回應狀態變化）
            val scale = 1f + (vol * 4f).coerceAtMost(0.6f)
            Box(
                Modifier.size(18.dp).scale(scale).clip(CircleShape).background(
                    Brush.radialGradient(listOf(
                        (if (speaking) Color(0xFF8B5CF6) else CyberCyan).copy(alpha = 0.95f),
                        Color.Transparent
                    ))
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (speaking) "回應中…" else status.ifEmpty { "聆聽中" },
                color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (step.isNotEmpty())
            Text(step, color = CyberCyan.copy(alpha = 0.75f), fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (ai.isNotEmpty())
            Text(renderMarkdown(ai), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
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
        } else if (applyPairCode(ctx, c, prefs)) {
            paiBase = prefs.paiBase; token = prefs.registerToken
            // 服務啟動由 applyPairCode 處理（pair 版兌換完才啟動；token 版直接啟動）
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
                                if (applyPairCode(ctx, pairCode, prefs)) {
                                    paiBase = prefs.paiBase; token = prefs.registerToken
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

        // 權限中心：特殊權限需手動開（系統規定），這裡一目了然 + 一鍵開設定
        item { PermissionCenter(ctx) }

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
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val orbOuter = if (landscape) 110.dp else 200.dp
    val orbInner = if (landscape) 84.dp else 150.dp
    var wake by GatewayState.wake   // 共用狀態：遠端 voice_start 自動喚醒時，這個開關會同步顯示成開啟
    val active = VoiceEngine.active.value
    val speaking = VoiceEngine.speaking.value
    val vol = VoiceEngine.volume.value
    // 注意：不在 onDispose 停止語音——切到瀏覽器分頁時語音要繼續在背景跑

    // 快速設定磚啟動 → 自動開始語音
    LaunchedEffect(GatewayState.autoStartVoice.value) {
        if (GatewayState.autoStartVoice.value) {
            GatewayState.autoStartVoice.value = false
            if (!VoiceEngine.active.value) VoiceEngine.start(ctx, prefs.paiBase, wake)
        }
    }

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
        VoiceEngine.standby.value -> Color(0xFF475569) // 待機中（暗灰藍）
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
                // 開車模式：免手操作、念新通知、主動問目的地（需語音連線中）
                if (active) {
                    val drv = VoiceEngine.drivingMode.value
                    TextButton(onClick = { VoiceEngine.setDriving(ctx, !drv) }) {
                        Text(if (drv) "🚗 開車中" else "🚗 開車", color = if (drv) Color(0xFF34D399) else CyberGray, fontSize = 12.sp)
                    }
                }
                Text("喚醒", color = CyberGray, fontSize = 12.sp)
                Switch(checked = wake, onCheckedChange = { wake = it }, enabled = !active)
            }
        }

        Spacer(Modifier.height(if (landscape) 6.dp else 0.dp).then(if (landscape) Modifier else Modifier.weight(1f)))
        // 能量球（隨語音輸入即時脈動 = 語音輸入回饋）；橫向縮小避免擠壓底部按鈕
        Box(Modifier.size(orbOuter), contentAlignment = Alignment.Center) {
            // 說話時的外環光暈
            if (userSpeaking || speaking) {
                Box(Modifier.size(((orbInner.value + 10) + vol * 240).dp.coerceAtMost(orbOuter.value.dp)).clip(CircleShape)
                    .background(orbColor.copy(alpha = 0.12f)))
            }
            Box(
                Modifier.size(orbInner).scale(orbScale).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(orbColor.copy(alpha = 0.95f), orbColor.copy(alpha = 0.15f))))
            )
        }
        Spacer(Modifier.height(if (landscape) 6.dp else 12.dp))
        Text(phase, color = if (userSpeaking) Color(0xFF22D3EE) else CyberGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))

        // 即時畫面投影：用系統 MediaProjection（可選單一 App / 整個螢幕）。開了之後 AI 邊聽邊看畫面。
        run {
            val live = VoiceEngine.liveVision.value == "screen"
            OutlinedButton(
                onClick = {
                    if (live) VoiceEngine.setLiveVision(ctx, "off")
                    else GatewayState.requestProjection.value = true   // 跳系統同意框（可選 App/整個螢幕）
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (live) Color(0xFF065F46) else Color.Transparent,
                    contentColor = if (live) Color(0xFF6EE7B7) else CyberCyan
                ),
                border = BorderStroke(1.dp, if (live) Color(0xFF34D399) else CyberCyan.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.ScreenShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (live) "🖥 投影中（AI 正在看畫面）· 點此停止" else "🖥 螢幕投影給 AI 看（可選 App）", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 鏡頭即時投影：AI 邊聽邊看鏡頭（看世界）
        run {
            val cam = VoiceEngine.liveVision.value == "camera"
            OutlinedButton(
                onClick = { VoiceEngine.setLiveVision(ctx, if (cam) "off" else "camera") },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (cam) Color(0xFF065F46) else Color.Transparent,
                    contentColor = if (cam) Color(0xFF6EE7B7) else CyberCyan
                ),
                border = BorderStroke(1.dp, if (cam) Color(0xFF34D399) else CyberCyan.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (cam) "📷 鏡頭投影中（AI 正在看鏡頭）· 點此停止" else "📷 鏡頭投影給 AI 看", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 前向警戒：鏡頭本地物體偵測，逼近物毫秒級警示（不經雲端）；安全哨兵：撞擊/跌倒自動求援
        run {
            val guard = CollisionGuard.active.value
            OutlinedButton(
                onClick = {
                    val p = Prefs(ctx)
                    if (guard) { p.collisionGuard = false; CollisionGuard.stop() }
                    else { p.collisionGuard = true; CollisionGuard.start(ctx) }
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (guard) Color(0xFF7C2D12) else Color.Transparent,
                    contentColor = if (guard) Color(0xFFFDBA74) else CyberCyan
                ),
                border = BorderStroke(1.dp, if (guard) Color(0xFFF97316) else CyberCyan.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (guard) "👁 前向警戒中（鏡頭朝前）· 點此停止" else "👁 前向警戒（逼近物即時警示）", fontSize = 13.sp)
            }
            // 距離監看畫面：即時鏡頭 + 物體框 + 估算距離
            if (guard) {
                var showMon by remember { mutableStateOf(false) }
                TextButton(onClick = { showMon = true; CollisionGuard.monitorOn = true }) {
                    Text("📏 顯示距離畫面", color = CyberCyan, fontSize = 12.sp)
                }
                if (showMon) {
                    Dialog(
                        onDismissRequest = { showMon = false; CollisionGuard.monitorOn = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        val danger = CollisionGuard.monitorDanger.value
                        Column(Modifier.fillMaxSize().background(Color.Black).padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("📏 前向警戒・距離監看", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { showMon = false; CollisionGuard.monitorOn = false }) {
                                    Text("關閉", color = CyberGray)
                                }
                            }
                            val f = CollisionGuard.monitorFrame.value
                            if (f != null) {
                                Image(
                                    bitmap = f.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Text("等待鏡頭畫面…", color = CyberGray)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (danger) "🔴 危險：前方物體逼近中" else "🟢 前方安全",
                                color = if (danger) Color(0xFFFF5252) else Color(0xFF4CFF9A),
                                fontSize = 18.sp, fontWeight = FontWeight.Bold
                            )
                            CollisionGuard.monitorInfo.value.forEach {
                                Text(it, color = CyberGray, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("＊距離為單眼視覺估算（假設物體實寬約 60cm），僅供參考，勿作安全依據",
                                color = CyberGray.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        run {
            var impact by remember { mutableStateOf(Prefs(ctx).impactGuard) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡 安全哨兵（撞擊/跌倒自動確認求援）", color = CyberGray, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = impact, onCheckedChange = {
                    impact = it; Prefs(ctx).impactGuard = it
                    if (it) ImpactSentinel.start(ctx) else ImpactSentinel.stop()
                }, modifier = Modifier.scale(0.75f))
            }
        }
        run {
            var health by remember { mutableStateOf(Prefs(ctx).healthGuard) }
            val healthPerms = rememberLauncherForActivityResult(
                androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
            ) { granted ->
                if (granted.containsAll(HealthSentinel.PERMS)) {
                    Prefs(ctx).healthGuard = true; health = true; HealthSentinel.start(ctx)
                } else {
                    health = false; Prefs(ctx).healthGuard = false
                    GatewayState.log("健康守護：權限未完整授權（心率/睡眠/步數）")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("❤️ 健康守護（手錶心率異常提醒）", color = CyberGray, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = health, onCheckedChange = {
                    if (it) {
                        try { healthPerms.launch(HealthSentinel.PERMS) }
                        catch (e: Throwable) { GatewayState.log("健康守護：無法開啟授權（${e.message}）") }
                    } else { health = false; Prefs(ctx).healthGuard = false; HealthSentinel.stop() }
                }, modifier = Modifier.scale(0.75f))
            }
        }
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

        // 拍照給 AI 看（TakePicturePreview 直接回縮圖 Bitmap）
        val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp != null) {
                // 語音對話進行中 → 把照片掛進對話（之後直接講話追問）；否則一次性看圖
                if (VoiceEngine.active.value) VisionClient.attachToVoice(ctx, bmp)
                else VisionClient.analyze(ctx, bmp, "請看這張照片，用台灣正體中文說明你看到什麼。")
            }
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
            // 📷 拍照問 AI（看圖）
            IconButton(
                onClick = { photoLauncher.launch(null) },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(CyberSurface)
            ) { Icon(Icons.Default.PhotoCamera, "拍照問 AI", tint = CyberCyan) }
            // 🪟 背景模式：縮到背景 + 懸浮視窗顯示狀態/輸入/輸出（需懸浮窗權限）
            if (active) {
                IconButton(
                    onClick = {
                        if (!VoiceOverlay.canDraw(ctx)) {
                            VoiceOverlay.requestPermission(ctx)
                        } else {
                            VoiceEngine.bgMode.value = true
                            VoiceOverlay.show(ctx); VoiceEngine.pushOverlay()
                            (ctx as? android.app.Activity)?.moveTaskToBack(true)
                        }
                    },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(CyberSurface)
                ) { Icon(Icons.Default.PictureInPictureAlt, "背景模式", tint = CyberCyan) }
            }
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
        // 移到左下角，避免和右下角的語音迷你卡重疊
        FloatingActionButton(
            onClick = { Thread { try { BrowserController.reload() } catch (_: Throwable) {} }.start() },
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
            containerColor = CyberSurface
        ) { Icon(Icons.Default.Refresh, contentDescription = "重新載入", tint = CyberCyan) }
    }
}

/**
 * 配對碼：base64 或 JSON。
 *  - 新版 {pai, pair}：一次性碼 → 背景兌換成「此帳號的長期 per-device 憑證」(綁帳號，安全)。
 *  - 舊版 {pai, token}：直接當共用憑證寫入（向後相容）。
 * 回 true=解析成功（pair 版會接著非同步兌換）。
 */
private fun applyPairCode(ctx: android.content.Context, code: String, prefs: Prefs): Boolean {
    val raw = code.trim()
    if (raw.isEmpty()) return false
    return try {
        val json = if (raw.startsWith("{")) raw else String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
        val o = JSONObject(json)
        prefs.paiBase = o.optString("pai", prefs.paiBase)
        if (o.has("name")) prefs.nodeName = o.getString("name")
        val pair = o.optString("pair", "")
        if (pair.isNotBlank()) {
            exchangePair(ctx, prefs, pair)   // 一次性碼 → 換長期憑證（背景）
            true
        } else {
            prefs.registerToken = o.optString("token", prefs.registerToken)
            val ok = prefs.registerToken.isNotBlank()
            if (ok) { GatewayState.log("✅ 已配對 ${prefs.nodeName}，啟動中…"); GatewayService.start(ctx) }
            ok
        }
    } catch (e: Throwable) { false }
}

/** 用一次性配對碼兌換長期 per-device 憑證，存起來並啟動節點服務。 */
private fun exchangePair(ctx: android.content.Context, prefs: Prefs, pairToken: String) {
    GatewayState.log("🔑 配對中…")
    kotlin.concurrent.thread {
        try {
            val body = JSONObject().apply { put("pair_token", pairToken); put("name", prefs.nodeName) }.toString()
            val c = (java.net.URL("${prefs.paiBase.trimEnd('/')}/api/gateway/pair").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                val o = JSONObject(resp)
                prefs.registerToken = o.optString("device_token")
                GatewayState.log("✅ 已配對到帳號：${o.optString("account")}，啟動中…")
                GatewayService.start(ctx)
            } else {
                GatewayState.log("配對失敗（$code）：配對碼可能已過期，請重新產生")
            }
        } catch (e: Throwable) { GatewayState.log("配對失敗：${e.message}") }
    }
}

private fun pairWithStart(ctx: android.content.Context, prefs: Prefs) {
    if (prefs.registerToken.isBlank()) { GatewayState.log("請先填註冊 Token 或貼配對碼"); return }
    GatewayService.start(ctx)
}
