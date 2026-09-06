#!/usr/bin/env python3
"""
tower-pm2 — DroidDesk Tower 轻量安全高效的服务器运维面板（单实例守护进程）
─────────────────────────────────────────────────────────────
- 一个服务 = 一个 PID，绝不多开
- /opt/droiddesk/tower/tower-pm2.py        主程序（常驻）
- /etc/tower/services.json                 服务注册表
- /run/tower/tower-pm2.pid                 自身 PID 文件
- /run/tower/<service>.pid                 各服务 PID 文件
- 监听 HTTP 7088（Web UI + REST API）
- keep_live 自动重启（防抖：5 分钟内重启超 5 次则 crash_loop）
"""

import os
import sys
import re
import json
import time
import signal
import socket
import argparse
import subprocess
import logging
import threading
import psutil
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime

# ── 全局常量 ───────────────────────────────────────────────
DEFAULT_CONFIG = "/etc/tower/services.json"
DEFAULT_PIDDIR = "/run/tower"
DEFAULT_LOGDIR = "/var/log/tower"
DEFAULT_PORT   = 7088
INSTANCE_LOCK  = f"{DEFAULT_PIDDIR}/tower-pm2.pid"

# ── 路径初始化 ─────────────────────────────────────────────
os.makedirs(DEFAULT_PIDDIR, exist_ok=True)
os.makedirs(DEFAULT_LOGDIR, exist_ok=True)

# ── 日志配置 ───────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(f"{DEFAULT_LOGDIR}/tower-pm2.log", encoding="utf-8"),
        logging.StreamHandler(sys.stderr),
    ],
)
log = logging.getLogger("tower-pm2")

# ── 全局状态 ───────────────────────────────────────────────
services: dict = {}          # name -> { pid, status, restart_count, last_restart, ... }
services_lock = threading.Lock()
shutdown_event = threading.Event()
user_stopped: set = set()    # 用户显式 stop 的服务名 —— keep_live 看门狗不拉起这些

