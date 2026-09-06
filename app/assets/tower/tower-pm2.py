#!/usr/bin/env python3
"""
tower-pm2 — DroidDesk Tower 面板 (7088)
────────────────────────────────────────
四个模块：open-server(SSH) / nginx / Service / Tower PM2

架构（重要）：
- DroidDesk Tower 面板 = 本进程 (7088)，四个模块的宿主，装完即跑
- Service 模块   -> /etc/tower/services.json（业务服务注册表）
- Tower PM2 模块 -> /etc/tower/pm2.json（独立进程管理器列表，默认空）
  两者完全独立的注册表
- pm2 开关只启停 Tower PM2 模块，面板 (7088) 不受影响
- nginx 由 nginx 模块卡片直接管理，不属于任何服务列表
"""

import os
import sys
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
from urllib.parse import urlparse
from datetime import datetime

SVC_CONFIG     = "/etc/tower/services.json"
PM2_CONFIG     = "/etc/tower/pm2.json"
PM2_FLAG       = "/run/tower/pm2.enabled"
DEFAULT_PIDDIR = "/run/tower"
DEFAULT_LOGDIR = "/var/log/tower"
DEFAULT_PORT   = 7088
INSTANCE_LOCK  = f"{DEFAULT_PIDDIR}/tower-pm2.pid"

os.makedirs(DEFAULT_PIDDIR, exist_ok=True)
os.makedirs(DEFAULT_LOGDIR, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(f"{DEFAULT_LOGDIR}/tower-pm2.log", encoding="utf-8"),
        logging.StreamHandler(sys.stderr),
    ],
)
log = logging.getLogger("tower-pm2")


class Registry:
    def __init__(self, path: str, label: str):
        self.path = path
        self.label = label
        self.state: dict = {}
        self.user_stopped: set = set()

REG_SVC = Registry(SVC_CONFIG, "Service")
REG_PM2 = Registry(PM2_CONFIG, "TowerPM2")

services_lock = threading.Lock()
shutdown_event = threading.Event()


def load_cfg(path: str) -> dict:
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        log.error("load %s failed: %s", path, e)
        return {}


def save_cfg(path: str, cfg: dict) -> bool:
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        log.error("save %s failed: %s", path, e)
        return False


def _svc_list(reg: Registry) -> list:
    return load_cfg(reg.path).get("services", [])


def _find_cfg(reg: Registry, name: str):
    return next((s for s in _svc_list(reg) if s.get("name") == name), None)


def read_pid(name: str):
    try:
        pid = int((Path(DEFAULT_PIDDIR) / f"{name}.pid").read_text().strip())
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
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except (OSError, ProcessLookupError):
        return False


def cmdline_of(pid: int) -> str:
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            return f.read().replace(b"\x00", b" ").decode("utf-8", "replace").strip()
    except Exception:
        return ""


def _cpu(pid: int) -> float:
    try:
        return round(psutil.Process(pid).cpu_percent(interval=0.05), 1)
    except Exception:
        return 0.0


def _mem(pid: int) -> str:
    try:
        mb = psutil.Process(pid).memory_info().rss / 1024 / 1024
        return f"{mb/1024:.1f}GB" if mb >= 1024 else f"{mb:.0f}MB"
    except Exception:
        return "0MB"


def _uptime(pid: int) -> str:
    try:
        secs = time.time() - psutil.Process(pid).create_time()
        h, r = divmod(int(secs), 3600)
        m, s = divmod(r, 60)
        return f"{h}h{m}m"
    except Exception:
        return ""


def refresh_service(reg: Registry, name: str) -> dict:
    svc_cfg = _find_cfg(reg, name)
    keep_live = (svc_cfg or {}).get("keep_live", False)
    pid = read_pid(name)
    running = pid_exists(pid) if pid else False
    with services_lock:
        if running:
            reg.state[name] = {
                "pid": pid, "status": "online",
                "cpu": _cpu(pid), "mem": _mem(pid), "uptime": _uptime(pid),
                "restart_count": reg.state.get(name, {}).get("restart_count", 0),
                "last_restart": reg.state.get(name, {}).get("last_restart", ""),
                "keep_live": keep_live,
            }
        else:
            reg.state[name] = {
                "pid": None, "status": "stopped",
                "cpu": 0, "mem": "0MB", "uptime": "",
                "restart_count": reg.state.get(name, {}).get("restart_count", 0),
                "last_restart": reg.state.get(name, {}).get("last_restart", ""),
                "keep_live": keep_live,
            }
    return reg.state[name]


