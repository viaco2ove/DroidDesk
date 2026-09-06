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
from http.server import HTTPServer, BaseHTTPRequestHandler
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

def stop_service(name: str, force: bool = False) -> dict:
    pid = read_pid(name)
    if not pid or not pid_exists(pid):
        clear_pid(name)
        with services_lock:
            services.pop(name, None)
        return {"ok": True, "message": "not running"}

    try:
        log.info("stopping: %s (PID %d, force=%s)", name, pid, force)
        sig = signal.SIGKILL if force else signal.SIGTERM
        os.kill(pid, sig)

        # 等待退出
        for i in range(20):   # 最多 10s
            if not pid_exists(pid):
                break
            time.sleep(0.5)

        if pid_exists(pid):
            os.kill(pid, signal.SIGKILL)
            time.sleep(0.5)

        clear_pid(name)
        with services_lock:
            services.pop(name, None)
        return {"ok": True, "message": "stopped"}
    except Exception as e:
        log.error("stop %s failed: %s", name, e)
        clear_pid(name)
        with services_lock:
            services.pop(name, None)
        return {"ok": False, "error": str(e)}

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

def refresh_all() -> None:
    """刷新所有服务状态"""
    cfg = load_config()
    svc_list = cfg.get("services", [])
    for svc in svc_list:
        name = svc.get("name", "")
        if name:
            refresh_service(name, svc)

