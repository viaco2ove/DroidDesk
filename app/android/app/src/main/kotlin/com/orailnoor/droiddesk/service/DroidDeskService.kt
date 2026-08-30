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
                    // sshWithUbuntu=true 时确保 sshd 也在跑
                    if (sp.getBoolean("sshWithUbuntu", false) &&
                        runtime.isUbuntuSshInstalled() &&
                        !runtime.isUbuntuSshdRunning()) {
                        Log.i(TAG, "Daemon active, starting sshd...")
                        runtime.startUbuntuSshd()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore Ubuntu session: ${e.message}")
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
            "-- /bin/bash --login"
        )
        Log.i(TAG, "Daemon: Ubuntu session command file written")

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
            "-- /bin/bash --login"
        )
            .redirectErrorStream(true)
            .start()
        Log.i(TAG, "Daemon: Ubuntu session started")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KeepAliveFloat.dismiss()
        releaseWakeLock()
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
