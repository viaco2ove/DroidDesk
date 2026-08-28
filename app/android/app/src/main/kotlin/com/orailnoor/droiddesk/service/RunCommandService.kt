package com.orailnoor.droiddesk.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import java.io.File

/**
 * 自定义 RUN_COMMAND 服务
 * 允许第三方应用（TermuxPilot）调用 DroidDesk 执行 shell 命令
 *
 * Intent 协议:
 *   Action: com.orailnoor.droiddesk.RUN_COMMAND
 *   Extras:
 *     - com.orailnoor.droiddesk.RUN_COMMAND_PATH: 命令/脚本绝对路径
 *     - com.orailnoor.droiddesk.RUN_COMMAND_ARGUMENTS: String[] 参数
 *     - com.orailnoor.droiddesk.RUN_COMMAND_BACKGROUND: boolean 后台运行
 *     - com.orailnoor.droiddesk.RUN_COMMAND_WORKDIR: String 工作目录
 */
class RunCommandService : Service() {

    companion object {
        private const val TAG = "DroidDeskRunCommand"

        const val ACTION_RUN_COMMAND = "com.orailnoor.droiddesk.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.orailnoor.droiddesk.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.orailnoor.droiddesk.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_BACKGROUND = "com.orailnoor.droiddesk.RUN_COMMAND_BACKGROUND"
        const val EXTRA_WORKDIR = "com.orailnoor.droiddesk.RUN_COMMAND_WORKDIR"
        const val EXTRA_STDIN = "com.orailnoor.droiddesk.RUN_COMMAND_STDIN"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_RUN_COMMAND) {
            Log.w(TAG, "Ignoring unknown action: $action")
            return START_NOT_STICKY
        }

        val commandPath = intent.getStringExtra(EXTRA_COMMAND_PATH)
        val arguments = intent.getStringArrayExtra(EXTRA_ARGUMENTS)
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, true)
        val workdir = intent.getStringExtra(EXTRA_WORKDIR)
        val stdin = intent.getStringExtra(EXTRA_STDIN)

        if (commandPath.isNullOrEmpty()) {
            Log.e(TAG, "Missing command path")
            return START_NOT_STICKY
        }

        Log.i(TAG, "Executing: $commandPath ${arguments?.joinToString(" ") ?: ""}")

        Thread {
            executeCommand(commandPath, arguments, workdir, stdin)
        }.start()

        return START_NOT_STICKY
    }

    /**
     * 执行命令
     * 优先使用 chroot 环境，如果没安装 chroot 则用 root shell
     */
    private fun executeCommand(
        commandPath: String,
        arguments: Array<String>?,
        workdir: String?,
        stdin: String?
    ) {
        try {
            // 构建完整命令
            val cmdBuilder = StringBuilder()
            cmdBuilder.append(commandPath)
            arguments?.forEach { arg ->
                cmdBuilder.append(" '").append(arg.replace("'", "'\\''")).append("'")
            }

            val fullCommand = cmdBuilder.toString()
            Log.i(TAG, "Running: $fullCommand")

            // 尝试用 chroot 执行（如果有 rootfs）
            val chroot = ChrootRuntime(this)
            if (chroot.isRootfsReady()) {
                Log.i(TAG, "Using chroot environment")
                val output = chroot.executeCommand(fullCommand)
                Log.i(TAG, "Output: $output")
                return
            }

            // 备选：root shell
            val rootShell = RootShell(this)
            if (rootShell.hasRoot()) {
                Log.i(TAG, "Using root shell")
                val cmd = if (workdir != null) {
                    "cd '$workdir' && $fullCommand"
                } else {
                    fullCommand
                }
                val output = rootShell.exec(cmd)
                Log.i(TAG, "Output: $output")
                return
            }

            // 备选：普通进程执行
            Log.w(TAG, "No root/chroot, falling back to ProcessBuilder")
            val pb = if (workdir != null) {
                ProcessBuilder(commandPath, *(arguments ?: emptyArray()))
                    .directory(File(workdir))
            } else {
                ProcessBuilder(commandPath, *(arguments ?: emptyArray()))
            }
            if (stdin != null) {
                pb.redirectInput(ProcessBuilder.Redirect.PIPE)
            }
            val process = pb.start()
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { it.write(stdin) }
            }
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.i(TAG, "Output: $output")
            Log.i(TAG, "Exit code: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
        }
    }
}