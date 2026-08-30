import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'dart:async';

class UbuntuConsoleScreen extends StatefulWidget {
  const UbuntuConsoleScreen({super.key});

  @override
  State<UbuntuConsoleScreen> createState() => _UbuntuConsoleScreenState();
}

class _UbuntuConsoleScreenState extends State<UbuntuConsoleScreen> with WidgetsBindingObserver {
  bool _daemonEnabled = false;
  bool _bootEnabled = false;
  bool _sshWithUbuntu = false;
  bool _sshInstalled = false;
  bool _keepAliveFloat = true;
  bool _canDrawOverlays = false;
  bool _loading = true;

  // 运行时状态
  bool _ubuntuRunning = false;
  bool _sshdRunning = false;
  int _sshPort = 22;
  Timer? _statusTimer;
  bool _statusBusy = false;

  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _portCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
    _statusTimer = Timer.periodic(const Duration(seconds: 3), (_) => _refreshStatus());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusTimer?.cancel();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _portCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // 从 Settings 等页面返回，刷新权限状态
      _refreshOverlayPermission();
    }
  }

  Future<void> _refreshOverlayPermission() async {
    final canFloat = await DroidDeskPlatform.canDrawOverlays();
    if (!mounted) return;
    setState(() {
      _canDrawOverlays = canFloat;
      if (canFloat && !_keepAliveFloat) {
        // 权限已授权但开关还关着，帮用户打开
        _keepAliveFloat = true;
        _toggle('keepAliveFloat', true);
      }
    });
  }

  Future<void> _refreshStatus() async {
    if (_statusBusy || !mounted) return;
    _statusBusy = true;
    try {
      final s = await DroidDeskPlatform.getUbuntuStatus();
      if (!mounted) return;
      setState(() {
        _ubuntuRunning = s['ubuntuRunning'] as bool;
        _sshdRunning = s['sshdRunning'] as bool;
        _sshPort = s['sshPort'] as int;
      });
    } finally {
      _statusBusy = false;
    }
  }

  Future<void> _toggleSshd(bool wantRunning) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = wantRunning
        ? await DroidDeskPlatform.startUbuntuSshd()
        : await DroidDeskPlatform.stopUbuntuSshd();
    if (!mounted) return;
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          ok
              ? (wantRunning ? 'OpenSSH started' : 'OpenSSH stopped')
              : (wantRunning ? 'OpenSSH start failed' : 'OpenSSH stop failed'),
        ),
        backgroundColor: ok ? DroidTheme.success : DroidTheme.error,
      ),
    );
    await _refreshStatus();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final settings = await DroidDeskPlatform.getUbuntuSettings();
      final creds = await DroidDeskPlatform.getUbuntuCredentials();
      final ssh = await DroidDeskPlatform.isUbuntuSshInstalled();
      final canFloat = await DroidDeskPlatform.canDrawOverlays();
      setState(() {
        _daemonEnabled = settings['daemon'] ?? false;
        _bootEnabled = settings['boot'] ?? false;
        _sshWithUbuntu = settings['sshWithUbuntu'] ?? false;
        _sshInstalled = ssh;
        _keepAliveFloat = settings['keepAliveFloat'] ?? true;
        _canDrawOverlays = canFloat;
        _userCtrl.text = creds['user'] ?? '';
        _passCtrl.text = creds['password'] ?? '';
        _portCtrl.text = creds['port'] ?? '22';
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
    // 初始加载时立即刷新运行时状态（timer 会继续周期性刷新）
    _refreshStatus();
  }

  Future<void> _toggle(String key, bool value) async {
    await DroidDeskPlatform.setUbuntuSetting(key, value);
  }

  Future<void> _installSsh() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() {});
    messenger.showSnackBar(
      const SnackBar(content: Text('Installing openssh-server...')),
    );
    final ok = await DroidDeskPlatform.installUbuntuSsh();
    final ssh = await DroidDeskPlatform.isUbuntuSshInstalled();
    if (!mounted) return;
    setState(() => _sshInstalled = ssh);
    messenger.showSnackBar(
      SnackBar(
        content: Text(ok ? 'OpenSSH installed' : 'OpenSSH installation failed'),
        backgroundColor: ok ? DroidTheme.success : DroidTheme.error,
      ),
    );
  }

  Future<void> _uninstallSsh() async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await DroidDeskPlatform.uninstallUbuntuSsh();
    final ssh = await DroidDeskPlatform.isUbuntuSshInstalled();
    if (!mounted) return;
    setState(() => _sshInstalled = ssh);
    messenger.showSnackBar(
      SnackBar(
        content: Text(ok ? 'OpenSSH removed' : 'OpenSSH removal failed'),
        backgroundColor: ok ? DroidTheme.success : DroidTheme.error,
      ),
    );
  }

  Future<void> _saveCredentials() async {
    final messenger = ScaffoldMessenger.of(context);
    final port = int.tryParse(_portCtrl.text.trim()) ?? 22;
    await DroidDeskPlatform.setUbuntuCredentials(
      _userCtrl.text.trim(),
      _passCtrl.text,
      port.toString(),
    );
    messenger.showSnackBar(
      SnackBar(content: Text('Credentials saved (port $port)')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    return Scaffold(
      appBar: AppBar(title: const Text('Ubuntu Console')),
      body: Container(
        decoration: const BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
                children: [
                  // Header info
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: DroidTheme.cardBg,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: DroidTheme.surfaceBorder),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.code_rounded,
                            color: Color(0xFFE95420), size: 28),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Ubuntu 24.04',
                                style: DroidTheme.headingSm,
                              ),
                              Text(
                                state.hasRoot ? 'chroot mode' : 'proot-distro mode',
                                style: DroidTheme.bodySm,
                              ),
                            ],
                          ),
                        ),
                        const Icon(Icons.check_circle_rounded,
                            color: DroidTheme.success),
                      ],
                    ),
                  ),

                  const SizedBox(height: 20),

                  // Ubuntu Shell
                  _ActionTile(
                    icon: Icons.terminal_rounded,
                    title: 'Ubuntu Shell',
                    subtitle: 'Open a full-screen native Ubuntu terminal(kill command:exit)',
                    color: const Color(0xFFE95420),
                    onTap: () {
                      // 立即跳转，不 await；让原生侧异步启动 terminal activity
                      DroidDeskPlatform.launchUbuntuTerminal();
                    },
                  ),

