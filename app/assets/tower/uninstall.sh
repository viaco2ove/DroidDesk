#!/bin/bash
# uninstall.sh — 卸载 DroidDesk Tower（保留业务进程和配置）
# 运行方式: bash uninstall.sh
set -euo pipefail

PDIR="/opt/droiddesk/tower"
CONF="/etc/tower"
RUNDIR="/run/tower"
LOGDIR="/var/log/tower"

echo "=== DroidDesk Tower 卸载脚本 ==="

# ── 1. 停止 tower-pm2 ───────────────────────────────────
echo "[1/4] 停止 tower-pm2 ..."
if [ -f "$PDIR/tower-stop" ]; then
    bash "$PDIR/tower-stop" 2>/dev/null || true
fi

# ── 2. 清理 tower 程序目录 ────────────────────────────────
echo "[2/4] 清理程序目录 ..."
rm -rf "$PDIR" "$RUNDIR"

# ── 3. 清理日志 ──────────────────────────────────────────
echo "[3/4] 清理日志文件 ..."
rm -f "$LOGDIR"/*.log 2>/dev/null || true

# ── 4. 清理配置（可选）──────────────────────────────────
echo "[4/4] 清理配置文件 ..."
rm -rf "$CONF"

echo ""
echo "=== 卸载完成 ==="
echo "已清理: $PDIR  $CONF  $RUNDIR  $LOGDIR"
echo "业务进程（如 toonflow-game/sshd/nginx）未受影响。"
echo "如需重新安装，请重新运行 install.sh"