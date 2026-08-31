# DroidDesk 功能增强说明

> 本文档说明 `dev_zxm` / `main_zxm` 分支相对于上游 `main` 分支的定制功能。

---

## 📋 功能总览

| 模块 | 状态 | 说明 |
|------|------|------|
| 原生终端 | 🆕 新增 | 完整 Termux 终端模拟器，支持手势和快捷键 |
| Ubuntu 环境 | 🆕 新增 | rootfs 下载安装 + 运行时管理 + SSH |
| 保活悬浮窗 | 🆕 新增 | 防止后台进程被系统杀灭 |
| 开机自启 | 🆕 新增 | 设备启动后自动恢复 Linux 环境 |
| 远程命令接口 | 🆕 新增 | 第三方应用可调用执行命令 |
| JNI 集成 | 🆕 新增 | C 层 socket hook 实现 |

---

## 🆕 1. 原生终端 (Native Terminal)

### 新增文件
- `TerminalEmulator.java` — 终端模拟器核心 (2617 行)
- `TerminalSession.java` — 伪终端会话管理
- `TerminalBuffer.java` — 文本缓冲与滚动历史
- `TerminalView.java` — 原生终端视图 (1507 行)
- `TerminalRenderer.java` — 终端渲染器
- `KeyHandler.java` — 键码映射与控制码生成
- `GestureAndScaleRecognizer.java` — 手势与缩放识别
- `TerminalExtraKeysView.kt` — 底部快捷键工具栏
- `NativeTerminalActivity.kt` — 原生终端 Activity

### 功能特性
- ✅ 伪终端子进程管理 (JNI via `libtermux.so`)
- ✅ 线程安全的循环字节缓冲 (`ByteQueue`)
- ✅ 多修饰键组合支持 (Ctrl, Alt, Shift, Meta)
- ✅ 文本选择与复制粘贴
- ✅ 手势支持：滑动滚动、双指缩放
- ✅ 底部快捷键栏：Tab, Ctrl, Alt, ESC, 方向键等
- ✅ 终端颜色方案支持

---

## 🆕 2. Ubuntu 运行环境

### 新增文件
- `UbuntuConsoleScreen.dart` — Ubuntu 管理界面
- `LinuxRuntime.kt` (重构) — Ubuntu 运行时引擎
- `KeepAliveFloat.kt` — 保活悬浮窗服务

### 功能特性
- ✅ **rootfs 下载安装**：集成 Ubuntu 24.04 rootfs 下载/解压流程
- ✅ **SSH 远程访问**：内置 OpenSSH 服务 (端口 8122)
- ✅ **保活悬浮窗**：显示 CPU/内存状态，防止进程被后台清理
- ✅ **开机自启**：设备启动后自动恢复 Ubuntu 环境
- ✅ **独立 sshd 会话**：与桌面 session 分离，sshd 独立运行

### 界面预览
```
┌─────────────────────────────────┐
│  🐧 Ubuntu 运行环境             │
├─────────────────────────────────┤
│  [━━━━━━━━━━━] 0.0%             │
│  下载中: bootstrap-aarch64.zip   │
├─────────────────────────────────┤
│  ○ Ubuntu 状态: 已停止           │
│  ○ SSH 服务: 已停止              │
│  ○ 开机自启: 关闭                │
├─────────────────────────────────┤
│  [启动 Ubuntu]  [启动 SSH]       │
│  [悬浮窗]  [卸载]                │
└─────────────────────────────────┘
```

---

## 🆕 3. 开机自启 (Boot Receiver)

### 新增文件
- `BootReceiver.kt`

### 功能特性
- ✅ 监听 `BOOT_COMPLETED`、`QUICKBOOT_POWERON`、`REBOOT` 广播
- ✅ 启动时自动恢复 DroidDeskService
- ✅ 可选自动启动 Ubuntu 环境

---

## 🆕 4. 前台服务 (Foreground Service)

### 新增文件
- `DroidDeskService.kt` — 前台服务保活
- `KeepAliveFloat.kt` — 悬浮窗显示运行时状态

### 功能特性
- ✅ 申请 `FOREGROUND_SERVICE` 权限
- ✅ 显示常驻通知，防止被系统杀灭
- ✅ 悬浮窗显示：CPU、内存、进程状态

---

## 🆕 5. 远程命令接口 (RunCommandService)

### 新增文件
- `RunCommandService.kt`
- 自定义权限 `com.orailnoor.droiddesk.permission.RUN_COMMAND`

### 功能特性
- ✅ 第三方应用可调用执行 shell 命令
- ✅ Intent action: `com.orailnoor.droiddesk.RUN_COMMAND`
- ✅ 支持命令参数传递和结果返回

---

## 🆕 6. JNI 集成 (Native Bridge)

### 新增文件
- `cpp/termux.c` — JNI 实现的 C 代码
- `cpp/Android.mk` — Android NDK 构建配置
- `JNI.java` — JNI 接口封装

### 功能特性
- ✅ `LD_PRELOAD` socket hook (`libsocket_hook.so`)
- ✅ 文件操作重定向
- ✅ Unix socket 连接劫持
- ✅ 无需 root 的 chroot 替代方案

---

## 集成并支持 Ubuntu supervisor 守护进程管理

