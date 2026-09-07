# PC 上（PowerShell），把三个文件推上去
cd D:\Users\viaco\PycharmProjects\TermuxPilot\android\DroidDesk\app\assets\tower
scp -P 8122 tower-pm2.py droiddesk-tower tower-pm2 root@192.168.31.66:/opt/droiddesk/tower/

# 加执行权限并重启面板守护（/usr/local/bin 的符号链接已指向这里，无需重链）
ssh -p 8122 root@192.168.31.66 "chmod +x /opt/droiddesk/tower/tower-pm2.py /opt/droiddesk/tower/droiddesk-tower /opt/droiddesk/tower/tower-pm2; bash /opt/droiddesk/tower/tower-stop; sleep 1; bash /opt/droiddesk/tower/tower-start"

# 容器里重新删除（注意服务名只有一个，末尾不要多打"页"）
droiddesk-tower service delete Toonflow管理页