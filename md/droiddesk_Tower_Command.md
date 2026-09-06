# Droiddesk_droiddesk-tower cli 命令设计
## 一、设计原则

1. **统一入口**：所有命令通过 `droiddesk-tower` 命令执行，简洁易记
2. **模块化管理**：按功能模块组织子命令（panel、ssh、nginx、service、pm2）
3. **一致性**：每个模块都支持 status/start/stop/restart 等基础操作
4. **简洁优先**：常用操作一步到位，高级操作通过选项扩展
5. **机器可读**：支持 `--json` 输出格式，便于脚本集成和监控
6. **帮助完善**：`droiddesk-tower --help` 和 `droiddesk-tower <模块> --help` 提供完整帮助
7. **UI 对齐**：CLI 命令与 Web UI 功能一一对应，用户可通过 UI 或 CLI 完成相同操作

## 二、命令结构

```
droiddesk-tower [全局选项] <模块> <子命令> [参数] [选项]
```

### 全局选项

| 选项 | 说明 |
|------|------|
| `--help` | 显示帮助信息 |
| `--version` | 显示版本号 |
| `--json` | 以 JSON 格式输出（与后续命令组合使用） |
| `--config <path>` | 指定配置文件路径（默认 /etc/droiddesk-tower/config.json） |
| `--quiet` | 静默模式，仅输出错误信息 |
| `--verbose` | 详细模式，输出调试信息 |

### 命令层级

```
droiddesk-tower
├── panel          # 主面板管理
├── ssh            # SSH 服务管理
├── nginx          # Nginx Web 服务器管理
├── service        # 服务注册表 + 服务生命周期管理（高频使用）
└── pm2            # 进程管理器守护进程管理
```

## 三、模块命令详情

### 3.1 droiddesk-tower panel — 主面板管理

管理 Droiddesk droiddesk-tower 主面板本身。

| 命令 | 说明 |
|------|------|
| `droiddesk-tower panel status` | 查看面板运行状态、端口、启动时间 |
| `droiddesk-tower panel start` | 启动面板 |
| `droiddesk-tower panel stop` | 停止面板 |
| `droiddesk-tower panel restart` | 重启面板 |
| `droiddesk-tower panel port [PORT]` | 查看或修改面板访问端口 |
| `droiddesk-tower panel logs [LINES]` | 查看面板运行日志（默认最近 50 行） |

**示例：**

```
droiddesk-tower panel status
droiddesk-tower panel port 8080
droiddesk-tower panel logs 100
```

**与 UI 对应关系：**

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `droiddesk-tower panel status` | 主面板状态显示 |
| `droiddesk-tower panel port` | 主面板端口修改输入框 |

---

### 3.2 droiddesk-tower ssh — SSH 服务管理

管理 SSH 服务（open-server）。

| 命令 | 说明 |
|------|------|
| `droiddesk-tower ssh status` | 查看 SSH 运行状态 |
| `droiddesk-tower ssh start` | 启动 SSH 服务 |
| `droiddesk-tower ssh stop` | 停止 SSH 服务 |
| `droiddesk-tower ssh restart` | 重启 SSH 服务 |
| `droiddesk-tower ssh set-password [USER] [PASSWORD]` | 设置 SSH 用户密码 |
| `droiddesk-tower ssh set-port [PORT]` | 修改 SSH 监听端口 |
| `droiddesk-tower ssh info` | 查看 SSH 详细信息（端口、用户、密钥等） |

**示例：**

```
droiddesk-tower ssh status
droiddesk-tower ssh set-password root newpassword
droiddesk-tower ssh set-port 2222
```

**与 UI 对应关系：**

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `droiddesk-tower ssh status/start/stop/restart` | SSH 模块开关按钮 |
| `droiddesk-tower ssh set-password` | SSH 账号密码输入框 + Save |
| `droiddesk-tower ssh set-port` | SSH 端口输入框 |

---

### 3.3 droiddesk-tower nginx — Nginx 管理

管理 Nginx Web 服务器。

| 命令 | 说明 |
|------|------|
| `droiddesk-tower nginx status` | 查看 Nginx 运行状态 |
| `droiddesk-tower nginx start` | 启动 Nginx |
| `droiddesk-tower nginx stop` | 停止 Nginx |
| `droiddesk-tower nginx restart` | 重启 Nginx |
| `droiddesk-tower nginx reload` | 热重载配置（不中断连接） |
| `droiddesk-tower nginx config-test` | 测试 Nginx 配置文件语法 |
| `droiddesk-tower nginx logs [LINES]` | 查看 Nginx 日志（默认最近 50 行） |
| `droiddesk-tower nginx conf` | 查看 Nginx 主配置文件路径 |

