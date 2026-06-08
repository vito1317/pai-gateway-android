package com.vito.gateway

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 訊息分頁：對話列表 ↔ 對話內容（發文字/圖片，與 web 共用同一批對話）。 */
@Composable
fun ChatTab() {
    val ctx = LocalContext.current
    val inConv = ChatStore.currentConvId.value != null

    LaunchedEffect(Unit) {
        if (ChatStore.currentConvId.value == null) ChatStore.refreshList(ctx)
    }

    if (!inConv) ChatListView() else ChatDetailView()
}

@Composable
private fun ChatListView() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertical,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("訊息", color = CyberCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertical) {
                IconButton(onClick = { ChatStore.refreshList(ctx) }) {
                    Icon(Icons.Default.Refresh, "重新整理", tint = CyberGray)
                }
                Button(
                    onClick = { ChatStore.newConv() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberPurple)
                ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新對話") }
            }
        }
        if (ChatStore.error.value.isNotEmpty()) {
            Text(ChatStore.error.value, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(16.dp, 0.dp))
        }
        if (ChatStore.loadingList.value && ChatStore.conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = CyberCyan) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 24.dp)) {
            items(ChatStore.conversations, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp)).background(CyberSurface)
                        .clickable { ChatStore.openConv(ctx, c.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertical
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(CyberPurple), Alignment.Center) {
                        Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.preview.isNotEmpty()) {
                            val prefix = if (c.role == "assistant") "AI：" else ""
                            Text("$prefix${c.preview}", color = CyberGray, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = CyberGray)
                }
            }
        }
    }
}

@Composable
private fun ChatDetailView() {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { pendingImage = decodeUri(ctx, it) }
    }

    // 新訊息進來 → 捲到底
    LaunchedEffect(ChatStore.messages.size, ChatStore.sending.value) {
        if (ChatStore.messages.isNotEmpty()) listState.animateScrollToItem(ChatStore.messages.size)
    }

    Column(Modifier.fillMaxSize()) {
        // 頂部列：返回 + 標題 + 終止
        Row(
            Modifier.fillMaxWidth().background(CyberSurface).padding(8.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertical
        ) {
            IconButton(onClick = { ChatStore.back(ctx) }) { Icon(Icons.Default.ArrowBack, "返回", tint = CyberCyan) }
            Text(
                ChatStore.title.value.ifEmpty { "新對話" }, color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (ChatStore.sending.value) {
                TextButton(onClick = { ChatStore.stop(ctx) }) { Text("■ 終止", color = Color(0xFFF87171), fontSize = 13.sp) }
            }
        }

        // 訊息列表
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 8.dp)) {
            items(ChatStore.messages) { m -> MessageBubble(m) }
            if (ChatStore.sending.value) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(CyberSurface).padding(12.dp, 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertical) {
                                CircularProgressIndicator(Modifier.size(14.dp), color = CyberCyan, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp)); Text("思考中…", color = CyberGray, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // 待送圖片預覽
        pendingImage?.let { bmp ->
            Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertical) {
                Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(8.dp))
                Text("圖片已附上", color = CyberGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { pendingImage = null }) { Icon(Icons.Default.Close, "移除", tint = CyberGray) }
            }
        }

        // 輸入列
        Row(
            Modifier.fillMaxWidth().background(CyberSurface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertical
        ) {
            IconButton(onClick = { picker.launch("image/*") }, enabled = !ChatStore.sending.value) {
                Icon(Icons.Default.Image, "附圖", tint = CyberCyan)
            }
            TextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("跟 AI 說一句話…", color = CyberGray) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CyberBackground, unfocusedContainerColor = CyberBackground,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            val canSend = (input.isNotBlank() || pendingImage != null) && !ChatStore.sending.value
            IconButton(
                onClick = {
                    ChatStore.send(ctx, input.trim(), pendingImage)
                    input = ""; pendingImage = null
                },
                enabled = canSend
            ) {
                Icon(Icons.Default.Send, "送出", tint = if (canSend) CyberCyan else CyberGray)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMsg) {
    val isUser = m.role == "user"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (isUser) CyberPurple else CyberSurface).padding(12.dp, 10.dp)
        ) {
            // 使用者發的圖
            m.image?.let { dataUri ->
                decodeDataUri(dataUri)?.let { bmp ->
                    Image(bmp.asImageBitmap(), null,
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit)
                    if (m.content.isNotBlank() && m.content != "（圖片）") Spacer(Modifier.height(6.dp))
                }
            }
            if (m.content.isNotBlank() && !(m.image != null && m.content == "（圖片）")) {
                if (isUser) {
                    Text(m.content, color = Color.White, fontSize = 15.sp)
                } else {
                    Text(renderMarkdown(m.content), color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

/** 圖庫 Uri → Bitmap（軟體配置，才能再壓縮編碼）。 */
private fun decodeUri(ctx: android.content.Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
    }
} catch (e: Throwable) { null }

/** data:image/...;base64,xxx → Bitmap。 */
private fun decodeDataUri(dataUri: String): Bitmap? = try {
    val b64 = dataUri.substringAfter("base64,", "")
    if (b64.isEmpty()) null else {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
} catch (e: Throwable) { null }
