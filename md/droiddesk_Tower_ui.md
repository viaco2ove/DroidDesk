# Droiddesk_Tower web ui 设计
参考宝塔 参考"宝塔，安全高效的服务器运维面板"
![img.png](img.png)
特点：单例，多个pid 看见都是同一个“Droiddesk Tower”
## open-server install（按钮）/intallled 【卸载】按钮
### Start SSH with Ubuntu 【开关按钮】
sshd will run as long as Ubuntu is
running   
### open-server [switch]
### ACCOUNT
Username [输入框，默认root]
Password [密码输入框]
SSH Port [输入框，默认8122]
【Save】
Credentials + SSH port applied inside Ubuntu on
save
## nginx
### nginx install（按钮）/intallled 【卸载】按钮
### Start nginx with Ubuntu 【开关按钮】
### nginx [switch]
### nginx [restart]

## service
add/delete
每个python服务：[name][path][start nginx with Ubuntu swtich] [keep live swtich]
例如 Toonflow 管理页
[Toonflow 管理页][/opt/toonflow/panel/start-panel.sh][start nginx with Ubuntu swtich] [keep live swtich][delete/stop/restart]

## tower pm2
【描述】 解决pm2 多pid 混乱的问题。
### tower pm2 install（按钮）/intallled 【卸载】按钮
### Start "tower pm2" with Ubuntu 【开关按钮】
### "tower pm2" [switch]
### "tower pm2" [restart]
###  service list
 id │ name             │ mode     │ ↺    │ status    │ cpu      │ memory   │funs│
├────┼──────────────────┼──────────┼──────┼───────────┼──────────┼──────────┤──────────┤
│ 0  │ toonflow-game    │ fork     │ 0    │ online    │ 0%       │ 1.2gb     │delete/stop/restart     │

【+ 添加服务】


# Droiddesk_Tower in  Droiddesk ui 设计
## Droiddesk_Tower install（按钮）/intallled 【卸载】按钮
### port [输入框,默认7088]
### Start Droiddesk_Tower  with Ubuntu 【开关按钮】
### Start Droiddesk_Tower 【switch】


## open-server install（按钮）/intallled 【卸载】按钮
### Start SSH with Ubuntu 【开关按钮】
sshd will run as long as Ubuntu is
running   
### open-server [switch]
### ACCOUNT
Username [输入框，默认root]
Password [密码输入框]
SSH Port [输入框，默认8122]
【Save】
Credentials + SSH port applied inside Ubuntu on
save
## nginx
### nginx install（按钮）/intallled 【卸载】按钮
### Start nginx with Ubuntu 【开关按钮】
### nginx [switch]
### nginx [restart]


## service
add/delete
每个python服务：[name][path][start nginx with Ubuntu swtich] [keep live swtich]
例如 Toonflow 管理页
[Toonflow 管理页][/opt/toonflow/panel/start-panel.sh][start nginx with Ubuntu swtich] [keep live swtich][delete/stop/restart]

## tower pm2
【描述】 解决pm2 多pid 混乱的问题。
### tower pm2 install（按钮）/intallled 【卸载】按钮
### Start "tower pm2" with Ubuntu 【开关按钮】
### "tower pm2" [switch]
### "tower pm2" [restart]
###  service list
 id │ name             │ mode     │ ↺    │ status    │ cpu      │ memory   │funs│
├────┼──────────────────┼──────────┼──────┼───────────┼──────────┼──────────┤──────────┤
│ 0  │ toonflow-game    │ fork     │ 0    │ online    │ 0%       │ 1.2gb     │delete/stop/restart     │

【+ 添加服务】