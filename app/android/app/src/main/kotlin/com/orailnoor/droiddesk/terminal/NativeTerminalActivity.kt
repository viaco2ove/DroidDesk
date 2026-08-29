package com.orailnoor.droiddesk.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 真正的终端 Activity - 使用 termux-app 的 TerminalView + TerminalSession。
 *
 * Session 保存在 companion object 中，activity 销毁时只 detach view，session 进程不被 kill。
 * 下次启动会重新 attach 到现有 session（继续之前的 shell），没有现存 session 才创建。
 */
class NativeTerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    companion object {
        private const val TAG = "NativeTerminal"
        // 默认字体大小 (基于屏幕密度)
        private const val DEFAULT_FONT_SIZE = 12

        // 保存活跃 session，避免 activity 销毁时 kill 进程
        // 用 IdentityHashMap 防止 TerminalSession.equals/hashCode 出错
        private val activeSessions = java.util.IdentityHashMap<TerminalSession, SessionInfo>()

        private data class SessionInfo(val envTag: String, val session: TerminalSession)
    }

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private val chroot by lazy { ChrootRuntime(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var showKeyboardRunnable: Runnable? = null

    // 记录当前 attach 的 session env tag (ubuntu / 普通)
    private var currentEnvTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate start")
        super.onCreate(savedInstanceState)
        try {
            val density = resources.displayMetrics.density
            var fontSize = Math.round(DEFAULT_FONT_SIZE * density)
            if (fontSize % 2 == 1) fontSize--

            terminalView = TerminalView(this, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTextSize(fontSize)
                setTypeface(Typeface.MONOSPACE)
                setBackgroundColor(0xFF0A0A0A.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
            }
            Log.i(TAG, "TerminalView created")

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(
                    terminalView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                )
            }

            val extraKeys = TerminalExtraKeysView(this).apply {
                terminalView = this@NativeTerminalActivity.terminalView
            }
            layout.addView(extraKeys)

            // 边缘右滑返回容器：从屏幕左边缘向右滑动即可返回（会话保留在后台）
            val root = EdgeSwipeBackLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(layout)
                onSwipeBack = {
                    Log.i(TAG, "Edge swipe back -> finish (session kept alive)")
                    finish()
                }
            }
            setContentView(root)
            Log.i(TAG, "setContentView done")

            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            // 复用现有 session（如果有匹配 envTag 的）
            currentEnvTag = intent?.getStringExtra("env") ?: ""
            terminalSession = reuseOrCreateSession(currentEnvTag)
            terminalView.attachSession(terminalSession)
            Log.i(TAG, "Session ready (env=$currentEnvTag)")

            terminalView.setTerminalViewClient(this)

            terminalView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) showSoftKeyboardInternal()
            }

            terminalView.postDelayed({
                terminalView.requestFocus()
                showSoftKeyboardInternal()
            }, 300)
            Log.i(TAG, "onCreate finished")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate CRASHED", e)
            Toast.makeText(this, "Terminal init failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun reuseOrCreateSession(envTag: String): TerminalSession {
        // 先清理已失效（进程已退出）的 session，避免残留
        val dead = mutableListOf<TerminalSession>()
        for ((sess, info) in activeSessions) {
            if (!info.session.isRunning) dead.add(sess)
        }
        for (sess in dead) {
            Log.i(TAG, "Removing dead session (env=${activeSessions[sess]?.envTag})")
            activeSessions.remove(sess)
        }

        // 查找匹配的现有 session
        for (info in activeSessions.values) {
            if (info.envTag == envTag && info.session.isRunning && info.session.getShellPidPublic() > 0) {
                Log.i(TAG, "Reusing existing session for env=$envTag (pid=${info.session.getShellPidPublic()})")
                // 切回这个 session：临时把 client 换成我们
                info.session.setSessionClientPublic(this)
                return info.session
            }
        }
        // 没有就创建
        Log.i(TAG, "Creating new session for env=$envTag")
        return createNewSession(envTag)
    }

    private fun createNewSession(envTag: String): TerminalSession {
        val shellPath: String
        val cwd: String
        var shellArgs: Array<String?> = arrayOfNulls(0)

        when {
            envTag == "ubuntu" && chroot.hasRoot() && chroot.isRootfsReady() -> {
                shellPath = "chroot ${chroot.getRootfsPath()} /bin/bash --login"
                cwd = "/"
            }
            envTag == "ubuntu" -> {
                val cmdFile = java.io.File(filesDir, "bin/ubuntu-shell.cmd")
                if (cmdFile.exists()) {
                    val content = cmdFile.readText().trim()
                    shellPath = "/system/bin/sh"
                    shellArgs = arrayOf("sh", "-c", content)
                    cwd = "/"
                } else {
                    Log.w(TAG, "ubuntu-shell.cmd not found, fallback to /system/bin/sh")
                    shellPath = "/system/bin/sh"
                    cwd = "/"
                }
            }
            chroot.hasRoot() && chroot.isRootfsReady() -> {
                shellPath = "chroot ${chroot.getRootfsPath()} /bin/bash --login"
                cwd = "/"
            }
            chroot.hasRoot() -> {
                shellPath = "/system/bin/sh"
                cwd = "/"
            }
            else -> {
                shellPath = "/system/bin/sh"
                cwd = "/"
            }
        }

        Log.i(TAG, "Starting new session with shell: $shellPath args=${shellArgs.toList()}")

        val session = TerminalSession(
            shellPath,
            cwd,
            shellArgs,
            getEnvironment(),
            200,
            this
        )
        activeSessions[session] = SessionInfo(envTag, session)
        return session
    }

    private fun getEnvironment(): Array<String> {
        val env = mutableListOf(
            "TERM=xterm-256color",
            "HOME=/data/data/$packageName/files/home",
            "PATH=/system/bin:/system/xbin",
            "LANG=en_US.UTF-8"
        )
        if (chroot.hasRoot() && chroot.isRootfsReady()) {
            env.add("USER=root")
            env.add("LOGNAME=root")
            env.add("DISPLAY=:0")
        }
        return env.toTypedArray()
    }

        override fun onBackPressed() {
        Log.i(TAG, "onBackPressed -> finish()")
        finish()
        super.onBackPressed()
    }

