package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

class TapNumberOverlay(
    private val context: Context,
    private val dump: () -> List<ScreenNode>
) {
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val container: FrameLayout = FrameLayout(context)
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
        if (!Settings.canDrawOverlays(context)) return false
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
            } catch (_: Throwable) {
                return false
            }
            isShowing = true
        }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 750L)
        return true
    }

    fun refresh(numbered: List<Pair<Int, ScreenNode>>) {
        if (!isShowing) return
        container.removeAllViews()
        for ((number, node) in numbered.take(50)) {
            container.addView(badgeView(number), layoutParamsFor(node))
        }
    }

    fun hide() {
        handler.removeCallbacks(refreshRunnable)
        if (isShowing) {
            try {
                windowManager.removeView(container)
            } catch (_: Throwable) {}
            isShowing = false
        }
        container.removeAllViews()
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
