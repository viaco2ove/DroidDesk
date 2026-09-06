# tower pm2
解决pm2 多pid 混乱的问题。

核心问题是：**Tower PM2 模块应该如何设计——直接使用 PM2，还是自己开发一个轻量进程管理器，且要求每个服务单 PID 运行（不像 PM2 可以多开 fork）。**

下面我从架构选型、方案对比、推荐设计三个层面来详细分析。

---

## 一、两种方案对比

### 方案 A：直接使用 PM2

| 维度 | 分析 |
|------|------|
| **原理** | PM2 作为独立守护进程运行，Tower Panel 通过 CLI（`pm2 start/stop/restart/status`）或 HTTP API 与其交互 |
| **单实例限制** | PM2 原生设计是支持 `--instances 1` 到多实例的。虽然可以设 `instances: 1`，但 PM2 内部仍有 cluster 模块的 fork 逻辑，进程模型复杂 |
| **多 PID 共享问题** | PM2 的 `--watch`、`--restart` 会重新 fork 进程，旧 PID 不会立即释放。在 Tower 的 service 模型中，一个服务对应一个 PID，PM2 的"重启即 fork 新进程"模型会导致 PID 不断跳动，不好追踪 |
| **运行时依赖** | PM2 是 Node.js 工具，你的服务是 Python，环境异构增加了部署复杂度 |
| **日志管理** | PM2 自带日志轮转，这是优势 |
| **集成难度** | 需要解析 PM2 的 `list` JSON 输出来展示状态，耦合度高，PM2 输出格式变化可能导致面板异常 |
| **卸载** | 卸载服务时需要同时清理 PM2 中的注册，容易残留 |

**结论：不推荐直接用 PM2。** PM2 的进程模型（多实例 + fork）与 Tower 的"一服务一 PID"理念不匹配，集成成本高、耦合深。

### 方案 B：自己开发轻量进程管理器（推荐）

| 维度 | 分析 |
|------|------|
| **原理** | 一个独立的 `tower-pm2` 守护进程，管理所有注册的 Service，每个 Service 严格一个 PID |
| **单实例保证** | 由架构本身保证：一个 Service 名只对应一个 PID，启动前检查是否已有运行中的 PID，有则拒绝或先 stop |
| **轻量** | 不需要 Node.js 运行时，纯 Python（与 Tower Panel 技术栈一致），依赖少 |
| **与 Tower 深度集成** | 进程管理器与 Panel 共用同一个 Service Registry（JSON/SQLite），状态完全可控 |
| **功能裁剪** | 不需要 PM2 的 cluster、load balancing、ecosystem 等复杂功能，只需要 start/stop/restart/status/auto-restart |
| **日志** | 自己实现简单的 stdout/stderr 重定向 + 日志轮转，代码量不大 |

**结论：推荐自己开发。** 轻量、可控、与 Tower 的 Service 模型完美契合。

---

## 二、推荐架构设计

### 整体架构

```
┌─────────────────────────────────────────────────┐
│                  Tower Panel (UI)                │
│  ┌──────────┬──────────┬──────────┬──────────┐  │
│  │ Service  │ Tower PM2│ Nginx    │ SSH      │  │
│  │ 管理页   │  进程管理  │  管理    │  管理    │  │
│  └──────────┴──────────┴──────────┴──────────┘  │
└──────────────────────┬──────────────────────────┘
                       │ REST API / Unix Socket
                       ▼
┌─────────────────────────────────────────────────┐
│           tower-pm2 Daemon (守护进程)             │
│                                                  │
│  ┌─────────────┐  ┌──────────────┐  ┌────────┐ │
│  │ Service      │  │ PID Monitor   │  │ Log    │ │
│  │ Registry     │  │ (心跳检测)     │  │ Writer │ │
│  │ (JSON/DB)    │  │              │  │        │ │
│  └─────────────┘  └──────┬───────┘  └───┬────┘ │
│                           │              │       │
│                    ┌──────┴──────┐       │       │
│                    │ Worker PIDs │◀──────┘       │
│                    │ (1 per svc) │              │
│                    └─────────────┘              │
└─────────────────────────────────────────────────┘
```

### 核心设计要点

#### 1. 守护进程模型

`tower-pm2` 作为一个后台守护进程常驻运行：

- **启动时**：读取 Service Registry，恢复所有标记为 `keep_live: true` 的服务
- **运行中**：每个服务一个子进程，记录 PID 到内存 + 磁盘
- **监控循环**：每 N 秒检查一次所有子进程是否存活，死亡则自动重启
- **信号处理**：捕获 SIGTERM/SIGINT，优雅停止所有子进程后退出

#### 2. Service Registry（服务注册表）

最简单的实现就是一个 JSON 文件，每个服务一条记录：

```json
{
  "services": [
    {
      "name": "Toonflow管理页",
      "path": "/opt/toonflow/panel/start-panel.sh",
      "working_dir": "/opt/toonflow/panel",
      "start_with_os": true,
      "keep_live": true,
      "pid": null,
      "status": "stopped",
      "stdout_log": "/var/log/tower/Toonflow管理页.out.log",
      "stderr_log": "/var/log/tower/Toonflow管理页.err.log",
      "restart_count": 0,
      "last_start_time": null
    }
  ]
}
```

关键约束：`name` 字段唯一，启动时检查是否已有同名运行中的进程，有则拒绝。