override fun onResume() {
        super.onResume()
        terminalView.requestFocus()
        showSoftKeyboardInternal()
    }

    override fun onPause() {
        super.onPause()
        showKeyboardRunnable?.let { handler.removeCallbacks(it) }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy (will detach session, NOT kill it)")
        showKeyboardRunnable?.let { handler.removeCallbacks(it) }
        // detach view 但不 finish session，让进程继续在后台运行
        terminalView.attachSession(null)
        // 把 client 切换到 dummy，防止回调到已 destroy 的 activity
        terminalSession?.let { sess ->
            sess.setSessionClientPublic(NoOpSessionClient)
        }
        super.onDestroy()
    }

    // ── 公共日志方法 ──

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "StackTrace", e) }

    override fun onTextChanged(@NonNull changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(@NonNull changedSession: TerminalSession) {
        Log.d(TAG, "Title: ${changedSession.title}")
    }

    override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
        Log.i(TAG, "Session finished (env=$currentEnvTag)")
        // shell 自己退出了：从 activeSessions 移除
        activeSessions.remove(finishedSession)
        if (!isFinishing) {
            Toast.makeText(this, "Session ended", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            terminalSession?.write(text)
        }
    }

    override fun onBell(@NonNull session: TerminalSession) {}
    override fun onColorsChanged(@NonNull session: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) {
        Log.d(TAG, "Shell PID: $pid")
    }
    override fun getTerminalCursorStyle(): Int = 4

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(@NonNull e: MotionEvent) { showSoftKeyboardInternal() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, @NonNull e: KeyEvent, @NonNull session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, @NonNull e: KeyEvent): Boolean = false
    override fun onLongPress(@NonNull e: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, @NonNull session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    private fun showSoftKeyboardInternal() {
        if (showKeyboardRunnable != null) {
            handler.removeCallbacks(showKeyboardRunnable!!)
        }
        showKeyboardRunnable = Runnable {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        handler.postDelayed(showKeyboardRunnable!!, 100)
    }
}

/**
 * 边缘右滑返回容器。
 *
 * 由于 TerminalView 会消费所有触摸事件，这里在 dispatchTouchEvent 层面
 * 优先拦截：从屏幕左边缘按下并向右水平滑动即视为"返回"手势，
 * 只结束 Activity（不销毁后台 shell 会话）。
 * 非边缘触摸仍原样下发给终端，不影响正常输入与滚动。
 */
private class EdgeSwipeBackLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 触发返回的回调 */
    var onSwipeBack: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeWidthPx = (36 * density).toInt()
    private val minSwipePx = (120 * density).toInt()

    private var trackingPointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var gestureDone = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDone = false
                startX = ev.x
                startY = ev.y
                // 仅左边缘按下且向右滑动视为返回手势
                if (ev.x <= edgeWidthPx) {
                    trackingPointerId = ev.getPointerId(0)
                } else {
                    trackingPointerId = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指触摸，取消手势追踪
                trackingPointerId = -1
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureDone) return true // 手势已触发，吞掉后续事件
                if (trackingPointerId >= 0) {
                    val idx = ev.findPointerIndex(trackingPointerId)
                    if (idx >= 0) {
                        val dx = ev.getX(idx) - startX
                        val dy = ev.getY(idx) - startY
                        // 水平右滑位移明显大于竖直位移，且超过最小距离
                        if (dx > touchSlopPx &&
                            dx > Math.abs(dy) * 2f &&
                            dx >= minSwipePx
                        ) {
                            gestureDone = true
                            trackingPointerId = -1
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            // 给子视图发送 CANCEL，让 TerminalView 干净地结束本次触摸
                            val cancel = MotionEvent.obtain(
                                ev.downTime, ev.eventTime,
                                MotionEvent.ACTION_CANCEL, ev.x, ev.y, 0
                            )
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            onSwipeBack?.invoke()
                            return true
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureDone) {
                    trackingPointerId = -1
                    return true // 吞掉本次手势的收尾事件
                }
                trackingPointerId = -1
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Dummy client 用于在 activity 销毁后接管 session 回调。
 * 防止回调到已 destroy 的 activity 引发崩溃。
 */
private object NoOpSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = 4
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}