**示例：**

```
droiddesk-tower nginx status
droiddesk-tower nginx reload
droiddesk-tower nginx config-test
droiddesk-tower nginx logs 200
```

**与 UI 对应关系：**

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `droiddesk-tower nginx status/start/stop/restart/reload` | Nginx 模块开关 / restart 按钮 |

---

### 3.4 droiddesk-tower service — 服务管理（高频使用）

管理服务注册表与生命周期。这是用户最常用的模块，封装了底层 droiddesk-tower-pm2 的调用。

#### 3.4.1 服务注册表管理

| 命令 | 说明 |
|------|------|
| `droiddesk-tower service list` | 列出所有已注册服务及其状态 |
| `droiddesk-tower service add <name> <path> [选项]` | 注册一个新服务 |
| `droiddesk-tower service delete <name>` | 删除服务注册信息（不停止进程） |
| `droiddesk-tower service config <name> [key=value]` | 查看或修改服务配置 |

**add 选项：**

| 选项 | 说明 |
|------|------|
| `--nginx` / `-n` | 自动关联 Nginx 反向代理 |
| `--keep-live` / `-k` | 开启进程崩溃自动重启 |
| `--start-with-os` / `-s` | 开机自动启动 |
| `--working-dir <dir>` | 设置工作目录 |
| `--port <port>` | 设置服务监听端口（用于 Nginx 反向代理） |

**示例：**

```
droiddesk-tower service list
droiddesk-tower service add Toonflow管理页 /opt/toonflow/panel/start-panel.sh --nginx --keep-live
droiddesk-tower service delete Toonflow管理页
droiddesk-tower service config Toonflow管理页
```

#### 3.4.2 服务生命周期管理

| 命令 | 说明 |
|------|------|
| `droiddesk-tower service start <name>` | 启动服务（自动确保 droiddesk-tower-pm2 运行中） |
| `droiddesk-tower service stop <name>` | 停止服务（发送 SIGTERM，5秒后 SIGKILL） |
| `droiddesk-tower service restart <name>` | 重启服务（先停后启） |
| `droiddesk-tower service status <name>` | 查看服务运行状态（PID、CPU、内存、运行时间） |
| `droiddesk-tower service logs <name> [LINES]` | 查看服务运行日志（stdout/stderr） |

**示例：**

```
droiddesk-tower service start Toonflow管理页
droiddesk-tower service stop Toonflow管理页
droiddesk-tower service restart Toonflow管理页
droiddesk-tower service status Toonflow管理页
droiddesk-tower service logs Toonflow管理页 100
```

**与 UI 对应关系：**

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `droiddesk-tower service list` | 服务列表 |
| `droiddesk-tower service add` | 服务添加表单 |
| `droiddesk-tower service delete` | 服务删除按钮 |
| `droiddesk-tower service start/stop/restart` | 服务操作按钮 |
| `droiddesk-tower service status` | 服务状态/进程信息 |
| `droiddesk-tower service logs` | 服务日志查看器 |

**设计说明：** `droiddesk-tower service` 是高层封装接口，会自动检查 droiddesk-tower-pm2 守护进程是否运行，若未运行则自动启动。用户日常操作只需使用 `droiddesk-tower service` 即可，无需直接调用 `droiddesk-tower pm2`。

---

### 3.5 tower-pm2 — 进程管理器

管理 droiddesk-tower-pm2 守护进程及其管理的进程。提供底层进程管理能力。

#### 3.5.1 守护进程管理

| 命令 | 说明 |
|------|------|
| `tower-pm2 install` | 安装 droiddesk-tower-pm2 守护进程（创建 systemd 服务） |
| `tower-pm2 uninstall` | 卸载 droiddesk-tower-pm2 守护进程（停止并删除服务文件） |
| `tower-pm2 start` | 启动 droiddesk-tower-pm2 守护进程 |
| `tower-pm2 stop` | 停止 droiddesk-tower-pm2 守护进程 |
| `tower-pm2 restart` | 重启 droiddesk-tower-pm2 守护进程 |
| `tower-pm2 status` | 查看守护进程运行状态 |
| `tower-pm2 logs [LINES]` | 查看守护进程日志 |

**示例：**

```
tower-pm2 install
tower-pm2 start
tower-pm2 status
```

