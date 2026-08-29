package com.orailnoor.droiddesk.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * TerminalView: 自绘的终端视图，处理键盘输入、手势、文本选择
 * TerminalSession: 管理 PTY 子进程
 *
 * 这是和 Termux app 一样的终端实现。
 */
class NativeTerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    companion object {
        private const val TAG = "NativeTerminal"
        // 默认字体大小 (sp)，与 termux-app 保持一致
        private const val DEFAULT_FONT_SIZE = 12
    }

    private lateinit var terminalView: TerminalView
    private var terminalSession: TerminalSession? = null
    private val chroot by lazy { ChrootRuntime(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var showKeyboardRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate start")
        super.onCreate(savedInstanceState)
        try {
            // 获取默认字体大小 (基于屏幕密度)
            val density = resources.displayMetrics.density
            var fontSize = Math.round(DEFAULT_FONT_SIZE * density)
            if (fontSize % 2 == 1) fontSize--  // 确保偶数

            terminalView = TerminalView(this, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 必须先 setTextSize 初始化 mRenderer，再 setTypeface
                setTextSize(fontSize)
                setTypeface(Typeface.MONOSPACE)
                setBackgroundColor(0xFF0A0A0A.toInt())
                // 关键: 让视图可获取焦点并可触摸
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

            // Extra keys 工具栏（在底部）
            val extraKeys = TerminalExtraKeysView(this).apply {
                terminalView = this@NativeTerminalActivity.terminalView
            }
            layout.addView(extraKeys)

            setContentView(layout)
            Log.i(TAG, "setContentView done")

            // 设置键盘弹出时调整布局
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            createTerminalSession()
            Log.i(TAG, "createTerminalSession done")

            terminalView.setTerminalViewClient(this)

            // 设置焦点变化监听器
            terminalView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showSoftKeyboardInternal()
                }
            }

            // 延迟显示键盘，等待视图准备好
            terminalView.postDelayed({
                terminalView.requestFocus()
                showSoftKeyboardInternal()
            }, 300)
            Log.i(TAG, "onCreate finished")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate CRASHED", e)
            android.widget.Toast.makeText(this, "Terminal init failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

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

    private fun createTerminalSession() {
        val shellPath: String
        val cwd: String
        var shellArgs: Array<String?> = arrayOfNulls(0)

        // 当 Intent 带 "env"=ubuntu 时强制进 Ubuntu 终端
        val envOverride = intent?.getStringExtra("env")

        when {
            envOverride == "ubuntu" && chroot.hasRoot() && chroot.isRootfsReady() -> {
                shellPath = "chroot ${chroot.getRootfsPath()} /bin/bash --login"
                cwd = "/"
            }
            envOverride == "ubuntu" -> {
                // 使用 sh -c 执行从文件读取的命令，绕过文件执行位问题
                val cmdFile = java.io.File(filesDir, "bin/ubuntu-shell.cmd")
                if (cmdFile.exists()) {
                    val content = cmdFile.readText().trim()
                    shellPath = "sh"
                    // argv[0]="sh" 必须存在，否则 execvp 后某些 shell 立即退出 (exit 127)
                    shellArgs = arrayOf("sh", "-c", content)
                    cwd = "/"
                    Log.i(TAG, "Using inline command (argv[0]=sh): ${content.take(80)}...")
                } else {
                    Log.w(TAG, "ubuntu-shell.cmd not found at ${cmdFile.absolutePath}")
                    shellPath = "sh"
                    shellArgs = arrayOf("sh")
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

        Log.i(TAG, "Starting session with shell: $shellPath args=${shellArgs.toList()}")

        terminalSession = TerminalSession(
            shellPath,
            cwd,
            shellArgs,
            getEnvironment(),
            200,  // transcriptRows
            this
        )

        terminalView.attachSession(terminalSession)
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
        showKeyboardRunnable?.let { handler.removeCallbacks(it) }
        terminalSession?.finishIfRunning()
        super.onDestroy()
    }

    // ── 公共日志方法（TerminalSessionClient 和 TerminalViewClient 共用）──

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "StackTrace", e)
    }

    // ── TerminalSessionClient ──

    override fun onTextChanged(@NonNull changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(@NonNull changedSession: TerminalSession) {
        Log.d(TAG, "Title: ${changedSession.title}")
    }

    override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
        val exitStatus = finishedSession.shellExitStatus
        Log.i(TAG, "Session finished, exit status: $exitStatus")
        runOnUiThread { finish() }
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

    override fun onColorsChanged(@NonNull session: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) {
        Log.d(TAG, "Shell PID: $pid")
    }

    override fun getTerminalCursorStyle(): Int {
        return 4 // TERMINAL_CURSOR_STYLE_BLOCK
    }

    // ── TerminalViewClient ──

    override fun onScale(scale: Float): Float {
        return scale
    }

    override fun onSingleTapUp(@NonNull e: MotionEvent) {
        terminalView.requestFocus()
        showSoftKeyboardInternal()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return false
    }

    override fun shouldEnforceCharBasedInput(): Boolean {
        return false
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return true
    }

    override fun isTerminalViewSelected(): Boolean {
        return true
    }

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, @NonNull e: KeyEvent, @NonNull session: TerminalSession): Boolean {
        return false
    }

    override fun onKeyUp(keyCode: Int, @NonNull e: KeyEvent): Boolean {
        return false
    }

    override fun onLongPress(@NonNull e: MotionEvent): Boolean {
        return false
    }

    override fun readControlKey(): Boolean {
        return false
    }

    override fun readAltKey(): Boolean {
        return false
    }

    override fun readShiftKey(): Boolean {
        return false
    }

    override fun readFnKey(): Boolean {
        return false
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, @NonNull session: TerminalSession): Boolean {
        return false
    }

    override fun onEmulatorSet() {}
}
