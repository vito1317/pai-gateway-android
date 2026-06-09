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
 * 投影/鏡頭模式時的懸浮視窗：App 在背景時，浮在所有畫面之上顯示 AI 目前回應 + 你說的話。
 * 可拖曳；點一下回 App。需 SYSTEM_ALERT_WINDOW 權限（與 ControlOverlay 同）。
 */
object VisionOverlay {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
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

    /** 更新內容（AI 回應 + 使用者逐字稿）。 */
    fun update(ctx: Context, ai: String, user: String) {
        if (!showing) return
        main.post {
            try {
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
                setStroke(px(1), Color.parseColor("#5534D399"))
            }
        }
        val title = TextView(app).apply {
            text = "🤖 PAI · 投影中（點此回 App）"
            setTextColor(Color.parseColor("#34D399")); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        userView = TextView(app).apply {
            text = "🎙 聆聽中…"
            setTextColor(Color.parseColor("#9422D3EE")); textSize = 11f
            setPadding(0, px(4), 0, 0)
        }
        aiView = TextView(app).apply {
            text = "…"
            setTextColor(Color.parseColor("#F0FFFFFF")); textSize = 14f
            setPadding(0, px(3), 0, 0); maxLines = 6
        }
        card.addView(title); card.addView(userView); card.addView(aiView)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            px(280), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = px(12); y = px(80) }

        // 拖曳 + 點擊回 App
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
                        try {
                            app.startActivity(Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        } catch (_: Throwable) {}
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