#### 3.5.2 进程管理

| 命令 | 说明 |
|------|------|
| `tower-pm2 list` | 列出所有被管理的进程（含运行时状态） |
| `tower-pm2 add <name> <path> [选项]` | 添加服务到进程管理（同时注册到服务注册表） |
| `tower-pm2 remove <name>` | 从进程管理中移除服务（停止进程并删除注册） |
| `tower-pm2 start <name>` | 启动指定服务进程 |
| `tower-pm2 stop <name>` | 停止指定服务进程 |
| `tower-pm2 restart <name>` | 重启指定服务进程 |
| `tower-pm2 logs <name> [LINES]` | 查看指定服务的 stdout/stderr 日志 |
| `tower-pm2 monit` | 实时监控模式（类似 pm2 monit，实时刷新状态） |

**add 选项：**

| 选项 | 说明 |
|------|------|
| `--nginx` / `-n` | 自动关联 Nginx 反向代理 |
| `--keep-live` / `-k` | 开启进程崩溃自动重启 |
| `--start-with-os` / `-s` | 开机自动启动 |
| `--working-dir <dir>` | 设置工作目录 |
| `--port <port>` | 设置服务监听端口 |

**示例：**

```
tower-pm2 list
tower-pm2 add Toonflow管理页 /opt/toonflow/panel/start-panel.sh --nginx --keep-live
tower-pm2 remove Toonflow管理页
tower-pm2 start Toonflow管理页
tower-pm2 stop Toonflow管理页
tower-pm2 restart Toonflow管理页
tower-pm2 logs Toonflow管理页 200
tower-pm2 monit
```

**与 UI 对应关系：**

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `tower-pm2 install/start/stop/restart/status` | droiddesk-tower-pm2 模块头部控制按钮 |
| `tower-pm2 list` | droiddesk-tower-pm2 服务列表表格 |
| `tower-pm2 add` | "+ 添加服务" 按钮 |
| `tower-pm2 remove` | 服务列表中的 delete 操作 |

**设计说明：** `droiddesk-tower pm2` 是底层进程管理器，直接控制 droiddesk-tower-pm2 守护进程。当用户通过 `droiddesk-tower service` 操作服务时，底层实际调用的是 `droiddesk-tower pm2` 的能力。`tower-pm2 monit` 提供类似 pm2 monit 的实时监控界面。

## 四、输出格式

### 4.1 人类可读格式（默认）

命令默认输出人类可读的格式化文本，使用表格、颜色等提升可读性。

**service list 输出示例：**

```
┌────┬──────────────────┬──────────┬──────┬───────────┬──────────┬──────────┐
│ id │ name             │ mode     │ ↺    │ status    │ cpu      │ memory   │
├────┼──────────────────┼──────────┼──────┼───────────┼──────────┼──────────┤
│ 0  │ Toonflow管理页    │ fork     │ 0    │ ● running │ 3.2%     │ 45.1MB   │
│ 1  │ Toonflow API     │ fork     │ 0    │ ● running │ 1.1%     │ 32.8MB   │
│ 2  │ Worker进程       │ fork     │ 0    │ ○ stopped │ —        │ —        │
└────┴──────────────────┴──────────┴──────┴───────────┴──────────┴──────────┘
```

### 4.2 JSON 格式

使用 `--json` 选项输出标准 JSON，便于脚本集成和监控。

```
droiddesk-tower service list --json
```

输出：

```json
[
  {
    "id": 0,
    "name": "Toonflow管理页",
    "mode": "fork",
    "restart_count": 0,
    "status": "running",
    "pid": 12345,
    "cpu": 3.2,
    "memory": 47185920,
    "uptime": "2h15m",
    "stdout_log": "/var/log/droiddesk-tower/Toonflow管理页.out.log",
    "stderr_log": "/var/log/droiddesk-tower/Toonflow管理页.err.log"
  }
]
```

## 五、退出码

| 退出码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1 | 通用错误 |
| 2 | 参数错误（用法不正确） |
| 3 | 服务未找到 |
| 4 | 服务已在运行 |
| 5 | 守护进程未运行 |
| 6 | 权限不足 |
| 7 | 端口已被占用 |
| 8 | 配置文件错误 |
| 9 | 服务注册冲突（同名服务已存在） |

## 六、使用场景示例

### 场景1：快速部署一个新服务

