package com.vito.gateway

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 輔助使用（AccessibilityService）：AI 操作整支手機的基礎。
 *  - screen_snapshot：讀取目前畫面可互動元素（[sN] 文字/類型/座標），任何 App 都行（含 LINE）。
 *  - screen_click / screen_type / screen_swipe / screen_back / screen_home：點擊、輸入、滑動、導覽。
 * 需使用者在「設定 → 協助工具（輔助使用）」手動開啟（特殊權限）。
 */
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains(ctx.packageName) == true

        fun openSettings(ctx: Context) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
    }

    // 最近一次 snapshot 的元素（ref -> node 中心座標/文字），給 click/type 用
    private data class El(val ref: String, val text: String, val cls: String, val rect: Rect,
                          val clickable: Boolean, val editable: Boolean)
    @Volatile private var lastEls: List<El> = emptyList()

    override fun onServiceConnected() {
        instance = this
        GatewayState.log("♿ 螢幕操作（輔助使用）已連接")
    }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 掃描目前畫面：可互動（可點/可輸入）+ 有文字的元素，給 AI 一份帶 [sN] 編號的清單。 */
    fun snapshot(): String {
        val root = rootInActiveWindow ?: return "（讀不到畫面——目前視窗不允許或螢幕關閉）"
        val out = mutableListOf<El>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            try {
                val r = Rect(); n.getBoundsInScreen(r)
                val text = (n.text?.toString() ?: "").ifEmpty { n.contentDescription?.toString() ?: "" }
                val editable = n.isEditable
                val clickable = n.isClickable
                if ((clickable || editable || text.isNotBlank()) && r.width() > 1 && r.height() > 1 && n.isVisibleToUser) {
                    val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                    if (clickable || editable || text.length in 1..60) {
                        out.add(El("s${out.size + 1}", text.replace(Regex("\\s+"), " ").take(60), cls, Rect(r), clickable, editable))
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            } catch (_: Throwable) {}
        }
        walk(root)
        val els = out.take(80)
        lastEls = els
        val pkg = root.packageName?.toString() ?: "?"
        val sb = StringBuilder("目前 App：$pkg\n可互動元素（共 ${els.size}）：\n")
        els.forEach { e ->
            sb.append("  [${e.ref}] ${e.cls}")
            if (e.editable) sb.append("(輸入框)") else if (e.clickable) sb.append("(可點)")
            if (e.text.isNotBlank()) sb.append(" \"${e.text}\"")
            sb.append("\n")
        }
        return sb.toString().take(5000)
    }

    private fun find(target: String): El? {
        val t = target.trim()
        lastEls.firstOrNull { it.ref == t }?.let { return it }
        val norm = { s: String -> s.replace(Regex("[\\s,，。]"), "") }
        val nt = norm(t)
        return lastEls.firstOrNull { norm(it.text) == nt }
            ?: lastEls.firstOrNull { nt.isNotEmpty() && norm(it.text).contains(nt) }
    }

    /** 點擊：先試元素 ACTION_CLICK，不行就在中心點派發手勢點擊。 */
    fun click(target: String): String {
        val el = find(target) ?: return "找不到「$target」——先 screen_snapshot 看當前畫面元素"
        val cx = el.rect.centerX().toFloat(); val cy = el.rect.centerY().toFloat()
        return if (tapAt(cx, cy)) "已點擊 [${el.ref}] ${el.text}\n\n" + snapAfter()
        else "點擊失敗（手勢未執行）"
    }

    /** 在輸入框輸入文字（ACTION_SET_TEXT，對任何 App 的輸入框有效）。 */
    fun type(target: String, text: String): String {
        val el = find(target)
        // 先點該位置聚焦
        if (el != null) tapAt(el.rect.centerX().toFloat(), el.rect.centerY().toFloat())
        Thread.sleep(500)
        val root = rootInActiveWindow ?: return "讀不到畫面"
        val node = findEditable(root, el?.rect) ?: return "找不到輸入框——先 screen_snapshot 確認"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "已輸入「$text」\n\n" + snapAfter() else "輸入失敗"
    }

    private fun findEditable(root: AccessibilityNodeInfo, near: Rect?): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            try {
                if (n.isEditable && n.isVisibleToUser) {
                    if (near == null) { if (best == null) best = n }
                    else {
                        val r = Rect(); n.getBoundsInScreen(r)
                        if (Rect.intersects(r, near) || best == null) best = n
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            } catch (_: Throwable) {}
        }
        walk(root)
        return best
    }

    /** 滑動（上/下/左/右）。 */
    fun swipe(direction: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val (from, to) = when (direction.lowercase().trim()) {
            "up", "上" -> Pair(Pair(w / 2, h * 0.7f), Pair(w / 2, h * 0.3f))
            "down", "下" -> Pair(Pair(w / 2, h * 0.3f), Pair(w / 2, h * 0.7f))
            "left", "左" -> Pair(Pair(w * 0.8f, h / 2), Pair(w * 0.2f, h / 2))
            "right", "右" -> Pair(Pair(w * 0.2f, h / 2), Pair(w * 0.8f, h / 2))
            else -> return "direction 請用 up/down/left/right"
        }
        val path = Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }
        val ok = dispatchGestureSync(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build())
        return if (ok) "已滑動 $direction\n\n" + snapAfter() else "滑動失敗"
    }

    fun back(): String { performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(600); return "已按返回\n\n" + snapAfter() }
    fun home(): String { performGlobalAction(GLOBAL_ACTION_HOME); return "已回主畫面" }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGestureSync(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build())
    }

    private fun dispatchGestureSync(g: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gd: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun snapAfter(): String {
        Thread.sleep(700)
        return snapshot()
    }
}
