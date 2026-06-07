package com.vito.gateway

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AI 操作手機時的系統級懸浮框：浮在所有 App 之上（包含 LINE 等），顯示「🤖 AI 操作中 + 目前動作」。
 * 需要 SYSTEM_ALERT_WINDOW（顯示在其他應用程式上層）權限。
 * McpTools 在執行 screen_*/maps_route 等操作時自動 show，閒置幾秒後自動 hide。
 */
object ControlOverlay {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    private var titleView: TextView? = null
    private var actionView: TextView? = null
    @Volatile private var hideAt = 0L

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun requestPermission(ctx: Context) {
        try {
            ctx.startActivity(
                android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {}
    }

    /** 顯示/更新浮框文字。action=目前在做什麼。沒有懸浮窗權限就忽略（不影響操作本身）。 */
    fun show(ctx: Context, action: String) {
        val app = ctx.applicationContext
        if (!canDraw(app)) return
        hideAt = System.currentTimeMillis() + 6000
        main.post {
            try {
                if (view == null) build(app)
                actionView?.text = action.ifBlank { "處理中…" }
                view?.visibility = View.VISIBLE
            } catch (_: Throwable) {}
        }
        // 閒置自動隱藏
        main.postDelayed({ if (System.currentTimeMillis() >= hideAt) hide() }, 6200)
    }

    fun hide() {
        main.post { try { view?.visibility = View.GONE } catch (_: Throwable) {} }
    }

    private fun build(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = app.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(14).toFloat()
                setColor(Color.parseColor("#E6101826"))
                setStroke(px(1), Color.parseColor("#3322D3EE"))
            }
        }
        titleView = TextView(app).apply {
            text = "🤖 AI 操作手機中"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        actionView = TextView(app).apply {
            text = "處理中…"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            setPadding(0, px(3), 0, 0)
        }
        card.addView(titleView)
        card.addView(actionView)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(60)
        }
        wm.addView(card, lp)
        view = card
    }
}
