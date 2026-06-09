package com.vito.gateway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 鏡頭即時擷取（CameraX）：背景持續分析最新一幀，VoiceEngine 投影迴圈 grabB64() 抓最新一張給 AI。
 * 無 UI、用自建 LifecycleOwner 綁定相機；預設後鏡頭（看世界）。
 */
object CameraCapture {
    @Volatile private var latest: Bitmap? = null
    private var provider: ProcessCameraProvider? = null
    private var owner: HeadlessLifecycle? = null
    private var appCtx: Context? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var lensBack = true   // true=後鏡頭，false=前鏡頭

    /** 開啟鏡頭（在主執行緒綁定）。重複呼叫安全。 */
    fun start(ctx: Context) {
        if (provider != null) return
        val app = ctx.applicationContext
        appCtx = app
        ContextCompat.getMainExecutor(app).execute {
            try {
                val o = HeadlessLifecycle().also { owner = it; it.resume() }
                val future = ProcessCameraProvider.getInstance(app)
                future.addListener({
                    try {
                        val p = future.get(); provider = p
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(exec) { img ->
                            try {
                                val bmp = img.toBitmap()
                                latest = rotate(bmp, img.imageInfo.rotationDegrees)
                            } catch (_: Throwable) {} finally { img.close() }
                        }
                        val selector = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        p.unbindAll()
                        p.bindToLifecycle(o, selector, analysis)
                        GatewayState.log("📷 鏡頭即時投影已開始")
                    } catch (e: Throwable) { GatewayState.log("鏡頭啟動失敗：${e.message}") }
                }, ContextCompat.getMainExecutor(app))
            } catch (e: Throwable) { GatewayState.log("鏡頭啟動失敗：${e.message}") }
        }
    }

    fun grabB64(): String? {
        val b = latest ?: return null
        return try {
            val scale = (1080f / b.width).coerceAtMost(1f)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(b, 1080, (b.height * scale).toInt(), true) else b
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) { null }
    }

    fun stop() {
        val p = provider; val o = owner; val ctx = appCtx
        provider = null; owner = null; latest = null
        if (ctx == null) return
        try {
            ContextCompat.getMainExecutor(ctx).execute {
                try { p?.unbindAll() } catch (_: Throwable) {}
                try { o?.destroy() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        return try {
            Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        } catch (e: Throwable) { b }
    }

    /** 無 UI 的 LifecycleOwner，給 CameraX 綁定用（狀態切換需在主執行緒呼叫）。 */
    private class HeadlessLifecycle : LifecycleOwner {
        private val reg = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = reg
        fun resume() { reg.currentState = Lifecycle.State.RESUMED }
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED }
    }
}