def refresh_all(reg: Registry) -> None:
    for s in _svc_list(reg):
        if s.get("name"):
            refresh_service(reg, s["name"])


def _find_children(pid: int) -> list:
    kids = []
    try:
        with open(f"/proc/{pid}/task/{pid}/children") as f:
            for tok in f.read().split():
                cp = int(tok)
                kids.append(cp)
                kids.extend(_find_children(cp))
    except Exception:
        pass
    return kids


def _match_pids(reg: Registry, name: str) -> list:
    svc_cfg = _find_cfg(reg, name) or {}
    cmd = svc_cfg.get("cmd", "")
    key = None
    for tok in cmd.replace("NODE_ENV=local", "").split():
        if "/" in tok and not tok.startswith("-"):
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
            if cl.startswith("/bin/sh -c") or cl.startswith("sh -c"):
                continue
            if "tower-pm2" in cl:
                continue
            found.append(pid)
    except Exception:
        pass
    return found


RESTART_WINDOW = 300
MAX_RESTARTS = 5


def start_service(reg: Registry, name: str) -> dict:
    svc_cfg = _find_cfg(reg, name)
    if not svc_cfg:
        return {"ok": False, "error": f"service '{name}' not found in {reg.path}"}

    pid = read_pid(name)
    if pid and pid_exists(pid):
        return {"ok": False, "error": f"already running (PID {pid})"}

    cmd = svc_cfg.get("cmd", "")
    cwd = svc_cfg.get("cwd", "/")
    log_out = svc_cfg.get("stdout_log", f"{DEFAULT_LOGDIR}/{name}.out.log")
    log_err = svc_cfg.get("stderr_log", f"{DEFAULT_LOGDIR}/{name}.err.log")
    os.makedirs(os.path.dirname(log_out), exist_ok=True)

    reg.user_stopped.discard(name)

    try:
        log.info("[%s] starting: %s  cmd=%s", reg.label, name, cmd)
        proc = subprocess.Popen(
            cmd, shell=True, cwd=cwd,
            stdout=open(log_out, "a"), stderr=open(log_err, "a"),
            start_new_session=True,
        )
        write_pid(name, proc.pid)
        with services_lock:
            reg.state[name] = {
                "pid": proc.pid, "status": "online",
                "cpu": 0, "mem": "0MB", "uptime": "0h0m",
                "restart_count": 0,
                "last_restart": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "keep_live": svc_cfg.get("keep_live", False),
            }
        return {"ok": True, "pid": proc.pid, "status": "online"}
    except Exception as e:
        log.error("[%s] start %s failed: %s", reg.label, name, e)
        return {"ok": False, "error": str(e)}


def stop_service(reg: Registry, name: str, force: bool = False) -> dict:
    reg.user_stopped.add(name)
    with services_lock:
        reg.state[name] = {**reg.state.get(name, {}), "status": "stopped", "pid": None}

    victims = set()
    pid = read_pid(name)
    if pid and pid_exists(pid):
        victims.add(pid)
        victims.update(_find_children(pid))
    victims.update(_match_pids(reg, name))

    if not victims:
        clear_pid(name)
        with services_lock:
            reg.state.pop(name, None)
        return {"ok": True, "message": "not running"}

    sig = signal.SIGKILL if force else signal.SIGTERM
    log.info("[%s] stopping: %s force=%s pids=%s", reg.label, name, force, sorted(victims))
    for v in victims:
        try:
            os.kill(v, sig)
        except Exception:
            pass

    deadline = time.time() + 6
    while time.time() < deadline:
        if not any(pid_exists(v) for v in victims):
            break
        time.sleep(0.5)

    victims.update(_match_pids(reg, name))
    for v in list(victims):
        if pid_exists(v):
            try:
                os.kill(v, signal.SIGKILL)
            except Exception:
                pass
            try:
                os.killpg(os.getpgid(v), signal.SIGKILL)
            except Exception:
                pass

    for _ in range(3):
        time.sleep(1)
        for v in _match_pids(reg, name):
            try:
                os.kill(v, signal.SIGKILL)
            except Exception:
                pass

    clear_pid(name)
    with services_lock:
        reg.state.pop(name, None)
    return {"ok": True, "message": "stopped"}


