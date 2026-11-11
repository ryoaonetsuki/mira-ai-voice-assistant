package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

sealed class ShowOverlayResult {
    object Success : ShowOverlayResult()
    object PermissionMissing : ShowOverlayResult()
    data class WindowManagerFailed(val error: Throwable?) : ShowOverlayResult()
}

class TapNumberOverlay(
    private val context: Context,
    private val dump: () -> List<ScreenNode>
) {
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val container: FrameLayout = FrameLayout(context)
    @Volatile
    private var isShowing: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())

    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            try {
                val nodes = dump()
                val numbered = UiNodeMatcher.tappableNumbered(nodes)
                refresh(numbered)
            } catch (_: Throwable) {}
            if (isShowing) {
                handler.postDelayed(this, 750L)
            }
        }
    }

    private fun badgeView(number: Int): TextView {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            28f,
            context.resources.displayMetrics
        ).roundToInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#0E7490"))
            setSize(sizePx, sizePx)
        }

        return TextView(context).apply {
            text = number.toString()
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
        }
    }

    private fun layoutParamsFor(node: ScreenNode): FrameLayout.LayoutParams {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            28f,
            context.resources.displayMetrics
        ).roundToInt()
        return FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = node.left
            topMargin = node.top
        }
    }

    fun show(numbered: List<Pair<Int, ScreenNode>>): Boolean {
        return showDetailed(numbered) is ShowOverlayResult.Success
    }

    fun showDetailed(numbered: List<Pair<Int, ScreenNode>>): ShowOverlayResult {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show tap numbers: 'Display over other apps' (SYSTEM_ALERT_WINDOW) permission not granted")
            return ShowOverlayResult.PermissionMissing
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            val resultHolder = java.util.concurrent.atomic.AtomicReference<ShowOverlayResult>(ShowOverlayResult.WindowManagerFailed(null))
            handler.post {
                try {
                    resultHolder.set(showInternal(numbered))
                } finally {
                    latch.countDown()
                }
            }
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Log.w(TAG, "showDetailed interrupted while awaiting main thread dispatch")
            }
            return resultHolder.get()
        } else {
            return showInternal(numbered)
        }
    }

    private fun showInternal(numbered: List<Pair<Int, ScreenNode>>): ShowOverlayResult {
        container.removeAllViews()
        for ((number, node) in numbered.take(50)) {
            container.addView(badgeView(number), layoutParamsFor(node))
        }
        if (!isShowing) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(container, params)
            } catch (e: Throwable) {
                Log.w(TAG, "windowManager.addView failed: ${e.message}", e)
                return ShowOverlayResult.WindowManagerFailed(e)
            }
            isShowing = true
            Log.i(TAG, "Tap numbers overlay shown with ${numbered.size} badges")
        }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 750L)
        return ShowOverlayResult.Success
    }

    fun refresh(numbered: List<Pair<Int, ScreenNode>>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refreshInternal(numbered) }
        } else {
            refreshInternal(numbered)
        }
    }

    private fun refreshInternal(numbered: List<Pair<Int, ScreenNode>>) {
        if (!isShowing) return
        container.removeAllViews()
        for ((number, node) in numbered.take(50)) {
            container.addView(badgeView(number), layoutParamsFor(node))
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hideInternal() }
        } else {
            hideInternal()
        }
    }

    private fun hideInternal() {
        handler.removeCallbacks(refreshRunnable)
        if (isShowing) {
            try {
                windowManager.removeView(container)
            } catch (e: Throwable) {
                Log.w(TAG, "windowManager.removeView failed: ${e.message}")
            }
            isShowing = false
            Log.i(TAG, "Tap numbers overlay hidden")
        }
        container.removeAllViews()
    }

    companion object {
        private const val TAG = "TapNumberOverlay"
    }
}

fun formatNumberedList(numbered: List<Pair<Int, ScreenNode>>): String {
    if (numbered.isEmpty()) return ""
    return numbered.joinToString("\n") { (number, node) ->
        val label = node.text.ifBlank {
            node.contentDescription.ifBlank {
                node.className.substringAfterLast('.')
            }
        }
        "$number: $label"
    }
}