```
# 1. 确保 droiddesk-tower-pm2 运行中
tower-pm2 start

# 2. 添加服务并自动关联 Nginx
droiddesk-tower service add MyAPI /opt/myapi/start.sh --nginx --keep-live --start-with-os

# 3. 验证服务状态
droiddesk-tower service status MyAPI

# 4. 查看运行日志
droiddesk-tower service logs MyAPI
```

### 场景2：查看所有服务的 JSON 状态（用于监控脚本）

```
droiddesk-tower service list --json | jq '.[] | select(.status == "running") | .name'
```

### 场景3：修改服务配置

```
# 查看当前配置
droiddesk-tower service config MyAPI

# 修改 Keep Live 开关
droiddesk-tower service config MyAPI keep_live=true

# 修改 Nginx 关联
droiddesk-tower service config MyAPI nginx=true
```

### 场景4：故障排查

```
# 1. 查看守护进程状态
tower-pm2 status

# 2. 查看服务详细状态
droiddesk-tower service status MyAPI

# 3. 查看服务日志
droiddesk-tower service logs MyAPI 500

# 4. 重启服务
droiddesk-tower service restart MyAPI

# 5. 如果问题依旧，重启守护进程
tower-pm2 restart
```

### 场景5：批量管理（脚本集成）

```
# 停止所有运行中的服务
droiddesk-tower service list --json | jq -r '.[] | select(.status == "running") | .name' | while read name; do
    droiddesk-tower service stop "$name"
done

# 检查所有服务是否健康
droiddesk-tower service list --json | jq '.[] | "\(.name): \(.status)"'
```

## 七、与 UI 的完整对应关系

| CLI 命令 | UI 对应功能 |
|----------|------------|
| `droiddesk-tower panel status` | 主面板状态显示 |
| `droiddesk-tower panel port` | 主面板端口修改输入框 |
| `droiddesk-tower ssh status/start/stop/restart` | SSH 模块开关按钮 |
| `droiddesk-tower ssh set-password` | SSH 账号密码输入框 + Save |
| `droiddesk-tower ssh set-port` | SSH 端口输入框 |
| `droiddesk-tower nginx status/start/stop/restart/reload` | Nginx 模块开关 / restart 按钮 |
| `droiddesk-tower service list` | 服务列表 |
| `droiddesk-tower service add` | 服务添加表单 |
| `droiddesk-tower service delete` | 服务删除按钮 |
| `droiddesk-tower service start/stop/restart` | 服务操作按钮 |
| `droiddesk-tower service status` | 服务状态/进程信息 |
| `droiddesk-tower service logs` | 服务日志查看器 |
| `tower-pm2 install/start/stop/restart/status` | droiddesk-tower-pm2 模块头部控制按钮 |
| `tower-pm2 list` | droiddesk-tower-pm2 服务列表表格 |
| `tower-pm2 add` | "+ 添加服务" 按钮 |
| `tower-pm2 remove` | 服务列表中的 delete 操作 |

## 八、目录结构

```
/etc/droiddesk-tower/
├── config.json          # 主配置文件
├── services.json        # 服务注册表
├── services/            # 各服务独立配置目录
│   ├── Toonflow管理页.json
│   └── Toonflow API.json
└── nginx/               # Nginx 反向代理配置目录
    └── droiddesk-tower_*.conf

/var/log/droiddesk-tower/
├── droiddesk-tower-pm2.log        # 守护进程日志
├── Toonflow管理页.out.log  # 服务 stdout 日志
└── Toonflow管理页.err.log  # 服务 stderr 日志

/var/run/
└── droiddesk-tower-pm2.sock       # Unix Socket 文件
```

## 九、droiddesk-tower service 与 tower-pm2 的关系说明

| 维度 | droiddesk-tower service | tower-pm2 |
|------|--------------|-----------|
| **定位** | 高层封装接口（用户日常使用） | 底层进程管理器（守护进程管理） |
| **管理对象** | 服务注册表 + 进程生命周期 | droiddesk-tower-pm2 守护进程 + 进程 |
| **自动依赖** | 自动确保 droiddesk-tower-pm2 运行中 | 不依赖其他模块 |
| **典型场景** | 日常启动/停止/重启服务 | 安装/卸载/重启守护进程 |
| **底层实现** | 调用 tower-pm2 的能力 | 直接操作 subprocess + psutil |

**使用建议：**

- 日常操作使用 `droiddesk-tower service` 即可，简洁方便
- 守护进程安装/卸载/故障恢复时使用 `droiddesk-tower pm2`
- 需要实时监控时使用 `tower-pm2 monit`
- 脚本集成时使用 `--json` 选项