def restart_service(reg: Registry, name: str) -> dict:
    stop_service(reg, name, force=True)
    time.sleep(1)
    return start_service(reg, name)


def add_service(reg: Registry, payload: dict) -> dict:
    name = payload.get("name", "").strip()
    cmd = payload.get("cmd", "").strip() or payload.get("path", "").strip()
    if not name or not cmd:
        return {"ok": False, "error": "name and cmd/path are required"}
    cfg = load_cfg(reg.path)
    svc_list = cfg.get("services", [])
    if any(s.get("name") == name for s in svc_list):
        return {"ok": False, "error": f"service '{name}' already exists"}
    svc_list.append({
        "name": name,
        "cmd": cmd,
        "cwd": payload.get("cwd", "/"),
        "stdout_log": f"{DEFAULT_LOGDIR}/{name}.out.log",
        "stderr_log": f"{DEFAULT_LOGDIR}/{name}.err.log",
        "keep_live": payload.get("keep_live", False),
        "start_with_os": payload.get("start_with_os", False),
        "start_nginx_with_ubuntu": payload.get("start_nginx_with_ubuntu", False),
    })
    cfg["services"] = svc_list
    if not save_cfg(reg.path, cfg):
        return {"ok": False, "error": "failed to write config"}
    return {"ok": True, "name": name}


def delete_service(reg: Registry, name: str) -> dict:
    stop_service(reg, name, force=True)
    cfg = load_cfg(reg.path)
    svc_list = cfg.get("services", [])
    before = len(svc_list)
    svc_list = [s for s in svc_list if s.get("name") != name]
    if len(svc_list) == before:
        return {"ok": False, "error": f"service '{name}' not found"}
    cfg["services"] = svc_list
    if not save_cfg(reg.path, cfg):
        return {"ok": False, "error": "failed to write config"}
    return {"ok": True, "message": f"service '{name}' deleted"}


def _watch(reg: Registry, restart_times: dict) -> None:
    for svc in _svc_list(reg):
        name = svc.get("name", "")
        if not svc.get("keep_live", False):
            continue
        if name in reg.user_stopped:
            continue
        pid = read_pid(name)
        if pid and pid_exists(pid):
            continue
        now = time.time()
        times = [t for t in restart_times.get(name, []) if now - t < RESTART_WINDOW]
        if len(times) >= MAX_RESTARTS:
            log.warning("[%s] crash_loop detected for %s", reg.label, name)
            with services_lock:
                reg.state[name] = {
                    "pid": None, "status": "crash_loop",
                    "cpu": 0, "mem": "0MB", "uptime": "",
                    "restart_count": len(times),
                    "last_restart": reg.state.get(name, {}).get("last_restart", ""),
                    "keep_live": True,
                }
            continue
        log.warning("[%s] service %s died, auto-restarting...", reg.label, name)
        times.append(now)
        restart_times[name] = times
        start_service(reg, name)


def watchdog_loop() -> None:
    rt_svc = {}
    rt_pm2 = {}
    while not shutdown_event.is_set():
        time.sleep(5)
        _watch(REG_SVC, rt_svc)
        if pm2_enabled():
            _watch(REG_PM2, rt_pm2)


def pm2_enabled() -> bool:
    return Path(PM2_FLAG).exists()


def pm2_enable() -> dict:
    Path(PM2_FLAG).parent.mkdir(parents=True, exist_ok=True)
    Path(PM2_FLAG).write_text(str(os.getpid()))
    started = []
    for svc in _svc_list(REG_PM2):
        if svc.get("start_with_os") and svc.get("name"):
            start_service(REG_PM2, svc["name"])
            started.append(svc["name"])
    log.info("PM2 module enabled, autostarted: %s", started)
    return {"ok": True, "message": "tower-pm2 enabled", "started": started}