- 在 DroidDeskService 中添加 supervisor 健康监控定时任务
- 支持 supervisorWithUbuntu 开关，优先启动 supervisor 管理 sshd 和 nginx
- LinuxRuntime 增加 supervisor 相关方法，包括安装、启动、停止及状态检测
- supervisor 安装流程通过 apt-get 实现，自动写入默认配置管理 sshd/nginx
- MainActivity 增加对 supervisorWithUbuntu 配置开关的支持及安装接口
- Flutter 端新增 supervisor 状态检测与安装功能及安装进度回调
- UbuntuConsoleScreen 添加 supervisor 相关 UI 控件及安装引导和状态刷新
- 关闭 supervisor 时自动停止旧 sshd 以避免端口冲突，开启时由 supervisor 管理子进程
- 优化服务启动逻辑，确保 supervisor 与 pm2 守护进程不冲突，提升稳定性

## 🔧 代码优化

### 重构内容
| 文件 | 变更 |
|------|------|
| `MainActivity.kt` | 增强初始化逻辑 |
| `platform_bridge.dart` | 统一跨平台接口 |
| `app_state.dart` | 添加 Ubuntu 安装状态 |
| `home_screen.dart` | 集成 Ubuntu 入口 |
| `app_catalog_screen.dart` | 添加 Ubuntu 安装选项 |

### bash 修复
- ✅ 修复 bash 启动参数，加载 `~/.bashrc` 配置
- ✅ 修正默认 SSH 端口为 8122

---

## 📦 依赖变更

### 新增 Flutter 依赖
```yaml
dependencies:
  google_fonts: ^6.2.1      # JetBrains Mono 字体
  provider: ^6.1.2           # 状态管理
  shared_preferences: ^2.3.4 # 本地存储
  percent_indicator: ^4.2.3 # 安装进度
  flutter_animate: ^4.5.2    # 动画效果
  url_launcher: ^6.3.2       # 外部链接
```

---

## 📁 新增资源文件

| 文件 | 说明 |
|------|------|
| `assets/terminal.html` | 终端 Web 界面 |
| `assets/bootstrap-aarch64.zip` | Ubuntu rootfs 压缩包 |
| `assets/socket_hook.c` | Socket hook 源码 |
| `drawable/text_select_handle_*.xml` | 文本选择控制柄 |

---

## 🔄 分支说明

| 分支 | 用途 |
|------|------|
| `main` | 同步上游 orailnoor/DroidDesk |
| `main_zxm` | 稳定版发布分支 |
| `dev_zxm` | 开发中分支，功能验证后合并到 main_zxm |

---

## 有没有合并到原项目的可能和必要
** fork 后的方向很清晰 **把 DroidDesk 做成一个 Ubuntu 服务器方向的产品**。
fork   https://github.com/orailnoor/DroidDesk.git
后 https://github.com/viaco2ove/DroidDesk.git 改了许多，还有合并到原项目嘛？
结论：方向差异较大，无需合并到原项目

### 核心定位：Ubuntu 服务器化

本质上是将 DroidDesk 从一个**"桌面环境安装器"**转型为一个**"Android 上的 Ubuntu 服务器平台"**。
三个核心模块正好对应服务器场景的三大需求：

| 模块 | 对应服务器能力 |
|------|-------------|
| **Ubuntu 运行环境** | 提供完整的 Ubuntu 24.04 用户空间，自带 OpenSSH 服务，这是"服务器"的基础 |
| **服务保活（悬浮窗）** | 防止 Android 系统后台杀进程，保证服务器长期在线——这是手机跑服务器最大的痛点 |
| **原生终端** | 提供可交互的终端入口，方便远程管理——这是服务器的"控制台" |

---

### 与原项目的关系分析

#### 原项目的定位（orailnoor/DroidDesk）

从 README 可以明确看出，原项目的核心定位是：

> **"在 Android 上零 Root 运行完整 Ubuntu + XFCE 桌面环境"**

关键词是：**桌面环境（Desktop Environment）**。它的目标是让用户把手机变成一台"Linux PC"，能打开 VS Code、LibreOffice、Blender 这些桌面应用。安装流程围绕"选桌面环境 → 装 XFCE/LXQt/KDE → 启动 X11"展开。

#### forked 的方向（viaco2ove/DroidDesk）

forked的方向是：

> **"在 Android 上运行一个长期在线的 Ubuntu 服务器"**

关键词是：**服务器（Server）**。forked的目标不是跑桌面应用，而是提供 SSH 服务、后台守护进程、长期运行的服务。安装流程围绕"下载 rootfs → 启动 SSH → 保活 → 远程管理"展开。

### 理念冲突点评估

| 维度 | 原项目 | forked的 fork | 冲突程度 |
|------|--------|---------|---------|
| **核心目标** | 桌面环境 | 服务器 | ⚠️ 较大——目标用户不同 |
| **Ubuntu 运行环境** | 通过 proot 按需安装 | 内置 rootfs 下载 + SSH 服务 | ⚠️ 中等——功能增强，但改变了"按需安装"的轻量理念 |
| **服务保活** | 无（用户手动管理） | 悬浮窗 + 前台服务 | ✅ 小——属于运维增强，不改变核心架构 |
| **原生终端** | 依赖 Termux 自带终端 | 自研终端模拟器（2617 行） | ⚠️ 中等——原项目认为终端是 Termux 的事，forked把它做进了 App |
| **开机自启** | 无 | 广播监听 + 自动恢复 | ✅ 小——服务器场景刚需 |
| **远程命令接口** | 无 | 第三方应用调用 | ✅ 小——扩宽了使用场景 |


*文档更新时间: 2024-08-29*
