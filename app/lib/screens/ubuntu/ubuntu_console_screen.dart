import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';

class UbuntuConsoleScreen extends StatefulWidget {
  const UbuntuConsoleScreen({super.key});

  @override
  State<UbuntuConsoleScreen> createState() => _UbuntuConsoleScreenState();
}

class _UbuntuConsoleScreenState extends State<UbuntuConsoleScreen> {
  bool _daemonEnabled = false;
  bool _bootEnabled = false;
  bool _sshWithUbuntu = false;
  bool _sshInstalled = false;
  bool _loading = true;

  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _portCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _userCtrl.dispose();
    _passCtrl.dispose();
    _portCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final settings = await DroidDeskPlatform.getUbuntuSettings();
      final creds = await DroidDeskPlatform.getUbuntuCredentials();
      final ssh = await DroidDeskPlatform.isUbuntuSshInstalled();
      setState(() {
        _daemonEnabled = settings['daemon'] ?? false;
        _bootEnabled = settings['boot'] ?? false;
        _sshWithUbuntu = settings['sshWithUbuntu'] ?? false;
        _sshInstalled = ssh;
        _userCtrl.text = creds['user'] ?? '';
        _passCtrl.text = creds['password'] ?? '';
        _portCtrl.text = creds['port'] ?? '22';
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
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
