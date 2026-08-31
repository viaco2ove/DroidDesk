package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast
import java.io.File

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val TAG = "MainActivity"
    }

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime.getInstance(this)
        chrootRuntime = ChrootRuntime(this)

        if (intent.getBooleanExtra("autoSetup", false)) {
            runAutoChrootSetup()
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction.
     */
    private fun runAutoChrootSetup() {
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!chrootRuntime.hasRoot()) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Auto-setup requires root", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                startForegroundService()

                if (!chrootRuntime.isRootfsReady()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    if (!dlOk) throw RuntimeException("Rootfs download failed")

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    if (!exOk) throw RuntimeException("Rootfs extraction failed")
                }

                if (!chrootRuntime.isDesktopInstalled()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, _ ->
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    if (!inOk) throw RuntimeException("Desktop installation failed")
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                runOnUiThread {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", true)
                        putExtra("mode", "chroot")
                        putExtra("de", "xfce4")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup failed", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Auto-setup failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    val rooted = chrootRuntime.hasRoot()
                    result.success(mapOf(
                        "isBootstrapped" to if (rooted) chrootRuntime.isRootfsReady() else linuxRuntime.isBootstrapped(),
                        "isRunning" to if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning(),
                        "hasRoot" to rooted,
                        "distro" to if (rooted) "ubuntu-chroot" else "termux-native",
                        "installedDE" to if (rooted) {
                            if (chrootRuntime.isDesktopInstalled()) "xfce4" else ""
                        } else {
                            linuxRuntime.getInstalledDE()
                        },
                        "rootfsPath" to if (rooted) chrootRuntime.getRootfsPath() else "",
                        "rootfsSizeMB" to if (rooted) chrootRuntime.getRootfsSizeMB() else 0L
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to if (chrootRuntime.hasRoot()) {
                            "Software (llvmpipe)"
                        } else {
                            linuxRuntime.getGraphicsMode()
                        },
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Root checks ──
                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onDownloadProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onExtractProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to status)
                                            )
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onTerminalOutput",
                                                mapOf("text" to logChunk)
                                            )
                                        }
                                    }
                                }
                            )
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    thread {
                        linuxRuntime.setInstallLogSink { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    val status = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.getOptionalAppsStatus()
                    } else {
                        linuxRuntime.getOptionalAppsStatus()
                    }
                    result.success(status)
                }

                "isUbuntuInstalled" -> {
                    val installed = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.isRootfsReady()
                    } else {
                        linuxRuntime.isProotDistroInstalled("ubuntu")
                    }
                    result.success(installed)
                }

                "launchUbuntuTerminal" -> {
                    Log.i(TAG, "launchUbuntuTerminal invoked")
                    // 启动前台服务，确保 ubuntu 会话退到后台后不被 Phantom Process Killer 杀掉
                    if (linuxRuntime.isBootstrapped() && linuxRuntime.isProotDistroInstalled("ubuntu")) {
                        startForegroundService()
                    }
                    // 把完整的 shell 命令写到文件，绕过执行位问题
                    if (linuxRuntime.isBootstrapped()) {
                        val cmdFile = java.io.File(filesDir, "bin/ubuntu-shell.cmd")
                        cmdFile.parentFile?.mkdirs()
                        // 不使用 exec，保留 sh 进程以便查看错误
                        val homeDirPath = "${filesDir.absolutePath}/home"
                        cmdFile.writeText(
                            "export PREFIX=\"${linuxRuntime.prefixPath}\"; " +
                            "export TMPDIR=\"${filesDir.absolutePath}/tmp\"; " +
                            "export HOME=\"$homeDirPath\"; " +
                            "export TERMUX_APP__PACKAGE_NAME=\"${packageName}\"; " +
                            "export TERMUX_APP__DATA_DIR=\"${filesDir.absolutePath}\"; " +
                            "export TERMUX__PREFIX=\"${linuxRuntime.prefixPath}\"; " +
                            "export TERMUX__HOME=\"$homeDirPath\"; " +
                            "export PATH=\"${linuxRuntime.prefixPath}/bin:/system/bin\"; " +
                            "export PYTHONHOME=\"${linuxRuntime.prefixPath}\"; " +
                            "export LD_LIBRARY_PATH=\"${linuxRuntime.prefixPath}/lib\"; " +
                            "mkdir -p \"${filesDir.absolutePath}/tmp/proot\"; " +
                            "cd \"$homeDirPath\"; " +
                            "${linuxRuntime.prefixPath}/bin/proot-distro login ubuntu " +
                            "--bind \"${filesDir.absolutePath}/tmp:/tmp\" " +
                            "--env PROOT_TMP_DIR=\"${filesDir.absolutePath}/tmp/proot\" " +
                            "--env PROOT_LOADER=\"${linuxRuntime.prefixPath}/libexec/proot/loader\" " +
                            "--env PROOT_LOADER_32=\"${linuxRuntime.prefixPath}/libexec/proot/loader32\" " +
                            // -i 强制交互式 shell，让 ~/.bashrc 顶部 PS1 检查通过并加载
                            // -l 走 login 流程：读取 /etc/profile → ~/.profile → ~/.bashrc
                            "-- /bin/bash -i -l\n"
                        )
                        Log.i(TAG, "Ubuntu cmd file written: ${cmdFile.absolutePath}")
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Launching Ubuntu terminal...", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.terminal.NativeTerminalActivity::class.java)
                        intent.putExtra("env", "ubuntu")
                        startActivity(intent)
                        Log.i(TAG, "launchUbuntuTerminal: startActivity ok")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "launchUbuntuTerminal failed", e)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        result.error("LAUNCH_FAILED", e.message, null)
                    }
                }

                "getUbuntuSettings" -> {
                    val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
                    val settings = mapOf(
                        "daemon" to sp.getBoolean("daemon", false),
                        "boot" to sp.getBoolean("boot", false),
                        "sshWithUbuntu" to sp.getBoolean("sshWithUbuntu", false),
                        "keepAliveFloat" to sp.getBoolean("keepAliveFloat", true),
                        "pm2WithUbuntu" to sp.getBoolean("pm2WithUbuntu", false),
                    )
                    result.success(settings)
                }

                "setUbuntuSetting" -> {
                    val key = call.argument<String>("key") ?: ""
                    val value = call.argument<Boolean>("value") ?: false
                    val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
                    sp.edit().putBoolean(key, value).apply()
                    applyUbuntuSettings(sp)
                    // 守护 / sshWithUbuntu / keepAliveFloat / pm2WithUbuntu 开关开启时，确保前台服务在线以保护子进程
                    if ((key == "daemon" || key == "sshWithUbuntu" || key == "keepAliveFloat" || key == "pm2WithUbuntu") && value) {
                        startForegroundService()
                    }
                    result.success(true)
                }

                "getUbuntuCredentials" -> {
                    val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
                    val creds = mapOf(
                        "user" to (sp.getString("user", "") ?: ""),
                        "password" to (sp.getString("password", "") ?: ""),
                        "port" to (sp.getString("port", "8122") ?: "8122"),
                    )
                    result.success(creds)
                }

                "setUbuntuCredentials" -> {
                    val user = call.argument<String>("user") ?: ""
                    val password = call.argument<String>("password") ?: ""
                    val port = call.argument<String>("port") ?: "8122"
                    val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
                    sp.edit()
                        .putString("user", user)
                        .putString("password", password)
                        .putString("port", port)
                        .apply()
                    applyUbuntuCredentials(user, password, port)
                    result.success(true)
                }

                "isUbuntuSshInstalled" -> {
                    val installed = if (chrootRuntime.hasRoot()) {
                        File(chrootRuntime.getRootfsPath(), "usr/sbin/sshd").exists()
                    } else {
                        linuxRuntime.isUbuntuSshInstalled()
                    }
                    result.success(installed)
                }

                "installUbuntuSsh" -> {
                    thread {
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status))
                            }
                        }
                        val logSink: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installUbuntuSsh(progressSink, logSink)
                        } else {
                            linuxRuntime.installUbuntuSsh(progressSink)
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                "uninstallUbuntuSsh" -> {
                    thread {
                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.uninstallUbuntuSsh()
                        } else {
                            linuxRuntime.uninstallUbuntuSsh()
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                "getUbuntuStatus" -> {
                    val ubuntuRunning = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.isChrootRunning()
                    } else {
                        linuxRuntime.isUbuntuProotRunning()
                    }
                    val sshdRunning = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.isSshdRunning()
                    } else {
                        linuxRuntime.isUbuntuSshdRunning()
                    }
                    val status = mapOf(
                        "ubuntuRunning" to ubuntuRunning,
                        "sshdRunning" to sshdRunning,
                        "sshPort" to getSshPortFromPrefs(),
                    )
                    Log.d(TAG, "getUbuntuStatus: ubuntuRunning=$ubuntuRunning sshdRunning=$sshdRunning")
                    result.success(status)
                }

                "startUbuntuSshd" -> {
                    // 启动前台服务：sshd 必须在 service 持有下才能存活
                    if (linuxRuntime.isBootstrapped() && linuxRuntime.isProotDistroInstalled("ubuntu")) {
                        startForegroundService()
                    }
                    thread {
                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.startUbuntuSshd()
                        } else {
                            linuxRuntime.startUbuntuSshd()
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                "stopUbuntuSshd" -> {
                    thread {
                        if (chrootRuntime.hasRoot()) {
                            chrootRuntime.stopUbuntuSshd()
                        } else {
                            linuxRuntime.stopUbuntuSshd()
                        }
                        runOnUiThread { result.success(true) }
                    }
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    thread {
                        val logSink: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                            }
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installOptionalApp(appId, progressSink, logSink)
                        } else {
                            linuxRuntime.setInstallLogSink(logSink)
                            try {
                                linuxRuntime.installOptionalApp(appId, progressSink)
                            } finally {
                                linuxRuntime.setInstallLogSink(null)
                            }
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "x11"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080

                    if (height > 720) {
                        val scale = 720.0 / height
                        width = (width * scale).toInt()
                        height = 720
                    }

                    startForegroundService()

                    if (chrootRuntime.hasRoot()) {
                        // Rooted fast path: chroot + LorieView
                        thread {
                            if (!chrootRuntime.isRootfsReady()) {
                                Log.w(TAG, "Chroot rootfs not ready; cannot start session")
                                runOnUiThread { result.success(false) }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "chroot")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    } else {
                        // Non-root fallback: native Termux path
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            val installed = linuxRuntime.getInstalledDE()
                            val ready = installed == desktopEnv ||
                                linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                            if (!ready) {
                                Log.e(TAG, "Native Termux desktop setup failed; session was not launched")
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Native Linux setup failed. Check the setup log.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    result.success(false)
                                }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", true)
                                    putExtra("mode", "termux")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java)
                    startActivity(intent)
                    result.success(true)
                }

                // ── Native Terminal ──
                "launchNativeTerminal" -> {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.terminal.NativeTerminalActivity::class.java)
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    thread(name = "stop-linux-session") {
                        if (chrootRuntime.hasRoot() || chrootRuntime.isRunning()) {
                            chrootRuntime.stopSession()
                        }
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        } else {
                            linuxRuntime.executeCommand(command) { chunk ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                    }
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "canDrawOverlays" -> {
                    result.success(android.provider.Settings.canDrawOverlays(this))
                }

                // 调试入口：直接显示 / 隐藏保活悬浮窗（用于 adb 验证）
                "__showKeepAlive" -> {
                    if (android.provider.Settings.canDrawOverlays(this)) {
                        com.orailnoor.droiddesk.service.KeepAliveFloat.show(this)
                        startForegroundService()
                        result.success(true)
                    } else {
                        result.error("NO_OVERLAY_PERMISSION", "Need SYSTEM_ALERT_WINDOW", null)
                    }
                }
                "__hideKeepAlive" -> {
                    com.orailnoor.droiddesk.service.KeepAliveFloat.dismiss()
                    result.success(true)
                }

                "requestOverlayPermission" -> {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    result.success(true)
                }

                "setupBootstrap" -> {
                    if (chrootRuntime.hasRoot()) {
                        // Nothing to bootstrap for chroot; rootfs handles it
                        result.success(true)
                    } else {
                        thread {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    // ── Ubuntu Console helpers ──

    private fun applyUbuntuSettings(sp: android.content.SharedPreferences) {
        // sshWithUbuntu is enforced at session start time; here we just persist
        // and rely on the lifecycle service / boot receiver to start sshd.
        // A real implementation would update a service Intent; for now we
        // persist the flag and let the launcher query it.
        val sshWithUbuntu = sp.getBoolean("sshWithUbuntu", false)
        Log.i(TAG, "Ubuntu settings applied; sshWithUbuntu=$sshWithUbuntu")
        // 同步重启 sshd 以反映开关
        thread(name = "ubuntu-settings-apply") {
            try {
                if (sshWithUbuntu) {
                    if (chrootRuntime.hasRoot()) chrootRuntime.startUbuntuSshd()
                    else linuxRuntime.startUbuntuSshd()
                } else {
                    if (chrootRuntime.hasRoot()) chrootRuntime.stopUbuntuSshd()
                    else linuxRuntime.stopUbuntuSshd()
                }
            } catch (e: Exception) {
                Log.w(TAG, "apply sshd state: ${e.message}")
            }
        }
    }

    private fun getSshPortFromPrefs(): Int {
        val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
        return sp.getString("port", "22")?.toIntOrNull() ?: 22
    }

    private fun applyUbuntuCredentials(user: String, password: String, port: String) {
        thread(name = "apply-ubuntu-credentials") {
            try {
                if (user.isEmpty()) return@thread
                val tmpDirPath = "${filesDir.absolutePath}/tmp/proot"
                java.io.File(tmpDirPath).mkdirs()
                val prootArgs = "login ubuntu " +
                        "--bind \"${filesDir.absolutePath}/tmp:/tmp\" " +
                        "--env PROOT_TMP_DIR=\"$tmpDirPath\" " +
                        "--env PROOT_LOADER=\"${linuxRuntime.prefixPath}/libexec/proot/loader\" " +
                        "--env PROOT_LOADER_32=\"${linuxRuntime.prefixPath}/libexec/proot/loader32\" --"
                if (chrootRuntime.hasRoot() && chrootRuntime.isRootfsReady()) {
                    val escaped = password.replace("'", "'\\''")
                    chrootRuntime.executeCommand(
                        "id -u $user >/dev/null 2>&1 || useradd -m -s /bin/bash $user"
                    )
                    if (password.isNotEmpty()) {
                        chrootRuntime.executeCommand("echo '$user:$escaped' | chpasswd")
                    }
                    // 修改 sshd 端口
                    if (port.isNotEmpty()) {
                        chrootRuntime.executeCommand(
                            "sed -i 's/^#\\?Port .*/Port $port/' /etc/ssh/sshd_config"
                        )
                    }
                } else if (linuxRuntime.isBootstrapped()) {
                    linuxRuntime.executeCommand(
                        "proot-distro $prootArgs id -u $user >/dev/null 2>&1 || " +
                        "proot-distro $prootArgs useradd -m -s /bin/bash $user"
                    )
                    if (password.isNotEmpty()) {
                        val escaped = password.replace("'", "'\\''")
                        linuxRuntime.executeCommand(
                            "proot-distro $prootArgs bash -c \"echo '$user:$escaped' | chpasswd\""
                        )
                    }
                    // 修改 sshd 端口
                    if (port.isNotEmpty()) {
                        linuxRuntime.executeCommand(
                            "proot-distro $prootArgs sed -i 's/^#\\?Port .*/Port $port/' /etc/ssh/sshd_config"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Ubuntu credentials", e)
            }
        }
    }

    // ── Lifecycle ──

    override fun onResume() {
        super.onResume()
        // 从 Settings（悬浮窗授权页）返回后，重新检测权限并启动 service
        // 这样用户在授权页面点"允许"回来后，悬浮窗就能正确显示了
        if (android.provider.Settings.canDrawOverlays(this)) {
            val sp = getSharedPreferences("ubuntu_console", Context.MODE_PRIVATE)
            if (sp.getBoolean("keepAliveFloat", true)) {
                Log.i(TAG, "onResume: overlay granted, starting foreground service")
                startForegroundService()
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
