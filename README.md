# hwkbd_ime — 全键盘拼音输入法

为 Unihertz Titan 2 全键盘(TT2)设计的硬件键盘拼音输入法。Android 8.0+(minSdk 26)。

## 功能

- 三层键盘:L1 字母 / L2 符号数字(Alt,厂商 TitanKey.kcm 键帽)/ L3 编程(Sym,可可扩展字符表)
- Alt/Sym 单击一次性、双击锁定、三击循环、Sym 按住 = Ctrl 组合
- 候选首选居中(空格位),Shift/Sym/空格/Ctrl/Alt 五键选字,Ctrl+QWERT 按槽位选字
- 飞字:键盘表面(TOUCHPAD)上滑按 5 区选字,左右滑翻页,带动画
- 候选栏点选/上滑选字/左右滑翻页(动画),长按候选删除用户词
- 用户词频加权、30 万条内置词库(jieba + pinyin-pro 生成,`tools/dictgen/`)
- 36dp 紧凑单行两态 UI(候选行/状态行芯片徽标)

## 构建

```bash
./gradlew :app:assembleDebug        # 调试 APK
./gradlew :app:testDebugUnitTest    # JVM 单测
```

要求 JDK 17,Android SDK(compileSdk 36 缺失时 AGP 自动下载)。

## CI/CD

- **CI**(GitHub Actions,`.github/workflows/ci.yml`):push/PR → 单测 + assembleDebug,上传 APK 构件
- **Release**(`.github/workflows/release.yml`):推送 `v*` tag → 测试 + 构建 release/debug APK → 创建 GitHub Release
  - 可选签名:仓库 Secrets 配置 `SIGNING_KEYSTORE_BASE64`(jks 的 base64)、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`,release 包自动签名;未配置则出未签名包

安装:`adb install app-debug.apk` 或下载 Release 包手动安装,然后系统设置里启用并选择本输入法。首次打开任意输入框会后台加载词库(约 1-2 秒)。

## 真机调试

- 设置 → 实验场:干净输入框试输入/飞字/层切换
- 设置 → 按键探测:抓物理键真实 keycode,可一键设为 Sym 键
- 日志:`adb logcat -s ImeService:*`
