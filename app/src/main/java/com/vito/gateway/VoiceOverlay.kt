package com.vito.gateway

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 語音背景模式懸浮視窗：App 縮到背景時，浮在所有畫面之上顯示「狀態 / 你說的(輸入) / AI 回應(輸出)」。
 * 可拖曳；點一下回 App。需 SYSTEM_ALERT_WINDOW 權限。
 */
object VoiceOverlay {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    private var statusView: TextView? = null
    private var userView: TextView? = null
    private var aiView: TextView? = null
    @Volatile var showing = false

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun requestPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {}
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        if (!canDraw(app)) return
        showing = true
        main.post { try { if (view == null) build(app); view?.visibility = View.VISIBLE } catch (_: Throwable) {} }
    }

    /** 更新（狀態 / 使用者輸入 / AI 輸出）。 */
    fun update(ctx: Context, status: String, user: String, ai: String) {
        if (!showing) return
        main.post {
            try {
                statusView?.text = "🤖 PAI 背景模式 · ${status.ifBlank { "待命" }}（點此回 App）"
                userView?.text = if (user.isBlank()) "🎙 聆聽中…" else "你：$user"
                aiView?.text = ai.ifBlank { "…" }
            } catch (_: Throwable) {}
        }
    }

    fun hide() {
        showing = false
        main.post { try { view?.visibility = View.GONE } catch (_: Throwable) {} }
    }

    private fun build(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = app.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(Color.parseColor("#F00B1220"))
                setStroke(px(1), Color.parseColor("#5522D3EE"))
            }
        }
        statusView = TextView(app).apply {
            text = "🤖 PAI 背景模式 · 待命（點此回 App）"
            setTextColor(Color.parseColor("#22D3EE")); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        userView = TextView(app).apply {
            text = "🎙 聆聽中…"
            setTextColor(Color.parseColor("#94A3B8")); textSize = 12f
            setPadding(0, px(5), 0, 0)
        }
        aiView = TextView(app).apply {
            text = "…"
            setTextColor(Color.parseColor("#F0FFFFFF")); textSize = 14f
            setPadding(0, px(3), 0, 0); maxLines = 8
        }
        card.addView(statusView); card.addView(userView); card.addView(aiView)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            px(290), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = px(12); y = px(70) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        card.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > px(6) || abs(dy) > px(6)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    try { wm.updateViewLayout(card, lp) } catch (_: Throwable) {}; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 點一下 → 回 App 前景 + 收掉浮窗 + 關背景模式
                        try {
                            app.startActivity(Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        } catch (_: Throwable) {}
                        VoiceEngine.bgMode.value = false
                        hide()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(card, lp)
        view = card
    }
}
