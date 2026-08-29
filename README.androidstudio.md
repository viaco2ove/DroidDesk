# step1:
Write(.idea\runConfigurations\flutter_droiddesk.xml)
Wrote 9 lines to .idea\runConfigurations\flutter_droiddesk.xml
<component name="ProjectRunConfigurationManager">
<configuration default="false" name="DroidDesk" type="FlutterRunConfigurationType" factoryName="Flutter">
<option name="entrypoint" value="file://$PROJECT_DIR$/app/lib/main.dart" />
<option name="additionalArgs" value="" />
<method v="2">
<option name="RunConfigurationTask" enabled="true" runner="FlutterRunner" config="android" />
</method>
</configuration>
</component>

# step2:

更新一下 modules.xml，确保 Flutter 模块被正确识别：

添加了 Flutter 运行配置。

完成的配置

✅ 创建了运行配置文件 .idea/runConfigurations/flutter_droiddesk.xml

# 在 Android Studio 中使用

由于这是 Flutter 项目，在 Android Studio 中你需要确保：

1. 安装 Flutter 插件

- 打开 Android Studio → Settings → Plugins
- 搜索 "Flutter" 并安装
- 可能还需要安装 "Dart" 插件

2. 在 Android Studio 中打开项目

- File → Open → 选择 D:\Users\viaco\PycharmProjects\TermuxPilot\android\DroidDesk\app 文件夹（不是 android 子目录）
- 或者直接在 Android Studio 中选择 "Open Flutter Project"

3. 运行项目

配置创建好后，在 Android Studio 顶部会出现 DroidDesk 运行按钮（绿色三角形 ▶️），点击即可运行到手机。

---

注意：如果你想在 Android Studio 中同时管理这个项目的 Flutter 代码和 Android 原生代码，建议将 app 文件夹作为项目根目录打开（因为它是 Flutter 项目结构），而不是打开 android 子目录。