def pm2_disable() -> dict:
    stopped = []
    for svc in _svc_list(REG_PM2):
        n = svc.get("name", "")
        if n:
            stop_service(REG_PM2, n, force=True)
            stopped.append(n)
    try:
        Path(PM2_FLAG).unlink(missing_ok=True)
    except Exception:
        pass
    log.info("PM2 module disabled, stopped: %s", stopped)
    return {"ok": True, "message": "tower-pm2 disabled", "stopped": stopped}


def pm2_restart_daemon() -> dict:
    pm2_disable()
    time.sleep(1)
    return pm2_enable()


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


def _ssh_port_alive(port=None, timeout: float = 1.5) -> bool:
    port = port or _read_ssh_port()
    try:
        c = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        c.close()
        return True
    except Exception:
        return False


def _ssh_status() -> dict:
    return {"ok": True, "running": _ssh_port_alive(), "port": _read_ssh_port(), "user": "root"}


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
        if r.returncode != 0:
            return {"ok": False, "error": f"chpasswd failed: {r.stderr}"}
        msgs.append("password updated")
    port_changed = False
    if port and str(port).isdigit():
        p = int(port)
        if p != _read_ssh_port():
            if not _write_ssh_port(p):
                return {"ok": False, "error": "failed to write sshd_config"}
            port_changed = True
            msgs.append(f"port set to {p}")
    if port_changed and _ssh_port_alive():
        subprocess.Popen("pkill -x sshd; sleep 1; mkdir -p /run/sshd && setsid nohup /usr/sbin/sshd >/dev/null 2>&1 &", shell=True)
        msgs.append("sshd restarting for new port")
    return {"ok": True, "message": "; ".join(msgs) or "nothing to update"}


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


def _system_uptime() -> str:
    try:
        with open("/proc/uptime") as f:
            secs = float(f.read().split()[0])
        h, r = divmod(int(secs), 3600)
        m, s = divmod(r, 60)
        return f"{h}h {m}m"
    except Exception:
        return "-"


def acquire_lock() -> bool:
    Path(INSTANCE_LOCK).parent.mkdir(parents=True, exist_ok=True)
    Path(INSTANCE_LOCK).write_text(str(os.getpid()))
    return True


def release_lock() -> None:
    try:
        Path(INSTANCE_LOCK).unlink(missing_ok=True)
    except Exception:
        pass


def on_signal(sig, frame) -> None:
    log.info("received signal %d, shutting down...", sig)
    shutdown_event.set()


_UI_CACHE = None

def build_html() -> str:
    """Web UI 从 /opt/droiddesk/tower/ui.html 读取（与主程序分离，方便热更）"""
    global _UI_CACHE
    if _UI_CACHE is None:
        try:
            _UI_CACHE = open("/opt/droiddesk/tower/ui.html", encoding="utf-8").read()
        except Exception:
            _UI_CACHE = "<h1>DroidDesk Tower</h1><p>ui.html missing</p>"
    return _UI_CACHE


