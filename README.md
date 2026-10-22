# 清屏（QingPing）— 原生 Android 工程

**本仓库使用 Kotlin + Android SDK 原生开发，不需要 Godot，也不需要 Godot AAR 插件。**

清屏是一个基于 Android 无障碍服务的广告辅助拦截原型。它尝试识别其他应用中可被无障碍 API 读取的广告提示和关闭按钮，并在本地按应用统计成功执行的操作次数。

## 功能

- 通过用户在系统设置中主动启用的 `AccessibilityService` 监听界面变化。
- 当无障碍树中出现“广告 / 推广 / Sponsored / Advertisement”等提示时，尝试点击“跳过广告 / 关闭广告 / 跳过 / 关闭”等可访问控件。
- 仅当 Android 接受点击或返回操作后才增加计数；这不等于确认广告已完全消失。
- 主界面按应用显示累计次数、最近操作时间和应用名称。
- 可清空统计；统计保存在本机 `SharedPreferences`。
- 可选的“疑似误触跳转后自动返回”功能默认关闭。它是启发式补救，可能返回用户主动打开的链接。

## 项目结构

- `app/src/main/java/com/qingping/app/MainActivity.kt`：原生界面。
- `app/src/main/java/com/qingping/app/AdGuardAccessibilityService.kt`：无障碍识别与点击逻辑。
- `app/src/main/java/com/qingping/app/StatsStore.kt`：本地统计存储。
- `app/src/main/AndroidManifest.xml`：应用和服务声明。
- `.github/workflows/build-apk.yml`：GitHub Actions 自动构建调试版 APK。

## 直接下载 APK

[下载清屏 v1.0 调试版 APK](https://github.com/Charlie2006/ClearScreen/raw/refs/heads/main/downloads/qingping-v1.0-debug.apk)

这是已实际编译并通过签名校验的调试版，支持 Android 8.0 及以上。
如打开了 GitHub 文件页面，请点击 **Download raw file** 下载。
校验文件位于 [downloads/SHA256SUMS](downloads/SHA256SUMS)。
尚未进行真机功能测试；安装后需手动启用系统无障碍服务。

## 如何获得 APK

本仓库配置了 GitHub Actions：

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build QingPing APK**。
3. 点击 **Run workflow**，或等待向 `main` 分支推送提交后自动构建。
4. 构建成功后，打开对应的运行记录，在 **Artifacts** 区域下载 `qingping-debug-apk`。
5. 解压 artifact，取得 `app-debug.apk`，再安装到 Android 手机上。

工作流使用 JDK 17、Gradle 8.9、Android SDK Platform 35 和 Build Tools 35.0.0。首次运行可能需要几分钟下载依赖。

## 本地构建

需要 JDK 17、Gradle 8.9 和 Android SDK Platform 35。可在 Android Studio 中打开本项目根目录；也可以在已安装 Gradle 的命令行环境运行：

```bash
gradle --no-daemon assembleDebug
```

输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 手机首次使用

1. 安装 APK 并打开“清屏”。
2. 点击“打开无障碍设置”。
3. 在系统无障碍设置中手动启用“清屏广告辅助服务”。
4. 返回清屏，检查服务状态并查看统计。
5. 建议先保持“疑似误触跳转后自动返回”关闭，确认基本功能稳定后再考虑开启。

## 重要限制

1. **不能保证拦截所有广告。** 只有无障碍树中可读取的文字和控件才有机会被识别；图片式广告、自绘界面、游戏画面、隐藏按钮、无文字广告和部分强制跳转可能无法处理。
2. 这不是 VPN、DNS 或系统级广告过滤器；它不拦截广告网络请求，也不修改其他应用。
3. “跳转后返回”不是阻止跳转本身，而且可能误伤正常外链，因此默认关闭。
4. Android 会管理无障碍服务。服务通常可在系统重启后恢复，但无法保证所有厂商在清理后台、强行停止或省电模式下都持续运行。可按手机品牌设置自启动和“不限制”电池策略。
5. `QUERY_ALL_PACKAGES` 用于将包名显示为应用名称。若要上架 Google Play，需要评估包可见性政策和无障碍 API 政策并完成必要申报；不能保证审核通过。
6. 当前仓库提供源码和自动构建流程。只有当 Actions 构建成功并生成 artifact 后，才算实际产出 APK；仍需真机测试验证效果。

## 隐私

应用没有申请网络权限。统计数据仅保存在本机。无障碍服务需要用户主动在 Android 系统设置中启用，并会读取其他应用中系统允许访问的界面内容，以执行本项目所描述的辅助操作。