# ── Web UI ─────────────────────────────────────────────────
def build_html() -> str:
    cfg = load_config()
    svc_list = cfg.get("services", [])
    refresh_all()

    rows = ""
    for svc in svc_list:
        name = svc.get("name", "")
        with services_lock:
            st = services.get(name, {})
        status = st.get("status", "unknown")
        pid     = st.get("pid", "—")
        cpu     = st.get("cpu", 0)
        mem     = st.get("mem", "—")
        uptime  = st.get("uptime", "—")
        rc      = st.get("restart_count", 0)
        kl      = svc.get("keep_live", False)
        sw      = svc.get("start_nginx_with_ubuntu", False)

        color = {"online": "#27c93f", "stopped": "#ff5f56", "crash_loop": "#ffbd2e"}.get(status, "#888")
        rows += f"""
        <tr>
          <td>{name}</td>
          <td><span class="dot" style="background:{color}"></span>{status}</td>
          <td>{pid if pid else '—'}</td>
          <td>{uptime}</td>
          <td>{cpu}%</td>
          <td>{mem}</td>
          <td>{rc}</td>
          <td>
            <button class="btn-icon" onclick="action('start','{name}')" title="启动">&#9654;</button>
            <button class="btn-icon" onclick="action('stop','{name}')"  title="停止">&#9632;</button>
            <button class="btn-icon" onclick="action('restart','{name}')" title="重启">&#8635;</button>
            <button class="btn-icon btn-del" onclick="action('delete','{name}')" title="删除">&#10005;</button>
          </td>
        </tr>"""

    return f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DroidDesk Tower — 安全高效的服务器运维面板</title>
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f0f2f5;color:#333}}
.header{{background:#2c3e50;color:#fff;padding:16px 24px;display:flex;align-items:center;gap:16px;flex-wrap:wrap}}
.header h1{{font-size:18px;font-weight:600}}
.header .badge{{background:#e95420;padding:2px 8px;border-radius:4px;font-size:12px}}
.toolbar{{padding:12px 24px;background:#fff;border-bottom:1px solid #e0e0e0;display:flex;gap:8px;flex-wrap:wrap;align-items:center}}
.toolbar input{{padding:6px 10px;border:1px solid #ccc;border-radius:4px;width:200px}}
.toolbar input:focus{{outline:none;border-color:#e95420}}
.btn{{padding:6px 14px;border:none;border-radius:4px;cursor:pointer;font-size:13px}}
.btn-primary{{background:#e95420;color:#fff}}
.btn-primary:hover{{background:#c44a1c}}
.btn-secondary{{background:#f0f0f0;color:#333}}
.btn-secondary:hover{{background:#e0e0e0}}
.main{{padding:16px 24px}}
table{{width:100%;border-collapse:collapse;background:#fff;border-radius:6px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08)}}
th{{background:#f8f9fa;padding:10px 12px;text-align:left;font-size:13px;color:#666;border-bottom:1px solid #eee}}
td{{padding:10px 12px;font-size:13px;border-bottom:1px solid #f5f5f5}}
tr:last-child td{{border-bottom:none}}
tr:hover td{{background:#fafafa}}
.dot{{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;vertical-align:middle}}
.btn-icon{{padding:4px 8px;margin:0 2px;border:1px solid #ddd;border-radius:4px;background:#fff;cursor:pointer;font-size:13px}}
.btn-icon:hover{{background:#f0f0f0}}
.btn-del{{color:#e95420}}
.btn-del:hover{{background:#fff0ee}}
.msg{{padding:10px 24px;font-size:13px;border-radius:4px;margin-bottom:12px;display:none}}
.msg.ok{{background:#d4edda;color:#155724;display:block}}
.msg.err{{background:#f8d7da;color:#721c24;display:block}}
.footer{{text-align:center;color:#999;font-size:12px;padding:16px}}
#addModal{{display:none;position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:100;align-items:center;justify-content:center}}
#addModal.show{{display:flex}}
.modal{{background:#fff;border-radius:8px;padding:24px;width:420px;max-width:95vw}}
.modal h3{{margin-bottom:16px;font-size:16px}}
.form-group{{margin-bottom:12px}}
.form-group label{{display:block;font-size:13px;color:#666;margin-bottom:4px}}
.form-group input[type=text]{{width:100%;padding:6px 10px;border:1px solid #ccc;border-radius:4px}}
.form-group input[type=checkbox]{{margin-right:4px}}
.form-row{{display:flex;gap:8px}}
</style>
</head>
<body>
<div class="header">
  <h1>DroidDesk Tower</h1>
  <span class="badge">安全高效的服务器运维面板</span>
  <span style="margin-left:auto;font-size:13px;opacity:.8">端口 {DEFAULT_PORT}</span>
</div>

<div class="toolbar">
  <button class="btn btn-primary" onclick="showAdd()">+ 添加服务</button>
  <button class="btn btn-secondary" onclick="action('refresh','')">刷新</button>
  <span style="margin-left:auto;font-size:13px;color:#888">
    {len(svc_list)} 服务 · {sum(1 for s in svc_list if services.get(s.get('name',''),{}).get('status')=='online')} 在线
  </span>
</div>

<div id="msg" class="msg"></div>

<div class="main">
<table>
<thead>
<tr>
  <th>名称</th><th>状态</th><th>PID</th><th>运行时长</th><th>CPU</th><th>内存</th><th>重启次数</th><th>操作</th>
</tr>
</thead>
<tbody>{rows if rows else '<tr><td colspan="8" style="text-align:center;color:#999;padding:24px">暂无服务，添加一个试试</td></tr>'}
</tbody>
</table>
</div>

<div id="addModal">
  <div class="modal">
    <h3>添加服务</h3>
    <div class="form-group"><label>服务名称</label><input id="m-name" placeholder="如：toonflow-game"></div>
    <div class="form-group"><label>启动命令</label><input id="m-cmd" placeholder="如：cd /opt/toonflow && python3 -m panel"></div>
    <div class="form-group"><label>工作目录</label><input id="m-cwd" placeholder="如：/opt/toonflow（默认 /）"></div>
    <div class="form-group form-row">
      <label><input type="checkbox" id="m-keep"> 保活（异常自动重启）</label>
    </div>
    <div class="form-group form-row">
      <label><input type="checkbox" id="m-nginx"> 随 Ubuntu 启动 Nginx</label>
    </div>
    <div style="margin-top:16px;display:flex;gap:8px">
      <button class="btn btn-primary" onclick="doAdd()">确认添加</button>
      <button class="btn btn-secondary" onclick="hideAdd()">取消</button>
    </div>
  </div>
</div>

<div class="footer">DroidDesk Tower · {datetime.now().strftime('%Y-%m-%d %H:%M')}</div>

<script>
let msgTimer=null;
function showMsg(txt,ok=true){{
  const el=document.getElementById('msg');
  el.textContent=txt; el.className='msg '+(ok?'ok':'err');
  clearTimeout(msgTimer); msgTimer=setTimeout(()=>{{el.style.display='none'}},4000);
}}
async function action(act,name){{
  const r=await fetch('/api/'+act+(name?'/'+name:''),{{method:'POST'}});
  const j=await r.json();
  showMsg(j.error||j.message||(j.ok?'操作成功':'操作失败'),j.ok);
  setTimeout(()=>location.reload(),600);
}}
function showAdd(){{document.getElementById('addModal').classList.add('show');}}
function hideAdd(){{document.getElementById('addModal').classList.remove('show');}}
async function doAdd(){{
  const name=document.getElementById('m-name').value.trim();
  const cmd=document.getElementById('m-cmd').value.trim();
  if(!name||!cmd){{showMsg('名称和命令不能为空',false);return;}}
  const r=await fetch('/api/add',{{
    method:'POST',
    headers:{{'Content-Type':'application/json'}},
    body:JSON.stringify({{
      name, cmd,
      cwd:document.getElementById('m-cwd').value.trim()||'/',
      keep_live:document.getElementById('m-keep').checked,
      start_nginx_with_ubuntu:document.getElementById('m-nginx').checked,
    }})
  }});
  const j=await r.json();
  if(j.ok){{hideAdd();showMsg('添加成功');setTimeout(()=>location.reload(),600);}}
  else showMsg(j.error||'添加失败',false);
}}
</script>
</body>
</html>"""

# ── HTTP Handler ───────────────────────────────────────────
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

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
            "stop":     lambda: stop_service(name)    if name else {"ok": False, "error": "name required"},
            "restart":  lambda: restart_service(name) if name else {"ok": False, "error": "name required"},
            "delete":   lambda: delete_service(name)  if name else {"ok": False, "error": "name required"},
            "refresh":  lambda: (refresh_all(), {"ok": True, "message": "refreshed"}),
            "add":      self._handle_add,
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
    try:
        srv = HTTPServer(("0.0.0.0", args.port), Handler)
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
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
        srv.shutdown()
        log.info("tower-pm2 exited.")

if __name__ == "__main__":
    main()
