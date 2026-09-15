#!/system/bin/sh
PREFIX=/data/user/0/com.orailnoor.droiddesk/files/usr
export PATH=$PREFIX/bin:/system/bin
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=/data/user/0/com.orailnoor.droiddesk/files/tmp
# 启动 tower-pm2 在后台
rm -f /run/tower/tower-pm2.pid 2>/dev/null
setsid /system/bin/sh -c "/data/user/0/com.orailnoor.droiddesk/files/usr/bin/python3.14 /opt/droiddesk/tower/tower-pm2.py --port 7088 >>/var/log/tower/tower-pm2.log 2>&1" </dev/null &
sleep 3
echo === PID ===
cat /run/tower/tower-pm2.pid 2>&1
echo === LOG TAIL ===
tail -20 /var/log/tower/tower-pm2.log 2>&1
echo === API ===
curl -s -m 3 http://127.0.0.1:7088/api/system 2>&1