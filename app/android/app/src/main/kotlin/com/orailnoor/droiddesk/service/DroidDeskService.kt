package com.orailnoor.droiddesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.LinuxRuntime

/**
 * Foreground service that keeps the Linux runtime alive.
 *
 * Android aggressively kills background processes (especially Android 12+'s
 * Phantom Process Killer). This service ensures our native Termux/chroot session, desktop
 * environment, and Wayland compositor survive when the user switches apps.
 */
class DroidDeskService : Service() {

    companion object {
        private const val TAG = "DroidDeskService"
        const val CHANNEL_ID = "droiddesk_service"
        const val NOTIFICATION_ID = 1001
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        // HandlerThread 的消息队列在 service 进程内，service 被杀时 Looper 会连带停止
        // 线程优先级默认 THREAD_PRIORITY_BACKGROUND，比 kotlin.concurrent.thread 高
        workerThread = HandlerThread("DroidDeskWorker", Thread.MIN_PRIORITY).apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Linux desktop is running")

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        // 守护模式下：确保 Ubuntu 会话已启动 + sshd 在跑
        val sp = getSharedPreferences("ubuntu_console", MODE_PRIVATE)

        // ColorOS / MIUI 等国产 ROM 智能冻结：app 退到后台后即使有前台 service 也会被冻结，
        // 导致 proot/sshd 子进程不响应 I/O。SYSTEM_ALERT_WINDOW 让系统认为 app 处于"用户可见"状态，
        // 通常不会冻结，从而保住底层进程。
        val keepAliveEnabled = sp.getBoolean("keepAliveFloat", true)
        Log.i(TAG, "keepAliveFloat check: enabled=$keepAliveEnabled, canDrawOverlays=${android.provider.Settings.canDrawOverlays(this)}")
        if (keepAliveEnabled) {
            try {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    Log.i(TAG, "Attempting to show keep-alive float...")
                    KeepAliveFloat.show(this)
                    Log.i(TAG, "KeepAliveFloat.show() returned, isShowing=${KeepAliveFloat.isShowing()}")
                } else {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; skip keep-alive float")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Keep-alive float init failed: ${e.message}", e)
            }
        }
        if (sp.getBoolean("daemon", false)) {
            workerHandler?.post {
                try {
                    val runtime = LinuxRuntime.getInstance(this)
                    if (!runtime.isUbuntuProotRunning()) {
                        Log.i(TAG, "Daemon active, restoring Ubuntu session...")
                        restoreUbuntuSession(runtime)
                    }
                    // sshWithUbuntu=true 且 supervisor 未开启时，确保 sshd 也在跑
                    // （supervisor 开启时由 supervisor 内部的 [program:sshd] 管理 sshd）
                    if (sp.getBoolean("sshWithUbuntu", false) &&
                        !sp.getBoolean("supervisorWithUbuntu", false) &&
                        runtime.isUbuntuSshInstalled() &&
                        !runtime.isUbuntuSshdRunning()) {
                        Log.i(TAG, "Daemon active, starting sshd...")
                        runtime.startUbuntuSshd()
                    }
                    // pm2WithUbuntu=true 时确保 pm2 守护进程在跑
                    // supervisor 开启时由 supervisor 的 [program:pm2] 管理；否则由 service 通过 session 容器拉起
                    if (sp.getBoolean("pm2WithUbuntu", false)) {
                        if (sp.getBoolean("supervisorWithUbuntu", false)) {
                            Log.i(TAG, "Daemon active, pm2 managed by supervisor")
                        } else if (!runtime.isUbuntuPm2Running()) {
                            Log.i(TAG, "Daemon active, resurrecting pm2 in session...")
                            runtime.resurrectPm2InSession()
                        } else {
                            Log.i(TAG, "Daemon active, pm2 daemon already alive")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore Ubuntu session: ${e.message}")
                }
            }
        }

        // pm2 健康监控：每 60 秒检查 pm2 daemon，死了就重启
        schedulePm2Watchdog(sp)
        // supervisor 健康监控：每 60 秒检查 supervisord，死了就重启
        scheduleSupervisorWatchdog(sp)

        // supervisor 开启时优先启动（supervisor 内部管 sshd/nginx，所以传统 sshd 启动可以省略）
        if (sp.getBoolean("supervisorWithUbuntu", false)) {
            workerHandler?.post {
                try {
                    val runtime = LinuxRuntime.getInstance(this)
                    if (!runtime.isUbuntuSupervisorRunning()) {
                        Log.i(TAG, "Supervisor enabled, starting...")
                        runtime.startUbuntuSupervisor()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start supervisor: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun restoreUbuntuSession(runtime: LinuxRuntime) {
        if (!runtime.isBootstrapped()) return
        val filesDir = filesDir
        val homeDirPath = "${filesDir.absolutePath}/home"
        val tmpDirPath = "${filesDir.absolutePath}/tmp"
        val prefixPath = runtime.prefixPath

        java.io.File(filesDir, "bin").mkdirs()
        // pm2 daemon 必须和 SSH session 共用同一个 proot 容器（命名空间隔离导致独立容器无法互通）
        // 在 session 容器启动时一次性 pm2 kill + pm2 resurrect，确保全局只有一个 daemon，
        // .bashrc 里不再 resurrect（避免 SSH 进会话时再开一个 daemon）
        val wantPm2 = getSharedPreferences("ubuntu_console", MODE_PRIVATE)
            .getBoolean("pm2WithUbuntu", false)
        // session 容器内部命令：先清掉所有老 daemon（pkill + pm2 kill），再 resurrect
        // 用 nohup + setsid 让 daemon 与 session shell 进程解绑，session 死了 daemon 也活着
        val pm2Setup = if (wantPm2) {
            "pkill -9 -f 'pm2 God' 2>/dev/null; pkill -9 -f 'PM2 v' 2>/dev/null; " +
            "pm2 kill 2>/dev/null; sleep 1; " +
            "nohup pm2 resurrect >/dev/null 2>&1 </dev/null & "
        } else ""
        val innerCmd = "${pm2Setup}exec /bin/bash -i -l"
        val cmdFile = java.io.File(filesDir, "bin/ubuntu-shell.cmd")
        cmdFile.writeText(
            "export PREFIX=\"$prefixPath\"; " +
            "export TMPDIR=\"$tmpDirPath\"; " +
            "export HOME=\"$homeDirPath\"; " +
            "export TERMUX_APP__PACKAGE_NAME=\"$packageName\"; " +
            "export TERMUX_APP__DATA_DIR=\"${filesDir.absolutePath}\"; " +
            "export TERMUX__PREFIX=\"$prefixPath\"; " +
            "export TERMUX__HOME=\"$homeDirPath\"; " +
            "export PATH=\"$prefixPath/bin:/system/bin\"; " +
            "export PYTHONHOME=\"$prefixPath\"; " +
            "export LD_LIBRARY_PATH=\"$prefixPath/lib\"; " +
            "$prefixPath/bin/proot-distro login ubuntu " +
            "--bind \"$tmpDirPath:/tmp\" " +
            "--env PROOT_TMP_DIR=\"$tmpDirPath/proot\" " +
            "--env PROOT_LOADER=\"$prefixPath/libexec/proot/loader\" " +
            "--env PROOT_LOADER_32=\"$prefixPath/libexec/proot/loader32\" " +
            "-- sh -c '$innerCmd'"
        )
        Log.i(TAG, "Daemon: Ubuntu session command file written (pm2=$wantPm2)")

        // 启动会话进程（不等待输出）
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "export PREFIX=\"$prefixPath\"; export TMPDIR=\"$tmpDirPath\"; " +
            "export HOME=\"$homeDirPath\"; export TERMUX_APP__PACKAGE_NAME=\"$packageName\"; " +
            "export TERMUX_APP__DATA_DIR=\"${filesDir.absolutePath}\"; " +
            "export TERMUX__PREFIX=\"$prefixPath\"; export TERMUX__HOME=\"$homeDirPath\"; " +
            "export PATH=\"$prefixPath/bin:/system/bin\"; " +
            "export PYTHONHOME=\"$prefixPath\"; " +
            "export LD_LIBRARY_PATH=\"$prefixPath/lib\"; " +
            "$prefixPath/bin/proot-distro login ubuntu " +
            "--bind \"$tmpDirPath:/tmp\" " +
            "--env PROOT_TMP_DIR=\"$tmpDirPath/proot\" " +
            "--env PROOT_LOADER=\"$prefixPath/libexec/proot/loader\" " +
            "--env PROOT_LOADER_32=\"$prefixPath/libexec/proot/loader32\" " +
            "-- sh -c '$innerCmd'"
        )
            .redirectErrorStream(true)
            .start()
        Log.i(TAG, "Daemon: Ubuntu session started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // pm2 健康监控：定时检查 + 自动重启（仅当 supervisor 未启用时，supervisor 模式由 supervisor 自管 pm2）
    private val pm2WatchdogRunnable = object : Runnable {
        override fun run() {
            val sp = getSharedPreferences("ubuntu_console", MODE_PRIVATE)
            // 仅在用户开启 pm2WithUbuntu 且 supervisor 未开启时才监控（supervisor 模式下 pm2 由 supervisor 管）
            if (sp.getBoolean("pm2WithUbuntu", false) &&
                !sp.getBoolean("supervisorWithUbuntu", false)) {
                try {
                    val runtime = LinuxRuntime.getInstance(applicationContext)
                    if (!runtime.isUbuntuPm2Running()) {
                        Log.w(TAG, "pm2 watchdog: daemon dead, resurrecting in session...")
                        runtime.resurrectPm2InSession()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pm2 watchdog failed: ${e.message}")
                }
            }
            // 60 秒后再调度
            workerHandler?.postDelayed(this, 60_000)
        }
    }

    private fun schedulePm2Watchdog(sp: android.content.SharedPreferences) {
        if (sp.getBoolean("pm2WithUbuntu", false)) {
            workerHandler?.removeCallbacks(pm2WatchdogRunnable)
            workerHandler?.postDelayed(pm2WatchdogRunnable, 60_000)
            Log.i(TAG, "pm2 watchdog scheduled")
        }
    }

    // supervisor 健康监控
    private val supervisorWatchdogRunnable = object : Runnable {
        override fun run() {
            val sp = getSharedPreferences("ubuntu_console", MODE_PRIVATE)
            if (sp.getBoolean("supervisorWithUbuntu", false)) {
                try {
                    val runtime = LinuxRuntime.getInstance(applicationContext)
                    if (!runtime.isUbuntuSupervisorRunning()) {
                        Log.w(TAG, "supervisor watchdog: daemon dead, restarting...")
                        runtime.startUbuntuSupervisor()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "supervisor watchdog failed: ${e.message}")
                }
            }
            workerHandler?.postDelayed(this, 60_000)
        }
    }

    private fun scheduleSupervisorWatchdog(sp: android.content.SharedPreferences) {
        if (sp.getBoolean("supervisorWithUbuntu", false)) {
            workerHandler?.removeCallbacks(supervisorWatchdogRunnable)
            workerHandler?.postDelayed(supervisorWatchdogRunnable, 60_000)
            Log.i(TAG, "supervisor watchdog scheduled")
        }
    }

    override fun onDestroy() {
        KeepAliveFloat.dismiss()
        releaseWakeLock()
        workerHandler?.removeCallbacks(pm2WatchdogRunnable)
        workerHandler?.removeCallbacks(supervisorWatchdogRunnable)
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        super.onDestroy()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidDesk Linux Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux desktop environment running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidDesk")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── Wake Lock ──

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DroidDesk::LinuxRuntime"
        ).apply {
            acquire(Long.MAX_VALUE)  // Keep CPU alive
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