#### 3. 进程管理核心逻辑

```
start(name):
  1. 检查 Registry 中是否存在 name 的服务
  2. 检查该服务是否已有运行中的 PID（psutil 查询）
     → 有 → 返回 "already running (PID: xxx)"
  3. 读取 start_script, working_dir, log 路径
  4. subprocess.Popen(
       command=start_script,
       cwd=working_dir,
       stdout=open(stdout_log, 'a'),
       stderr=open(stderr_log, 'a'),
       start_new_session=True  # 独立进程组，避免被父进程信号杀死
     )
  5. 记录 PID 到 Registry
  6. 返回 "started (PID: xxx)"

stop(name):
  1. 读取 PID
  2. 发送 SIGTERM，等待 N 秒
  3. 如果还在运行，发送 SIGKILL
  4. 清除 PID，更新状态

restart(name):
  1. stop(name)
  2. start(name)

status(name):
  1. 读取 PID
  2. psutil.pid_exists(pid) → running/stopped
  3. 如果运行中，返回 uptime, CPU%, MEM%
```

#### 4. 与 Tower Panel 的通信方式

推荐 **Unix Socket + JSON**（轻量、安全，不需要开网络端口）：

```
Tower Panel ──→ unix:///var/run/tower-pm2.sock ──→ tower-pm2 daemon
```

请求格式（JSON over line-delimited）：
```json
{"action": "start", "service": "Toonflow管理页"}
{"action": "status", "service": "Toonflow管理页"}
{"action": "stop", "service": "Toonflow管理页"}
{"action": "list"}
```

响应格式：
```json
{"ok": true, "pid": 12345, "status": "running", "uptime": "2h15m", "cpu": 3.2, "mem": 45.1}
```

#### 5. 健康检查与自动重启

```
监控循环（每 5 秒）:
  for each service in services:
    if service.keep_live and not psutil.pid_exists(service.pid):
      log("Service died, restarting...")
      service.restart_count += 1
      spawn_new_process(service)
```

可以加一个防抖机制：5 分钟内重启超过 5 次则标记为 `crash_loop` 并告警，避免无限重启。

#### 6. 开机自启

- `tower-pm2` 自身通过 systemd 或 init.d 脚本开机自启
- 每个服务的 `start_with_os: true` 在 `tower-pm2` 启动时自动恢复

---

## 三、与 Nginx 的集成设计

从你的 UI 设计来看，每个 Python 服务有一个 "start nginx with Ubuntu" 开关。这里的集成逻辑应该是：

```
服务启动时:
  1. tower-pm2 启动 Python 进程
  2. 如果该服务开启了 nginx 集成 → tower-pm2 调用 nginx 配置管理
     - 生成/更新一个 server block（upstream 指向 localhost:端口）
     - nginx -t 测试配置
     - nginx -s reload 热重载

服务停止/卸载时:
  1. tower-pm2 停止进程
  2. 移除对应的 server block
  3. nginx -s reload
```

这样 Nginx 只运行一个实例（由 Tower 管理），Python 服务由 tower-pm2 管理，职责清晰。

---

## 四、技术选型建议

| 组件 | 推荐 | 理由 |
|------|------|------|
| **守护进程框架** | `systemd` (进程守护) + 自定义 Python 守护进程 | systemd 负责崩溃重启和开机自启，Python 进程管理器负责业务逻辑 |
| **进程管理** | `subprocess.Popen` + `psutil` | 轻量、成熟、PID 管理简单 |
| **服务注册表** | JSON 文件（`/etc/tower/services.json`） | 简单可靠，方便编辑和调试 |
| **通信方式** | Unix Socket + JSON | 不需要开网络端口，安全轻量 |
| **日志** | stdout/stderr 重定向到文件 + 简单日志轮转 | 不需要 logrotate 复杂配置 |
| **配置管理** | 每个服务一个 YAML/JSON 文件在 `/etc/tower/services/` | 方便版本控制和编辑 |

---

## 五、总结建议

**推荐方案：自己开发一个轻量 `tower-pm2` 进程管理器。**

理由：
1. **单 PID 约束**：你自己控制进程模型，天然保证一服务一 PID，不存在 PM2 的多实例 fork 问题
2. **轻量可控**：不需要 Node.js 运行时，代码量不大（核心逻辑约 300-500 行 Python），完全可控
3. **深度集成**：与 Tower 的 Service Registry、Nginx 管理、SSH 管理共用同一套基础设施，状态一致
4. **卸载干净**：删除服务时直接停进程 + 清 PID + 删配置，不会有 PM2 的残留问题
5. **易于调试**：所有逻辑都在你的代码里，出问题直接看代码和日志，不需要排查 PM2 的内部行为

如果你后续需要我帮你生成 `tower-pm2` 的完整 Python 实现代码，或者把这份设计整理成详细的技术文档，随时告诉我。


## 同时地
D:\Users\viaco\tools\Toonflow-game\toonflow-game-app\md\deploy\android_linux\ubuntu_android_droiddesk 
的pm2 命令改用 tower-pm2 命令

# tower-pm2 cli 命令设计
使用pm2 一致的命令设计
不用点 tower-pm2 staus  等命令 不同的pid 显示的内容是一致的。
特别是 "putty" 和 “ubuntu shell” 调用tower-pm2 cli 命令的一致性。