const SizedBox(height: 16),

                  // 运行时状态
                  _RuntimeStatusCard(
                    ubuntuRunning: _ubuntuRunning,
                    sshdRunning: _sshdRunning,
                    sshInstalled: _sshInstalled,
                    sshPort: _sshPort,
                    onStartSsh: _sshInstalled ? () => _toggleSshd(true) : null,
                    onStopSsh: _sshInstalled ? () => _toggleSshd(false) : null,
                    onRefresh: _refreshStatus,
                  ),

                  const SizedBox(height: 16),

                  // Lifecycle settings
                  Text('LIFECYCLE', style: DroidTheme.label),
                  const SizedBox(height: 8),
                  _SettingCard(
                    children: [
                      SwitchListTile(
                        value: _daemonEnabled,
                        onChanged: (v) {
                          setState(() => _daemonEnabled = v);
                          _toggle('daemon', v);
                        },
                        title: const Text('Process daemon'),
                        subtitle: const Text(
                            'Keep Ubuntu alive in the background'),
                        activeColor: const Color(0xFFE95420),
                      ),
                      const Divider(height: 1, color: DroidTheme.surfaceBorder),
                      SwitchListTile(
                        value: _bootEnabled,
                        onChanged: (v) {
                          setState(() => _bootEnabled = v);
                          _toggle('boot', v);
                        },
                        title: const Text('Boot at startup'),
                        subtitle: const Text('Launch Ubuntu when device boots'),
                        activeColor: const Color(0xFFE95420),
                      ),
                      const Divider(height: 1, color: DroidTheme.surfaceBorder),
                      SwitchListTile(
                        value: _keepAliveFloat,
                        onChanged: (v) async {
                          if (v && !_canDrawOverlays) {
                            // 先把开关值持久化，让 service 提前启动（等授权回来后 onResume 会补上）
                            if (mounted) setState(() => _keepAliveFloat = v);
                            _toggle('keepAliveFloat', v);
                            // 打开 Settings 授权页，用户授权后回来 onResume 会检测并显示悬浮窗
                            await DroidDeskPlatform.requestOverlayPermission();
                            if (mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                  content: Text('请在设置中开启「显示悬浮窗」权限后返回'),
                                  duration: Duration(seconds: 3),
                                ),
                              );
                            }
                            return;
                          }
                          if (mounted) setState(() => _keepAliveFloat = v);
                          _toggle('keepAliveFloat', v);
                          if (mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(v
                                    ? '保活悬浮窗已开启'
                                    : '保活悬浮窗已关闭'),
                              ),
                            );
                          }
                        },
                        title: const Text('Keep-alive overlay'),
                        subtitle: Text(_canDrawOverlays
                            ? 'Show floating icon to prevent background freezing'
                            : '需要「显示悬浮窗」权限'),
                        activeColor: const Color(0xFFE95420),
                      ),
                    ],
                  ),

                  const SizedBox(height: 20),

                  // OpenSSH
                  Text('OPENSSH SERVER', style: DroidTheme.label),
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: DroidTheme.cardBg,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: _sshInstalled
                            ? DroidTheme.success.withValues(alpha: 0.4)
                            : DroidTheme.surfaceBorder,
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              Icons.lock_outline_rounded,
                              color: _sshInstalled
                                  ? DroidTheme.success
                                  : DroidTheme.textSecondary,
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                _sshInstalled
                                    ? 'openssh-server installed'
                                    : 'openssh-server not installed',
                                style: DroidTheme.bodyMd,
                              ),
                            ),
                            if (_sshInstalled)
                              IconButton(
                                icon: const Icon(Icons.delete_outline_rounded,
                                    color: DroidTheme.error),
                                tooltip: 'Uninstall',
                                onPressed: _uninstallSsh,
                              )
                            else
                              FilledButton(
                                onPressed: _installSsh,
                                child: const Text('Install'),
                              ),
                          ],
                        ),
                        const Divider(
                            height: 20, color: DroidTheme.surfaceBorder),
                        SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          value: _sshWithUbuntu,
                          onChanged: _sshInstalled
                              ? (v) {
                                  setState(() => _sshWithUbuntu = v);
                                  _toggle('sshWithUbuntu', v);
                                }
                              : null,
                          title: const Text('Start SSH with Ubuntu'),
                          subtitle: const Text(
                              'sshd will run as long as Ubuntu is running'),
                          activeColor: const Color(0xFFE95420),
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 20),

                  // Credentials
                  Text('ACCOUNT', style: DroidTheme.label),
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: DroidTheme.cardBg,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: DroidTheme.surfaceBorder),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TextField(
                          controller: _userCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Username',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 10),
                        TextField(
                          controller: _passCtrl,
                          obscureText: true,
                          decoration: const InputDecoration(
                            labelText: 'Password',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 10),
                        TextField(
                          controller: _portCtrl,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: 'SSH Port',
                            hintText: '22',
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Align(
                          alignment: Alignment.centerRight,
                          child: FilledButton.icon(
                            onPressed: _saveCredentials,
                            icon: const Icon(Icons.save_rounded, size: 18),
                            label: const Text('Save'),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Credentials + SSH port applied inside Ubuntu on save.',
                          style: DroidTheme.bodySm,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  final VoidCallback onTap;
  const _ActionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: DroidTheme.cardBg,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.4)),
        ),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.14),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: color),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: DroidTheme.headingSm),
                  Text(subtitle, style: DroidTheme.bodySm),
                ],
              ),
            ),
            Icon(Icons.chevron_right_rounded, color: DroidTheme.textDim),
          ],
        ),
      ),
    );
  }
}

