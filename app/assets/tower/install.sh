#!/bin/bash
# install.sh — 一键安装 DroidDesk Tower（tower-pm2）
# 运行方式: bash install.sh [端口 默认7088]
set -euo pipefail

PORT="${1:-7088}"
PDIR="/opt/droiddesk/tower"
CONF="/etc/tower"
RUNDIR="/run/tower"
LOGDIR="/var/log/tower"

echo "=== DroidDesk Tower 安装脚本 ==="
echo "端口: $PORT"
echo ""

# ── 1. 清理旧的 tower/agent 文件（防止旧版本残留）─────────
echo "[1/5] 清理旧版本..."
rm -rf "$PDIR" "$CONF" "$RUNDIR"
mkdir -p "$PDIR" "$CONF" "$RUNDIR" "$LOGDIR"

# ── 2. 写入 tower-pm2.py ─────────────────────────────────
echo "[2/5] 写入 tower-pm2.py ..."
if [ -f "$(dirname "$0")/tower-pm2.py" ]; then
    cp "$(dirname "$0")/tower-pm2.py" "$PDIR/tower-pm2.py"
elif [ -f "/data/local/tmp/tower-pm2.py" ]; then
    cp /data/local/tmp/tower-pm2.py "$PDIR/tower-pm2.py"
else
    echo "ERROR: tower-pm2.py not found in $(dirname "$0")"
    exit 1
fi
chmod +x "$PDIR/tower-pm2.py"

# ── 3. 写入 services.json ─────────────────────────────────
echo "[3/5] 写入 services.json ..."
if [ -f "$(dirname "$0")/services.json" ]; then
    cp "$(dirname "$0")/services.json" "$CONF/services.json"
elif [ -f "/data/local/tmp/services.json" ]; then
    cp /data/local/tmp/services.json "$CONF/services.json"
else
    # 生成默认配置
    cat > "$CONF/services.json" <<'CONFJSON'
{
  "services": [
    {
      "name": "toonflow-game",
      "cmd": "NODE_ENV=local node /opt/toonflow/toonflow-game-app/build/app.js",
      "cwd": "/opt/toonflow/toonflow-game-app",
      "stdout_log": "/var/log/tower/toonflow-game.out.log",
      "stderr_log": "/var/log/tower/toonflow-game.err.log",
      "keep_live": true,
      "start_with_os": false,
      "start_nginx_with_ubuntu": false
    },
    {
      "name": "nginx",
      "cmd": "nginx",
      "cwd": "/",
      "stdout_log": "/var/log/tower/nginx.out.log",
      "stderr_log": "/var/log/tower/nginx.err.log",
      "keep_live": true,
      "start_with_os": false,
      "start_nginx_with_ubuntu": false
    }
  ]
}
CONFJSON
fi

# ── 4. 写入 shell 脚本 ─────────────────────────────────────
echo "[4/5] 写入管理脚本 ..."
for script in tower-start tower-stop tower-list tower-pm2 droiddesk-tower; do
    if [ -f "$(dirname "$0")/$script" ]; then
        cp "$(dirname "$0")/$script" "$PDIR/$script"
        chmod +x "$PDIR/$script"
    fi
done

# ── 4.5 安装全局 CLI 命令 ───────────────────────────────────
# droiddesk-tower / tower-pm2 放到 /usr/local/bin，任何 proot 实例内都可直接调用
echo "[4.5/5] 安装全局 CLI (droiddesk-tower, tower-pm2)..."
mkdir -p /usr/local/bin
for cli in droiddesk-tower tower-pm2; do
    if [ -f "$PDIR/$cli" ]; then
        ln -sf "$PDIR/$cli" "/usr/local/bin/$cli"
        chmod +x "/usr/local/bin/$cli"
    fi
done

# ── 5. 启动 tower-pm2 ──────────────────────────────────────
echo "[5/5] 启动 tower-pm2 ..."
cd "$PDIR"
bash tower-start "$PORT"

echo ""
echo "=== 安装完成 ==="
echo "Web UI:  http://127.0.0.1:$PORT"
echo "PID 文件: $RUNDIR/tower-pm2.pid"
echo "服务配置: $CONF/services.json"
echo ""
echo "常用命令:"
echo "  droiddesk-tower panel status            # 面板状态"
echo "  droiddesk-tower service list            # 服务列表"
echo "  droiddesk-tower service start <name>    # 启动服务"
echo "  tower-pm2 list                          # pm2 风格列表"
echo "  tower-pm2 monit                         # 实时监控"
echo "  bash $PDIR/tower-start                  # 启动面板（低层）"
echo "  bash $PDIR/tower-stop                   # 停止面板（低层）"