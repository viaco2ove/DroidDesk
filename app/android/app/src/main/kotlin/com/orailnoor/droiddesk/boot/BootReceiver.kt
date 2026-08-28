package com.orailnoor.droiddesk.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.orailnoor.droiddesk.service.RunCommandService

/**
 * 开机自启广播接收器
 * 设备启动后：
 * 1. 启动 DroidDeskService（保持后台活跃）
 * 2. 执行用户配置的启动脚本（如果有）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DroidDeskBoot"
        const val BOOT_SCRIPT_PATH = "/data/data/com.orailnoor.droiddesk/files/.droiddesk/boot/start.sh"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 启动 DroidDesk 前台服务
                val serviceIntent = Intent(context, com.orailnoor.droiddesk.service.DroidDeskService::class.java)
                context.startForegroundService(serviceIntent)

                // 如果有启动脚本，通过 RUN_COMMAND 执行
                val scriptFile = java.io.File(BOOT_SCRIPT_PATH)
                if (scriptFile.exists()) {
                    Log.i(TAG, "Found boot script, executing: $BOOT_SCRIPT_PATH")
                    val runIntent = Intent(context, RunCommandService::class.java)
                    runIntent.action = RunCommandService.ACTION_RUN_COMMAND
                    runIntent.putExtra(RunCommandService.EXTRA_COMMAND_PATH, BOOT_SCRIPT_PATH)
                    runIntent.putExtra(RunCommandService.EXTRA_BACKGROUND, true)
                    context.startService(runIntent)
                }
            }
        }
    }
}