class _SettingCard extends StatelessWidget {
  final List<Widget> children;
  const _SettingCard({required this.children});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      child: Column(children: children),
    );
  }
}

class _RuntimeStatusCard extends StatelessWidget {
  final bool ubuntuRunning;
  final bool sshdRunning;
  final bool sshInstalled;
  final int sshPort;
  final VoidCallback? onStartSsh;
  final VoidCallback? onStopSsh;
  final VoidCallback onRefresh;
  const _RuntimeStatusCard({
    required this.ubuntuRunning,
    required this.sshdRunning,
    required this.sshInstalled,
    required this.sshPort,
    required this.onStartSsh,
    required this.onStopSsh,
    required this.onRefresh,
  });

  Widget _row(String label, bool running, String detail, {Color? accent}) {
    final color = running ? DroidTheme.success : DroidTheme.textDim;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Container(
            width: 10,
            height: 10,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: running
                  ? [
                      BoxShadow(
                          color: color.withValues(alpha: 0.5),
                          blurRadius: 6)
                    ]
                  : null,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: DroidTheme.bodyMd),
                Text(detail,
                    style: DroidTheme.bodySm
                        ?.copyWith(color: DroidTheme.textSecondary)),
              ],
            ),
          ),
          Text(running ? 'running' : 'stopped',
              style: DroidTheme.bodySm?.copyWith(
                color: color,
                fontWeight: FontWeight.w600,
              )),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('RUNTIME STATUS', style: DroidTheme.label),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.refresh_rounded, size: 20),
                tooltip: 'Refresh',
                onPressed: onRefresh,
              ),
            ],
          ),
          const SizedBox(height: 4),
          _row(
            'Ubuntu',
            ubuntuRunning,
            ubuntuRunning
                ? 'proot session alive'
                : 'not running — open Ubuntu Shell to start',
          ),
          const Divider(height: 12, color: DroidTheme.surfaceBorder),
          _row(
            'OpenSSH server',
            sshdRunning,
            !sshInstalled
                ? 'not installed — install from openssh section below'
                : sshdRunning
                    ? 'listening on 0.0.0.0:$sshPort'
                    : 'stopped',
          ),
          if (sshInstalled && sshdRunning) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: DroidTheme.success.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                    color: DroidTheme.success.withValues(alpha: 0.3)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.link_rounded,
                      color: DroidTheme.success, size: 18),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'ssh root@<device-ip> -p $sshPort',
                      style: DroidTheme.bodySm?.copyWith(
                        fontFamily: 'monospace',
                        color: DroidTheme.success,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 10),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              if (sshInstalled && !sshdRunning)
                FilledButton.icon(
                  onPressed: onStartSsh,
                  style: FilledButton.styleFrom(
                      backgroundColor: const Color(0xFFE95420)),
                  icon: const Icon(Icons.play_arrow_rounded, size: 18),
                  label: const Text('Start SSH'),
                ),
              if (sshInstalled && sshdRunning)
                OutlinedButton.icon(
                  onPressed: onStopSsh,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: DroidTheme.error,
                    side: BorderSide(color: DroidTheme.error),
                  ),
                  icon: const Icon(Icons.stop_rounded, size: 18),
                  label: const Text('Stop SSH'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
