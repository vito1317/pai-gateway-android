package com.vito.gateway

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 語音背景模式懸浮球（Gemini Live 風）：發光脈動的能量球，會呼吸、說話時放大變色。
 * 球下顯示一行即時狀態/字幕。可拖曳；點一下回 App。需 SYSTEM_ALERT_WINDOW 權限。
 */
object VoiceOverlay {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    private var orb: View? = null
    private var glow: View? = null
    private var label: TextView? = null
    private var pulse: ValueAnimator? = null
    @Volatile var showing = false
    @Volatile private var speaking = false
    @Volatile private var listening = false

    fun canDraw(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun requestPermission(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        if (!canDraw(app)) return
        showing = true
        main.post { try { if (view == null) build(app); view?.visibility = View.VISIBLE } catch (_: Throwable) {} }
    }

    /** 更新狀態：依「狀態文字」判斷聆聽/說話 → 換球的顏色與脈動；label 顯示最新一句。 */
    fun update(ctx: Context, status: String, user: String, ai: String) {
        if (!showing) return
        speaking = status.contains("回應") || ai.isNotBlank() && !user.endsWith("聆聽中…")
        listening = status.contains("聆聽") || status.contains("請說話") || status.contains("連線")
        main.post {
            try {
                recolor()
                val line = when {
                    ai.isNotBlank() -> ai
                    user.isNotBlank() -> "你：$user"
                    else -> status.ifBlank { "聆聽中…" }
                }
                label?.text = line.take(40)
            } catch (_: Throwable) {}
        }
    }

    fun hide() {
        showing = false
        main.post { try { pulse?.cancel(); view?.visibility = View.GONE } catch (_: Throwable) {} }
    }

    private fun orbColors(): IntArray = when {
        speaking -> intArrayOf(Color.parseColor("#FFA78BFA"), Color.parseColor("#88EC4899"), Color.parseColor("#00EC4899")) // 紫→粉（說話）
        listening -> intArrayOf(Color.parseColor("#FF22D3EE"), Color.parseColor("#8834D399"), Color.parseColor("#0034D399")) // 青→綠（聆聽）
        else -> intArrayOf(Color.parseColor("#FF38BDF8"), Color.parseColor("#665B6CF8"), Color.parseColor("#005B6CF8")) // 藍（待命）
    }

    private fun recolor() {
        val c = orbColors()
        (orb?.background as? GradientDrawable)?.colors = c
        (glow?.background as? GradientDrawable)?.colors = intArrayOf(c[1], Color.TRANSPARENT)
    }

    private fun build(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = app.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val orbBox = FrameLayout(app).apply { layoutParams = LinearLayout.LayoutParams(px(120), px(120)) }
        glow = View(app).apply {
            layoutParams = FrameLayout.LayoutParams(px(120), px(120), Gravity.CENTER)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#6622D3EE"), Color.TRANSPARENT)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = px(60).toFloat(); shape = GradientDrawable.OVAL
            }
        }
        orb = View(app).apply {
            layoutParams = FrameLayout.LayoutParams(px(78), px(78), Gravity.CENTER)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, orbColors()).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = px(42).toFloat(); shape = GradientDrawable.OVAL
            }
        }
        recolor()
        orbBox.addView(glow); orbBox.addView(orb)
        label = TextView(app).apply {
            text = "聆聽中…"
            setTextColor(Color.parseColor("#E0FFFFFF")); textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(px(6), px(4), px(6), px(6))
        }
        col.addView(orbBox); col.addView(label)

        // 呼吸脈動動畫（無限）：說話/聆聽時幅度更大
        pulse = ValueAnimator.ofFloat(0.9f, 1.12f).apply {
            duration = 1100; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val amp = if (speaking) 1.18f else if (listening) 1.06f else 1.0f
                orb?.scaleX = f * amp; orb?.scaleY = f * amp
                glow?.scaleX = f * (amp + 0.15f); glow?.scaleY = f * (amp + 0.15f)
                glow?.alpha = if (speaking) 0.9f else 0.55f
            }
            start()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            px(150), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = px(16); y = px(90) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        col.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > px(6) || abs(dy) > px(6)) moved = true
                    lp.x = startX - dx; lp.y = startY + dy  // gravity END → x 反向
                    try { wm.updateViewLayout(col, lp) } catch (_: Throwable) {}; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
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
        wm.addView(col, lp)
        view = col
    }
}
