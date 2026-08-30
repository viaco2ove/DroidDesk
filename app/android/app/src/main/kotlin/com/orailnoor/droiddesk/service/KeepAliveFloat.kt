package com.orailnoor.droiddesk.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 悬浮窗保活 - 在 ColorOS / MIUI 等国产 ROM 上绕过"智能冻结"。
 *
 * 这些 ROM 在用户切到桌面后会冻结不在前台的 app 进程（包括前台服务），
 * 即使服务声明了 foregroundServiceType="dataSync"。但如果 app 显示了
 * SYSTEM_ALERT_WINDOW（悬浮窗），系统会认为 app 处于"用户可见"状态，
 * 通常不会冻结，从而保住底层的 proot / sshd 子进程。
 *
 * 使用方法：
 *   KeepAliveFloat.show(context)  - 显示一个可拖动的小圆点
 *   KeepAliveFloat.dismiss()      - 隐藏
 *
 * 悬浮窗本身不处理业务逻辑，仅 1x1 像素级别的 View，绘制开销可忽略。
 */
object KeepAliveFloat {
    private const val TAG = "KeepAliveFloat"
    private var floatView: View? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context) {
        if (floatView != null) return  // 已显示
        Log.d(TAG, "show() called, entering try block")
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = FrameLayout(context).apply {
                setBackgroundColor(0x55E95420.toInt())  // 半透明 Ubuntu 橙色，提示用户是 DroidDesk
                // 圆形 mask (API 21+)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
            }

            val label = TextView(context).apply {
                text = "🐧"  // Ubuntu logo
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            container.addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val sizeDp = 36
            val sizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, sizeDp.toFloat(),
                context.resources.displayMetrics
            ).toInt()

            val params = WindowManager.LayoutParams(
                sizePx, sizePx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 200

            // 拖动支持
            var initialX = 0
            var initialY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchStartX).toInt()
                        params.y = initialY + (event.rawY - touchStartY).toInt()
                        windowManager?.updateViewLayout(container, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 点击：回到 DroidDesk
                        val moved = kotlin.math.abs(event.rawX - touchStartX) > 10 ||
                                kotlin.math.abs(event.rawY - touchStartY) > 10
                        if (!moved) {
                            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            launch?.let { context.startActivity(it) }
                        }
                        true
                    }
                    else -> false
                }
            }

            Log.d(TAG, "About to call windowManager.addView")
            windowManager?.addView(container, params)
            floatView = container
            Log.i(TAG, "Keep-alive float shown successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show keep-alive float: ${e.message}", e)
            floatView = null
        }
    }

    fun dismiss() {
        val view = floatView ?: return
        try {
            windowManager?.removeView(view)
            Log.i(TAG, "Keep-alive float dismissed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss float: ${e.message}")
        }
        floatView = null
        windowManager = null
    }

    fun isShowing(): Boolean = floatView != null
}