# ── 配置读写 ───────────────────────────────────────────────
def load_config() -> dict:
    if not os.path.exists(DEFAULT_CONFIG):
        return {}
    try:
        with open(DEFAULT_CONFIG, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        log.error("load config failed: %s", e)
        return {}

def save_config(cfg: dict) -> bool:
    try:
        os.makedirs(os.path.dirname(DEFAULT_CONFIG), exist_ok=True)
        with open(DEFAULT_CONFIG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        log.error("save config failed: %s", e)
        return False

# ── PID 工具 ───────────────────────────────────────────────
def read_pid(name: str) -> int | None:
    """读取服务 PID 文件，返回 int 或 None（文件不存在/无效）"""
    pid_file = Path(DEFAULT_PIDDIR) / f"{name}.pid"
    try:
        pid = int(pid_file.read_text().strip())
        return pid if pid > 0 else None
    except Exception:
        return None

def write_pid(name: str, pid: int) -> None:
    Path(DEFAULT_PIDDIR, f"{name}.pid").write_text(str(pid))

def clear_pid(name: str) -> None:
    try:
        Path(DEFAULT_PIDDIR, f"{name}.pid").unlink(missing_ok=True)
    except Exception:
        pass

def pid_exists(pid: int) -> bool:
    """检查 PID 是否存在（跨 namespace 安全：proot 共享宿主 PID）"""
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True   # EPERM = 存在但无权
    except (OSError, ProcessLookupError):
        return False   # ESRCH 或其他 = 不存在

def cmdline_of(pid: int) -> str:
    """读取 /proc/<pid>/cmdline"""
    try:
        with open(f"/proc/{pid}/cmdline", "r") as f:
            return f.read().replace("\x00", " ").strip()
    except Exception:
        return ""

# ── 服务状态 ───────────────────────────────────────────────
def refresh_service(name: str, svc: dict) -> dict:
    """更新单个服务状态，返回更新后的 dict"""
    pid = read_pid(name)
    running = pid_exists(pid) if pid else False
    cfg = load_config()
    svc_list = cfg.get("services", [])

    # 找到配置中的 keep_live
    keep_live = False
    for s in svc_list:
        if s.get("name") == name:
            keep_live = s.get("keep_live", False)
            break

    # 进程已死但标记 running → 更新状态
    with services_lock:
        if running:
            services[name] = {
                "pid": pid,
                "status": "online",
                "cpu": _cpu(pid),
                "mem": _mem(pid),
                "uptime": _uptime(pid),
                "restart_count": services.get(name, {}).get("restart_count", 0),
                "last_restart": services.get(name, {}).get("last_restart", ""),
                "keep_live": keep_live,
            }
        else:
            services[name] = {
                "pid": None,
                "status": "stopped",
                "cpu": 0,
                "mem": 0,
                "uptime": "",
                "restart_count": services.get(name, {}).get("restart_count", 0),
                "last_restart": services.get(name, {}).get("last_restart", ""),
                "keep_live": keep_live,
            }
    return services[name]

def _cpu(pid: int) -> float:
    try:
        p = psutil.Process(pid)
        return round(p.cpu_percent(interval=0.1), 1)
    except Exception:
        return 0.0

def _mem(pid: int) -> str:
    try:
        p = psutil.Process(pid)
        mb = p.memory_info().rss / 1024 / 1024
        if mb >= 1024:
            return f"{mb/1024:.1f}GB"
        return f"{mb:.0f}MB"
    except Exception:
        return "0MB"

def _uptime(pid: int) -> str:
    try:
        p = psutil.Process(pid)
        secs = time.time() - p.create_time()
        h, r = divmod(int(secs), 3600)
        m, s = divmod(r, 60)
        return f"{h}h{m}m"
    except Exception:
        return ""

# ── 服务操作 ───────────────────────────────────────────────
RESTART_WINDOW = 300   # 5 分钟防抖窗口
MAX_RESTARTS   = 5

def start_service(name: str) -> dict:
    cfg = load_config()
    svc_list = cfg.get("services", [])
    svc_cfg = next((s for s in svc_list if s.get("name") == name), None)
    if not svc_cfg:
        return {"ok": False, "error": f"service '{name}' not found in {DEFAULT_CONFIG}"}

    pid = read_pid(name)
    if pid and pid_exists(pid):
        return {"ok": False, "error": f"already running (PID {pid})"}

    cmd   = svc_cfg.get("cmd", "")
    cwd   = svc_cfg.get("cwd", "/")
    log_out = svc_cfg.get("stdout_log", f"{DEFAULT_LOGDIR}/{name}.out.log")
    log_err = svc_cfg.get("stderr_log", f"{DEFAULT_LOGDIR}/{name}.err.log")
    os.makedirs(os.path.dirname(log_out), exist_ok=True)

    # 显式 start 清除用户停止意图，看门狗恢复保护
    user_stopped.discard(name)

    try:
        log.info("starting: %s  cmd=%s", name, cmd)
        proc = subprocess.Popen(
            cmd,
            shell=True,
            cwd=cwd,
            stdout=open(log_out, "a"),
            stderr=open(log_err, "a"),
            start_new_session=True,
        )
        write_pid(name, proc.pid)
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with services_lock:
            services[name] = {
                "pid": proc.pid,
                "status": "online",
                "cpu": 0, "mem": "0MB", "uptime": "0h0m",
                "restart_count": 0,
                "last_restart": now,
                "keep_live": svc_cfg.get("keep_live", False),
            }
        return {"ok": True, "pid": proc.pid, "status": "online"}
    except Exception as e:
        log.error("start %s failed: %s", name, e)
        return {"ok": False, "error": str(e)}

def _find_service_children(pid: int) -> list:
    """递归收集 pid 的所有后代（读 /proc/<pid>/task/*/children）"""
    kids = []
    try:
        with open(f"/proc/{pid}/task/{pid}/children") as f:
            for tok in f.read().split():
                cp = int(tok)
                kids.append(cp)
                kids.extend(_find_service_children(cp))
    except Exception:
        pass
    return kids


def _match_service_pids(name: str, svc_cmd: str) -> list:
    """兜底：在 /proc 里按命令特征找该服务的所有进程（含历史孤儿）。
    判定标准：cmdline 含 cmd 的核心特征（如 build/app.js 路径），
    且不是 shell wrapper（/bin/sh -c）、不是 tower-pm2 自己。
    """
    # 取 cmd 里的路径特征（最后一个带 / 的参数）
    key = None
    for tok in svc_cmd.split():
        if "/" in tok and not tok.startswith("-") and not tok.startswith("NODE_ENV"):
            key = tok
    if not key:
        return []
    found = []
    try:
        for d in Path("/proc").iterdir():
            if not d.name.isdigit():
                continue
            pid = int(d.name)
            if pid == os.getpid():
                continue
            cl = cmdline_of(pid)
            if not cl or key not in cl:
                continue
            # 排除 sh -c wrapper（它的 cmdline 同时含 key 和 "-c"）
            if cl.strip().startswith("/bin/sh -c") or cl.strip().startswith("sh -c"):
                continue
            # 排除 tower-pm2 自身相关
            if "tower-pm2" in cl:
                continue
            found.append(pid)
    except Exception:
        pass
    return found


def stop_service(name: str, force: bool = False) -> dict:
    """停服务：杀整个进程树 + /proc 特征兜底扫孤儿。
    只杀 PID 文件里那个 shell wrapper 会让 node 子进程被孤立 —— 这是
    「杀一个又冒一个、怎么都关不掉」的根因。
    """
    cfg = load_config()
    svc_cfg = next((s for s in cfg.get("services", []) if s.get("name") == name), None)
    svc_cmd = (svc_cfg or {}).get("cmd", "")

    # 1) 登记用户停止意图（看门狗不拉起）+ 更新状态
    user_stopped.add(name)
    with services_lock:
        services[name] = {**services.get(name, {}), "status": "stopped", "pid": None}

    # 2) 收集要杀的 PID：PID 文件 + 其后代 + /proc 特征匹配的孤儿
    victims = set()
    pid = read_pid(name)
    if pid and pid_exists(pid):
        victims.add(pid)
        victims.update(_find_service_children(pid))
    victims.update(_match_service_pids(name, svc_cmd))

    if not victims:
        clear_pid(name)
        with services_lock:
            services.pop(name, None)
        return {"ok": True, "message": "not running"}

    sig = signal.SIGKILL if force else signal.SIGTERM
    log.info("stopping: %s force=%s pids=%s", name, force, sorted(victims))
    for v in victims:
        try:
            os.kill(v, sig)
        except Exception:
            pass

    # 3) 等待退出（最多 6s），仍活着就 SIGKILL
    deadline = time.time() + 6
    while time.time() < deadline:
        alive = [v for v in victims if pid_exists(v)]
        if not alive:
            break
        time.sleep(0.5)
    # 第二轮：把幸存者 + 新扫出的孤儿（含 node cluster worker）全部 SIGKILL
    victims.update(_match_service_pids(name, svc_cmd))
    for v in list(victims):
        if pid_exists(v):
            try:
                os.kill(v, signal.SIGKILL)
            except Exception:
                pass
            # 同时杀进程组（start_new_session 使 wrapper 为组长，cluster worker 在同组）
            try:
                os.killpg(os.getpgid(v), signal.SIGKILL)
            except Exception:
                pass
    # 第三轮：多次扫描漏网的（杀组后可能残留的独立 worker / 延迟孤儿）
    for _ in range(3):
        time.sleep(1)
        for v in _match_service_pids(name, svc_cmd):
            try:
                os.kill(v, signal.SIGKILL)
            except Exception:
                pass

    clear_pid(name)
    with services_lock:
        services.pop(name, None)
    return {"ok": True, "message": "stopped"}

def restart_service(name: str) -> dict:
    stop_service(name, force=True)
    time.sleep(1)
    return start_service(name)

def add_service(payload: dict) -> dict:
    """添加/更新服务配置"""
    name = payload.get("name", "").strip()
    cmd  = payload.get("cmd", "").strip()
    if not name or not cmd:
        return {"ok": False, "error": "name and cmd are required"}

    cfg = load_config()
    svc_list = cfg.get("services", [])
    # 避免重名
    if any(s.get("name") == name for s in svc_list):
        return {"ok": False, "error": f"service '{name}' already exists"}

    svc_list.append({
        "name":              name,
        "cmd":               cmd,
        "cwd":               payload.get("cwd", "/"),
        "stdout_log":        payload.get("stdout_log", f"{DEFAULT_LOGDIR}/{name}.out.log"),
        "stderr_log":        payload.get("stderr_log", f"{DEFAULT_LOGDIR}/{name}.err.log"),
        "keep_live":         payload.get("keep_live", False),
        "start_with_os":     payload.get("start_with_os", False),
        "start_nginx_with_ubuntu": payload.get("start_nginx_with_ubuntu", False),
    })
    cfg["services"] = svc_list
    if not save_config(cfg):
        return {"ok": False, "error": "failed to write config"}
    return {"ok": True, "name": name}

def delete_service(name: str) -> dict:
    """删除服务（先停，再删配置）"""
    stop_service(name, force=True)
    cfg = load_config()
    svc_list = cfg.get("services", [])
    before = len(svc_list)
    svc_list = [s for s in svc_list if s.get("name") != name]
    if len(svc_list) == before:
        return {"ok": False, "error": f"service '{name}' not found"}
    cfg["services"] = svc_list
    if not save_config(cfg):
        return {"ok": False, "error": "failed to write config"}
    return {"ok": True, "message": f"service '{name}' deleted"}

# ── 巡检循环 ───────────────────────────────────────────────
def watchdog_loop() -> None:
    """后台线程：keep_live 自动重启 + crash_loop 检测"""
    restart_times: dict[str, list] = {}   # name -> [timestamp, ...]

    while not shutdown_event.is_set():
        time.sleep(5)
        cfg = load_config()
        svc_list = cfg.get("services", [])

        for svc in svc_list:
            name = svc.get("name", "")
            if not svc.get("keep_live", False):
                continue
            # 用户显式 stop 过的服务：看门狗绝不拉起（直到显式 start）
            if name in user_stopped:
                continue

            pid = read_pid(name)
            running = pid_exists(pid) if pid else False

            if not running:
                # 统计这个窗口内重启次数
                now = time.time()
                times = restart_times.get(name, [])
                times = [t for t in times if now - t < RESTART_WINDOW]
                restart_times[name] = times

                if len(times) >= MAX_RESTARTS:
                    log.warning("crash_loop detected for %s (%d restarts in %ds)", name, len(times), RESTART_WINDOW)
                    with services_lock:
                        services[name] = {
                            "pid": None, "status": "crash_loop",
                            "cpu": 0, "mem": "0MB", "uptime": "",
                            "restart_count": len(times),
                            "last_restart": services.get(name, {}).get("last_restart", ""),
                            "keep_live": True,
                        }
                    continue

                # 自动重启
                log.warning("service %s died, auto-restarting...", name)
                times.append(now)
                restart_times[name] = times
                start_service(name)

def adopt_orphans() -> None:
    """认领孤儿进程：PID 文件丢失/损坏时，通过 cmd 特征在 /proc 里找回服务进程。
    解决多容器/多实例竞争导致的 PID 文件与服务进程失联问题（pid 一致性原则）。
    """
    cfg = load_config()
    for svc in cfg.get("services", []):
        name = svc.get("name", "")
        if not name:
            continue
        pid = read_pid(name)
        if pid and pid_exists(pid):
            continue  # PID 文件正常，跳过
        # PID 文件缺失或进程已死 → 扫描 /proc 按命令行特征匹配
        cfg_cmd = svc.get("cmd", "")
        if not cfg_cmd:
            continue
        # 取命令的核心特征（最后一个路径段 + 关键参数）
        parts = cfg_cmd.replace("NODE_ENV=local ", "").split()
        key = None
        for p in parts:
            if "/" in p and not p.startswith("-"):
                key = p
        if not key:
            continue
        found = None
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            cmdline = cmdline_of(int(proc_dir.name))
            if cmdline and key in cmdline and "tower-pm2.py" not in cmdline:
                found = int(proc_dir.name)
                break
        if found:
            log.info("adopted orphan: %s -> PID %d", name, found)
            write_pid(name, found)


def refresh_all() -> None:
    """刷新所有服务状态"""
    adopt_orphans()
    cfg = load_config()
    svc_list = cfg.get("services", [])
    for svc in svc_list:
        name = svc.get("name", "")
        if name:
            refresh_service(name, svc)

# ── Web UI ─────────────────────────────────────────────────
def build_html() -> str:
    """宝塔风格 Web UI：open-server / nginx / service / tower-pm2 四模块"""
    refresh_all()
    return _UI_TEMPLATE


_UI_TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DroidDesk Tower</title>
<style>
:root{--bg:#f4f6f9;--card:#fff;--txt:#333;--sub:#8a94a6;--bd:#e6eaf0;--pri:#20a0ff;--ok:#27c93f;--warn:#ffbd2e;--err:#ff5f56;--orange:#e95420}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;background:var(--bg);color:var(--txt);font-size:14px}
.header{background:linear-gradient(135deg,#243447,#2c3e50);color:#fff;padding:14px 22px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
.header h1{font-size:18px;font-weight:600;letter-spacing:.5px}
.header .badge{background:var(--orange);padding:2px 10px;border-radius:10px;font-size:12px}
.sysbar{display:flex;gap:18px;margin-left:auto;font-size:12px;opacity:.9;flex-wrap:wrap}
.sysbar b{color:#7fd0ff;font-weight:600}
.wrap{max-width:1080px;margin:16px auto;padding:0 16px;display:grid;gap:16px}
.card{background:var(--card);border-radius:10px;box-shadow:0 1px 4px rgba(0,0,0,.06);overflow:hidden}
.card>.hd{padding:12px 18px;border-bottom:1px solid var(--bd);display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.card>.hd h2{font-size:15px;font-weight:600;display:flex;align-items:center;gap:8px}
.card>.hd .sp{margin-left:auto;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.card>.bd{padding:16px 18px}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px}
.dot.on{background:var(--ok);box-shadow:0 0 6px var(--ok)}
.dot.off{background:#c3cbd6}
.sw{position:relative;display:inline-block;width:44px;height:24px}
.sw input{opacity:0;width:0;height:0}
.sl{position:absolute;cursor:pointer;inset:0;background:#c3cbd6;border-radius:24px;transition:.25s}
.sl:before{content:"";position:absolute;height:18px;width:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.25s}
input:checked+.sl{background:var(--pri)}
input:checked+.sl:before{transform:translateX(20px)}
.sw.disabled{opacity:.5;pointer-events:none}
.btn{padding:6px 14px;border:none;border-radius:5px;cursor:pointer;font-size:13px;background:#eef1f5;color:#444}
.btn:hover{background:#e2e7ee}
.btn-pri{background:var(--pri);color:#fff}
.btn-ok{background:var(--ok);color:#fff}
.btn-warn{background:var(--warn);color:#fff}
.btn-err{background:var(--err);color:#fff}
.btn-sm{padding:4px 10px;font-size:12px}
.btn:disabled{opacity:.5;cursor:not-allowed}
.field{display:flex;flex-direction:column;gap:4px}
.field label{font-size:12px;color:var(--sub)}
.field input{padding:7px 10px;border:1px solid var(--bd);border-radius:5px;font-size:13px;min-width:130px}
.field input:focus{outline:none;border-color:var(--pri)}
.formrow{display:flex;gap:14px;flex-wrap:wrap;align-items:flex-end}
table{width:100%;border-collapse:collapse}
th{background:#f8fafc;padding:9px 12px;text-align:left;font-size:12px;color:var(--sub);border-bottom:1px solid var(--bd);white-space:nowrap}
td{padding:9px 12px;font-size:13px;border-bottom:1px solid #f2f5f8;white-space:nowrap}
tr:last-child td{border-bottom:none}
tbody tr:hover td{background:#fafcfe}
.empty{text-align:center;color:var(--sub);padding:26px}
#toast{position:fixed;top:18px;left:50%;transform:translateX(-50%);padding:10px 22px;border-radius:6px;color:#fff;font-size:13px;display:none;z-index:99;box-shadow:0 4px 14px rgba(0,0,0,.2)}
#toast.ok{background:#27c93f}#toast.err{background:#ff5f56}
#modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:90;align-items:center;justify-content:center}
#modal.show{display:flex}
.mbox{background:#fff;border-radius:10px;padding:22px;width:430px;max-width:92vw}
.mbox h3{font-size:15px;margin-bottom:14px}
.mbox .field{margin-bottom:12px}
.mbox .field input{width:100%}
.mfoot{display:flex;gap:10px;justify-content:flex-end;margin-top:16px}
.chk{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;margin:8px 0}
.chk label{display:flex;align-items:center;gap:5px;cursor:pointer}
.sec-desc{font-size:12px;color:var(--sub);margin-top:2px}
@media(max-width:640px){.sysbar{display:none}.field input{min-width:100px}}
</style>
</head>
<body>
<div class="header">
  <h1>DroidDesk Tower</h1><span class="badge">安全高效的服务器运维面板</span>
  <div class="sysbar" id="sysbar"></div>
</div>

<div class="wrap">

<div class="card">
  <div class="hd"><h2><span class="dot" id="ssh-dot"></span>Open-Server (SSH)</h2>
    <div class="sp">
      <span id="ssh-port-badge" style="font-size:12px;color:var(--sub)"></span>
      <label class="sw"><input type="checkbox" id="ssh-sw" onchange="sshToggle(this.checked)"><span class="sl"></span></label>
    </div>
  </div>
  <div class="bd">
    <div class="sec-desc">sshd will run as long as Ubuntu is running</div>
    <div style="height:12px"></div>
    <div class="formrow">
      <div class="field"><label>Username</label><input id="ssh-user" value="root"></div>
      <div class="field"><label>Password</label><input id="ssh-pass" type="password" placeholder="********"></div>
      <div class="field"><label>SSH Port</label><input id="ssh-port" value="8122" style="width:90px"></div>
      <button class="btn btn-pri" onclick="sshSave()">Save</button>
    </div>
    <div class="sec-desc" style="margin-top:8px">Credentials + SSH port applied inside Ubuntu on save</div>
  </div>
</div>

<div class="card">
  <div class="hd"><h2><span class="dot" id="ng-dot"></span>Nginx</h2>
    <div class="sp">
      <button class="btn btn-sm" onclick="ngAct('nginx-restart')">Restart</button>
      <label class="sw"><input type="checkbox" id="ng-sw" onchange="ngAct(this.checked?'nginx-start':'nginx-stop')"><span class="sl"></span></label>
    </div>
  </div>
  <div class="bd"><div class="sec-desc">Nginx Web 服务器 - 反向代理 / 静态站点</div></div>
</div>

<div class="card">
  <div class="hd"><h2><span class="dot" id="sv-dot"></span>Service</h2>
    <div class="sp"><button class="btn btn-pri btn-sm" onclick="showAdd()">+ 添加服务</button></div>
  </div>
  <div class="bd" style="padding:0;overflow-x:auto">
    <table>
      <thead><tr><th>name</th><th>path</th><th>start nginx</th><th>keep live</th><th>status</th><th style="text-align:right">funs</th></tr></thead>
      <tbody id="svc-body"><tr><td colspan="6" class="empty">loading...</td></tr></tbody>
    </table>
  </div>
</div>

<div class="card">
  <div class="hd"><h2><span class="dot on" id="pm2-dot"></span>Tower PM2</h2>
    <div class="sp">
      <button class="btn btn-sm" onclick="pm2Restart()">Restart</button>
      <label class="sw"><input type="checkbox" id="pm2-sw" checked onchange="pm2Toggle(this.checked)"><span class="sl"></span></label>
    </div>
  </div>
  <div class="bd">
    <div class="sec-desc">解决 pm2 多 pid 混乱的问题 - 单例守护，多入口看到的进程列表完全一致</div>
    <div style="height:12px"></div>
    <table>
      <thead><tr><th>id</th><th>name</th><th>mode</th><th>&#8635;</th><th>status</th><th>cpu</th><th>memory</th><th style="text-align:right">funs</th></tr></thead>
      <tbody id="pm2-body"><tr><td colspan="8" class="empty">loading...</td></tr></tbody>
    </table>
  </div>
</div>

</div>

<div id="toast"></div>

<div id="modal"><div class="mbox">
  <h3>添加服务</h3>
  <div class="field"><label>name（服务名）</label><input id="m-name" placeholder="如 Toonflow 管理页"></div>
  <div class="field"><label>path（启动脚本/命令）</label><input id="m-path" placeholder="/opt/toonflow/panel/start-panel.sh"></div>
  <div class="chk">
    <label><input type="checkbox" id="m-nginx"> start nginx with Ubuntu</label>
    <label><input type="checkbox" id="m-keep" checked> keep live</label>
  </div>
  <div class="mfoot">
    <button class="btn" onclick="hideAdd()">取消</button>
    <button class="btn btn-pri" onclick="doAdd()">添加</button>
  </div>
</div></div>

<script>
let toastTimer=null;
function toast(msg,ok=true){
  const el=document.getElementById('toast');
  el.textContent=msg; el.className=ok?'ok':'err'; el.style.display='block';
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.style.display='none',2600);
}
async function api(path,method='GET',body=null){
  try{
    const opt={method,headers:{'Content-Type':'application/json'}};
    if(body)opt.body=JSON.stringify(body);
    const r=await fetch(path,opt);
    return await r.json();
  }catch(e){return{ok:false,error:String(e)}}
}
async function refresh(){
  const[ssh,ng,list,sys]=await Promise.all([
    api('/api/ssh/status'),api('/api/nginx/status'),api('/api/list'),api('/api/system')
  ]);
  document.getElementById('ssh-sw').checked=!!ssh.running;
  document.getElementById('ssh-port').value=ssh.port||8122;
  setDot('ssh-dot',ssh.running);
  document.getElementById('ssh-port-badge').textContent='port '+(ssh.port||8122);
  document.getElementById('ng-sw').checked=!!ng.running;
  setDot('ng-dot',ng.running);
  renderSvc(list.services||{});
  renderPm2(list.services||{});
  document.getElementById('pm2-sw').checked=true;
  setDot('pm2-dot',true);
  if(sys.ok){
    document.getElementById('sysbar').innerHTML=
      '<span>load <b>'+(sys.loadavg[0]||0).toFixed(1)+'</b></span>'+
      '<span>mem <b>'+sys.mem_pct+'%</b></span>'+
      '<span>disk <b>'+Math.round(sys.disk_used_gb)+'/'+Math.round(sys.disk_total_gb)+'GB</b></span>'+
      '<span>up <b>'+sys.uptime+'</b></span>';
  }
}
function setDot(id,on){const el=document.getElementById(id);el.className='dot '+(on?'on':'off')}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function renderSvc(svc){
  const tb=document.getElementById('svc-body');
  const names=Object.keys(svc).filter(function(n){return n!=='sshd'});
  if(!names.length){tb.innerHTML='<tr><td colspan="6" class="empty">暂无服务，点击右上角 + 添加</td></tr>';return}
  tb.innerHTML=names.map(function(n){
    const s=svc[n];
    const on=s.status==='online';
    return '<tr>'+
      '<td><b>'+esc(n)+'</b></td>'+
      '<td style="color:var(--sub);max-width:260px;overflow:hidden;text-overflow:ellipsis">'+esc(s.cmd||'')+'</td>'+
      '<td>'+(s.start_nginx_with_ubuntu?'yes':'-')+'</td>'+
      '<td>'+(s.keep_live?'yes':'-')+'</td>'+
      '<td><span class="dot '+(on?'on':'off')+'"></span>'+esc(s.status)+'</td>'+
      '<td style="text-align:right">'+
        '<button class="btn btn-sm '+(on?'':'btn-ok')+'" onclick="svcAct(\''+esc(n)+'\',\''+(on?'stop':'start')+'\')">'+(on?'stop':'start')+'</button>'+
        '<button class="btn btn-sm" onclick="svcAct(\''+esc(n)+'\',\'restart\')">restart</button>'+
        '<button class="btn btn-sm btn-err" onclick="svcDel(\''+esc(n)+'\')">delete</button>'+
      '</td></tr>';
  }).join('');
}
function renderPm2(svc){
  const tb=document.getElementById('pm2-body');
  const names=Object.keys(svc).sort();
  if(!names.length){tb.innerHTML='<tr><td colspan="8" class="empty">无受管进程</td></tr>';return}
  tb.innerHTML=names.map(function(n,i){
    const s=svc[n];
    const on=s.status==='online';
    return '<tr>'+
      '<td>'+i+'</td><td><b>'+esc(n)+'</b></td><td>fork</td><td>'+(s.restart_count||0)+'</td>'+
      '<td><span class="dot '+(on?'on':'off')+'"></span>'+(on?'online':'stopped')+'</td>'+
      '<td>'+(s.cpu||0)+'%</td><td>'+esc(s.mem||'-')+'</td>'+
      '<td style="text-align:right">'+
        '<button class="btn btn-sm" onclick="svcAct(\''+esc(n)+'\',\'restart\')">&#8635;</button>'+
        '<button class="btn btn-sm '+(on?'':'btn-ok')+'" onclick="svcAct(\''+esc(n)+'\',\''+(on?'stop':'start')+'\')">'+(on?'&#9632;':'&#9654;')+'</button>'+
        '<button class="btn btn-sm btn-err" onclick="svcDel(\''+esc(n)+'\')">&#10005;</button>'+
      '</td></tr>';
  }).join('');
}
async function sshToggle(on){
  const r=await api('/api/'+(on?'ssh-start':'ssh-stop'),'POST');
  toast(r.ok?(on?'sshd started':'sshd stopped'):r.error,r.ok);
  refresh();
}
async function sshSave(){
  const r=await api('/api/ssh-set-cred','POST',{
    user:document.getElementById('ssh-user').value,
    password:document.getElementById('ssh-pass').value,
    port:document.getElementById('ssh-port').value
  });
  document.getElementById('ssh-pass').value='';
  toast(r.ok?(r.message||'saved'):r.error,r.ok);
  refresh();
}
async function ngAct(act){
  const r=await api('/api/'+act,'POST');
  toast(r.ok?(r.message||'done'):r.error,r.ok);
  refresh();
}
async function svcAct(name,act){
  const r=await api('/api/'+act+'/'+encodeURIComponent(name),'POST');
  toast(r.ok?(name+' '+act+' ok'):r.error,r.ok);
  setTimeout(refresh,600);
}
async function svcDel(name){
  if(!confirm('删除服务 '+name+'？进程将被停止并移除注册'))return;
  const r=await api('/api/delete/'+encodeURIComponent(name),'POST');
  toast(r.ok?(name+' deleted'):r.error,r.ok);
  setTimeout(refresh,600);
}
async function pm2Toggle(on){
  if(on){ toast('tower-pm2 already running'); return; }
  if(!confirm('停止 Tower PM2？所有受管服务（含 toonflow-game）将被停止')){ refresh(); return; }
  toast('stopping tower-pm2...');
  await api('/api/pm2-stop','POST');
  // 服务端会杀掉自身，页面 1.2s 后跳到提示页
  setTimeout(()=>{ document.body.innerHTML='<div style="padding:40px;text-align:center;color:#888">Tower PM2 已停止。<br><br><a href="/" style="color:#20a0ff">重新启动面板</a>（或执行 bash /opt/droiddesk/tower/tower-start 7088）</div>'; },1200);
}
async function pm2Restart(){
  const r=await api('/api/refresh','POST');
  toast('tower-pm2 refreshed',r.ok);
  refresh();
}
function showAdd(){document.getElementById('modal').classList.add('show')}
function hideAdd(){document.getElementById('modal').classList.remove('show')}
async function doAdd(){
  const name=document.getElementById('m-name').value.trim();
  const path=document.getElementById('m-path').value.trim();
  if(!name||!path){toast('name 和 path 必填',false);return}
  const r=await api('/api/add','POST',{
    name,path,
    keep_live:document.getElementById('m-keep').checked,
    start_nginx_with_ubuntu:document.getElementById('m-nginx').checked
  });
  if(r.ok){hideAdd();toast('已添加 '+name);setTimeout(refresh,600)}
  else toast(r.error,false);
}
refresh();
setInterval(refresh,5000);
</script>
</body>
</html>
"""

class Handler(BaseHTTPRequestHandler):
    # HTTP/1.0 (默认): 短连接, 每 POST 完整收发后关闭 —— 规避 proot 下
    # HTTP/1.1 keep-alive 的 POST body/Content-Length 状态错乱
    timeout = 30
    timeout = 30  # 单请求最长 30s，防止阻塞线程池

    def log_message(self, fmt, *args):
        pass  # 静默，默认会 print 到 stderr

    def send_json(self, data: dict, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/index.html", "/ui"):
            html = build_html()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", len(html.encode("utf-8")))
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))
            return
        if path == "/api/list":
            refresh_all()
            with services_lock:
                self.send_json({"ok": True, "services": dict(services)})
            return
        if path == "/api/ssh/status":
            self.send_json(_ssh_status())
            return
        if path == "/api/nginx/status":
            self.send_json(_nginx_status())
            return
        if path == "/api/system":
            try:
                cpu_cores = psutil.cpu_count()
                mem = psutil.virtual_memory()
                disk = psutil.disk_usage("/")
                load = os.getloadavg() if hasattr(os, "getloadavg") else (0, 0, 0)
                self.send_json({
                    "ok": True,
                    "cpu_cores": cpu_cores,
                    "loadavg": list(load),
                    "mem_total_gb": round(mem.total / 1024**3, 1),
                    "mem_used_gb":  round(mem.used  / 1024**3, 1),
                    "mem_pct": mem.percent,
                    "disk_total_gb": round(disk.total / 1024**3, 1),
                    "disk_used_gb":  round(disk.used  / 1024**3, 1),
                    "uptime": _system_uptime(),
                })
            except Exception as e:
                self.send_json({"ok": False, "error": str(e)})
            return
        self.send_json({"ok": False, "error": "not found"}, 404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        # /api/<action>[/<name>][/<sub>]
        parts = [p for p in path.split("/") if p]
        act = parts[1] if len(parts) > 1 else ""
        name = parts[2] if len(parts) > 2 else ""
        _ = parts[3] if len(parts) > 3 else ""

        handlers = {
            "start":    lambda: start_service(name)   if name else {"ok": False, "error": "name required"},
            "stop":     lambda: stop_service(name, force=True)    if name else {"ok": False, "error": "name required"},
            "restart":  lambda: restart_service(name) if name else {"ok": False, "error": "name required"},
            "delete":   lambda: delete_service(name)  if name else {"ok": False, "error": "name required"},
            "refresh":  lambda: (refresh_all(), {"ok": True, "message": "refreshed"}),
            "add":      self._handle_add,
            # ssh 模块
            "ssh-start":    lambda: _ssh_toggle(True),
            "ssh-stop":     lambda: _ssh_toggle(False),
            "ssh-set-cred": lambda: _ssh_set_cred(self),
            # nginx 模块
            "nginx-start":   lambda: _nginx_toggle(True),
            "nginx-stop":    lambda: _nginx_toggle(False),
            "nginx-restart": lambda: _nginx_restart(),
            # tower-pm2 自身停止（Web UI 开关用）
            "pm2-stop":      lambda: _pm2_self_stop(),
        }

        if act in handlers:
            result = handlers[act]()
        else:
            result = {"ok": False, "error": f"unknown action: {act}"}

        self.send_json(result)

    def _handle_add(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8")
            payload = json.loads(body) if body else {}
            return add_service(payload)
        except Exception as e:
            return {"ok": False, "error": str(e)}

# ── 系统信息 ───────────────────────────────────────────────
def _system_uptime() -> str:
    try:
        with open("/proc/uptime") as f:
            secs = float(f.read().split()[0])
        h, r = divmod(int(secs), 3600)
        m, s = divmod(r, 60)
        return f"{h}h {m}m"
    except Exception:
        return "—"

# ── SSH 模块（open-server）─────────────────────────────────
def _read_ssh_port() -> int:
    try:
        with open("/etc/ssh/sshd_config", encoding="utf-8") as f:
            for line in f:
                if line.strip().startswith("Port "):
                    return int(line.split()[1])
    except Exception:
        pass
    return 8122

def _write_ssh_port(port: int) -> bool:
    try:
        p = Path("/etc/ssh/sshd_config")
        lines = p.read_text(encoding="utf-8").splitlines(keepends=True)
        found = False
        out = []
        for line in lines:
            if line.strip().startswith("Port ") and not line.strip().startswith("#"):
                out.append(f"Port {port}\n")
                found = True
            else:
                out.append(line)
        if not found:
            out.append(f"Port {port}\n")
        p.write_text("".join(out), encoding="utf-8")
        return True
    except Exception:
        return False

def _ssh_port_alive(port: int = None, timeout: float = 1.5) -> bool:
    import socket as _s
    port = port or _read_ssh_port()
    try:
        c = _s.create_connection(("127.0.0.1", port), timeout=timeout)
        c.close()
        return True
    except Exception:
        return False

def _ssh_status() -> dict:
    return {
        "ok": True,
        "running": _ssh_port_alive(),
        "port": _read_ssh_port(),
        "user": "root",
    }

def _ssh_toggle(start: bool) -> dict:
    if start:
        if _ssh_port_alive():
            return {"ok": True, "message": "sshd already running"}
        subprocess.Popen(
            "mkdir -p /run/sshd && setsid nohup /usr/sbin/sshd >/dev/null 2>&1 &",
            shell=True, start_new_session=True,
        )
        time.sleep(1.5)
        if _ssh_port_alive():
            return {"ok": True, "message": "sshd started"}
        return {"ok": False, "error": "sshd failed to start"}
    else:
        subprocess.Popen("pkill -x sshd 2>/dev/null; true", shell=True)
        time.sleep(1)
        return {"ok": True, "message": "sshd stopped"}

def _ssh_set_cred(handler) -> dict:
    try:
        length = int(handler.headers.get("Content-Length", 0))
        body = json.loads(handler.rfile.read(length).decode("utf-8")) if length else {}
    except Exception as e:
        return {"ok": False, "error": str(e)}
    user = body.get("user", "root").strip() or "root"
    pwd = body.get("password", "").strip()
    port = body.get("port")
    msgs = []
    if pwd:
        r = subprocess.run(f"echo '{user}:{pwd}' | chpasswd", shell=True, capture_output=True, text=True)
        msgs.append("password updated" if r.returncode == 0 else f"chpasswd failed: {r.stderr}")
        if r.returncode != 0:
            return {"ok": False, "error": msgs[-1]}
    port_changed = False
    if port and str(port).isdigit():
        p = int(port)
        if p != _read_ssh_port():
            if not _write_ssh_port(p):
                return {"ok": False, "error": "failed to write sshd_config"}
            port_changed = True
            msgs.append(f"port set to {p}")
    if port_changed and _ssh_port_alive():
        # 端口变更需重启 sshd 生效
        subprocess.Popen("pkill -x sshd; sleep 1; mkdir -p /run/sshd && setsid nohup /usr/sbin/sshd >/dev/null 2>&1 &", shell=True)
        msgs.append("sshd restarting for new port")
    return {"ok": True, "message": "; ".join(msgs) or "nothing to update"}

# ── Nginx 模块 ─────────────────────────────────────────────
def _nginx_running() -> bool:
    r = subprocess.run("pgrep -x nginx", shell=True, capture_output=True, text=True)
    return bool(r.stdout.strip())

def _nginx_status() -> dict:
    return {"ok": True, "running": _nginx_running()}

def _nginx_toggle(start: bool) -> dict:
    if start:
        if _nginx_running():
            return {"ok": True, "message": "nginx already running"}
        subprocess.Popen("setsid nohup nginx >/dev/null 2>&1 &", shell=True, start_new_session=True)
        time.sleep(1.5)
        if _nginx_running():
            return {"ok": True, "message": "nginx started"}
        return {"ok": False, "error": "nginx failed to start (check config: nginx -t)"}
    else:
        subprocess.run("nginx -s stop 2>/dev/null || pkill -x nginx", shell=True, capture_output=True)
        time.sleep(1)
        return {"ok": True, "message": "nginx stopped"}

def _nginx_restart() -> dict:
    subprocess.run("nginx -s stop 2>/dev/null || pkill -x nginx", shell=True, capture_output=True)
    time.sleep(1)
    subprocess.Popen("setsid nohup nginx >/dev/null 2>&1 &", shell=True, start_new_session=True)
    time.sleep(1.5)
    if _nginx_running():
        return {"ok": True, "message": "nginx restarted"}
    return {"ok": False, "error": "nginx restart failed"}

def _pm2_self_stop() -> dict:
    """Web UI 开关：停所有受管服务（含孤儿）后延迟自杀。
    自杀放到后台线程 —— 先把 HTTP 响应发回客户端。
    """
    def _do():
        cfg = load_config()
        for svc in cfg.get("services", []):
            n = svc.get("name", "")
            if n:
                user_stopped.add(n)
                stop_service(n, force=True)
        release_lock()
        time.sleep(1.0)   # 给响应留时间
        log.info("tower-pm2 self-stop via Web UI switch")
        os.kill(os.getpid(), signal.SIGTERM)
    threading.Thread(target=_do, daemon=True).start()
    return {"ok": True, "message": "tower-pm2 stopping"}

# ── 单实例锁 ───────────────────────────────────────────────
def acquire_lock() -> bool:
    """单实例锁：基于 TCP 端口探测（proot 下最可靠）
    真正的检查由 tower-start 脚本做（端口监听判断）。
    这里只写 PID 文件用于关联与 kill。
    """
    Path(INSTANCE_LOCK).parent.mkdir(parents=True, exist_ok=True)
    Path(INSTANCE_LOCK).write_text(str(os.getpid()))
    return True

def release_lock() -> None:
    try:
        Path(INSTANCE_LOCK).unlink(missing_ok=True)
    except Exception:
        pass

# ── 信号处理 ───────────────────────────────────────────────
def on_signal(sig, frame) -> None:
    log.info("received signal %d, shutting down...", sig)
    shutdown_event.set()

# ── 主入口 ─────────────────────────────────────────────────
def main() -> None:
    global DEFAULT_CONFIG
    ap = argparse.ArgumentParser(
        description="tower-pm2 — DroidDesk Tower 轻量安全高效的服务器运维面板",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""示例:
  python3 tower-pm2.py                        # 前台运行（调试）
  nohup python3 tower-pm2.py --port 7088 &   # 后台运行

  API:
    GET  /                     Web UI
    GET  /api/list             列出所有服务
    GET  /api/system           系统资源
    POST /api/start/<name>     启动服务
    POST /api/stop/<name>      停止服务
    POST /api/restart/<name>   重启服务
    POST /api/delete/<name>    删除服务
    POST /api/add              添加服务（body: JSON）
    POST /api/refresh          刷新状态
"""
    )
    ap.add_argument("--port",   type=int, default=DEFAULT_PORT,   help=f"HTTP 监听端口（默认 {DEFAULT_PORT}）")
    ap.add_argument("--config", default=DEFAULT_CONFIG,            help=f"services.json 路径")
    args = ap.parse_args()

    DEFAULT_CONFIG = args.config

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT,  on_signal)

    if not acquire_lock():
        sys.exit(1)

    # 启动时恢复 keep_live 服务
    cfg = load_config()
    for svc in cfg.get("services", []):
        name = svc.get("name", "")
        if name and svc.get("keep_live", False):
            pid = read_pid(name)
            if not (pid and pid_exists(pid)):
                log.info("restore keep_live service: %s", name)
                start_service(name)

    # 启动 watchdog 线程
    t = threading.Thread(target=watchdog_loop, daemon=True)
    t.start()

    log.info("tower-pm2 listening on http://0.0.0.0:%d", args.port)
    srv = None
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
        srv.daemon_threads = True
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    except OSError as e:
        # 端口已被占用：说明已有 tower-pm2 在跑，本实例静默退出（exit 0）
        # 这是多容器并发启动时的正常竞争，失败方不应报错
        log.info("port %d in use (%s), another tower-pm2 is running, exiting", args.port, e)
        release_lock()
        return
    finally:
        log.info("tower-pm2 stopping all services...")
        shutdown_event.set()
        t.join(timeout=5)
        cfg2 = load_config()
        for svc in cfg2.get("services", []):
            name = svc.get("name", "")
            if name:
                stop_service(name, force=True)
        release_lock()
        if srv:
            srv.shutdown()
        log.info("tower-pm2 exited.")

if __name__ == "__main__":
    main()