class Handler(BaseHTTPRequestHandler):
    timeout = 30

    def log_message(self, fmt, *args):
        pass

    def send_json(self, data: dict, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def _read_body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", 0))
            return json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        except Exception:
            return {}

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/index.html", "/ui"):
            html = build_html()
            data = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", len(data))
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/api/list":
            refresh_all(REG_SVC)
            with services_lock:
                self.send_json({"ok": True, "services": dict(REG_SVC.state)})
            return
        if path == "/api/pm2/status":
            refresh_all(REG_PM2)
            with services_lock:
                self.send_json({"ok": True, "enabled": pm2_enabled(),
                                "services": dict(REG_PM2.state)})
            return
        if path == "/api/ssh/status":
            self.send_json(_ssh_status())
            return
        if path == "/api/nginx/status":
            self.send_json(_nginx_status())
            return
        if path == "/api/system":
            try:
                mem = psutil.virtual_memory()
                disk = psutil.disk_usage("/")
                load = os.getloadavg() if hasattr(os, "getloadavg") else (0, 0, 0)
                self.send_json({
                    "ok": True,
                    "cpu_cores": psutil.cpu_count(),
                    "loadavg": list(load),
                    "mem_total_gb": round(mem.total / 1024**3, 1),
                    "mem_used_gb": round(mem.used / 1024**3, 1),
                    "mem_pct": mem.percent,
                    "disk_total_gb": round(disk.total / 1024**3, 1),
                    "disk_used_gb": round(disk.used / 1024**3, 1),
                    "uptime": _system_uptime(),
                })
            except Exception as e:
                self.send_json({"ok": False, "error": str(e)})
            return
        self.send_json({"ok": False, "error": "not found"}, 404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        parts = [p for p in path.split("/") if p]
        act = parts[1] if len(parts) > 1 else ""
        name = parts[2] if len(parts) > 2 else ""

        if act == "pm2":
            sub = name
            target = parts[3] if len(parts) > 3 else ""
            handlers = {
                "status": lambda: {"ok": True, "enabled": pm2_enabled(), "services": dict(REG_PM2.state)},
                "enable": pm2_enable,
                "disable": pm2_disable,
                "restart-daemon": pm2_restart_daemon,
                "start": lambda: start_service(REG_PM2, target) if target else {"ok": False, "error": "name required"},
                "stop": lambda: stop_service(REG_PM2, target, force=True) if target else {"ok": False, "error": "name required"},
                "restart": lambda: restart_service(REG_PM2, target) if target else {"ok": False, "error": "name required"},
                "delete": lambda: delete_service(REG_PM2, target) if target else {"ok": False, "error": "name required"},
                "add": lambda: add_service(REG_PM2, self._read_body()),
            }
            result = handlers.get(sub, lambda: {"ok": False, "error": f"unknown pm2 action: {sub}"})()
            self.send_json(result)
            return

        handlers = {
            "start": lambda: start_service(REG_SVC, name) if name else {"ok": False, "error": "name required"},
            "stop": lambda: stop_service(REG_SVC, name, force=True) if name else {"ok": False, "error": "name required"},
            "restart": lambda: restart_service(REG_SVC, name) if name else {"ok": False, "error": "name required"},
            "delete": lambda: delete_service(REG_SVC, name) if name else {"ok": False, "error": "name required"},
            "refresh": lambda: (refresh_all(REG_SVC), {"ok": True, "message": "refreshed"})[1],
            "add": lambda: add_service(REG_SVC, self._read_body()),
            "ssh-start": lambda: _ssh_toggle(True),
            "ssh-stop": lambda: _ssh_toggle(False),
            "ssh-set-cred": lambda: _ssh_set_cred(self),
            "nginx-start": lambda: _nginx_toggle(True),
            "nginx-stop": lambda: _nginx_toggle(False),
            "nginx-restart": lambda: _nginx_restart(),
        }
        result = handlers.get(act, lambda: {"ok": False, "error": f"unknown action: {act}"})()
        self.send_json(result)


def main() -> None:
    ap = argparse.ArgumentParser(description="tower-pm2 - DroidDesk Tower panel")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = ap.parse_args()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    if not acquire_lock():
        sys.exit(1)

    for svc in _svc_list(REG_SVC):
        if svc.get("keep_live") and svc.get("name"):
            pid = read_pid(svc["name"])
            if not (pid and pid_exists(pid)):
                log.info("restore keep_live service: %s", svc["name"])
                start_service(REG_SVC, svc["name"])

    if pm2_enabled():
        for svc in _svc_list(REG_PM2):
            if svc.get("start_with_os") and svc.get("name"):
                pid = read_pid(svc["name"])
                if not (pid and pid_exists(pid)):
                    log.info("restore pm2 service: %s", svc["name"])
                    start_service(REG_PM2, svc["name"])

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
        log.info("port %d in use (%s), another tower-pm2 is running, exiting", args.port, e)
        release_lock()
        return
    finally:
        log.info("tower-pm2 stopping all services...")
        shutdown_event.set()
        t.join(timeout=5)
        release_lock()
        if srv:
            srv.shutdown()
        log.info("tower-pm2 exited.")


if __name__ == "__main__":
    main()
