# hwkbd_ime — 盖亚输入法

为 Unihertz Titan 2 全键盘(TT2)设计的硬件键盘拼音输入法。Android 8.0+(minSdk 26)。

## 功能

- 三层键盘:L1 字母 / L2 符号数字(Alt,厂商 TitanKey.kcm 键帽)/ L3 编程(Sym,可可扩展字符表)
- Alt/Sym:单击一次性、双击锁定、三击循环;**长按进层(按住期间生效),松开取消**
- Shift:单击大写一次、双击 Caps 大写锁定、长按按住期间大写(松手取消),状态行有大写芯片提示
- 候选首选居中(空格位),**Shift/Sym/空格/Ctrl/Alt 五键选字**(左→右对应候选 2/5/1/4/3),Ctrl+字母组合只转发给应用
- 飞字:键盘表面上滑按 5 区选字,词随手指缓慢起升、越过阈值飞完(其余候选同步渐隐),左右滑翻页
- 候选栏上滑选字同样拖动联动;点选/左右滑翻页(动画),长按候选删除用户词
- 用户词频加权、30 万条内置词库(jieba + pinyin-pro 生成,`tools/dictgen/`)
- 48dp 紧凑单行两态 UI(组词中候选行整行,空闲时状态行),状态行分三区:
  - **左=状态芯片**(顺序:`拼/En` → 大写 → `ALT` → `SYM`)——`拼/En` 点按循环切中英;
    `ALT`/`SYM` **两颗常显**(没切换时是灰色),
    点按=单次切换到该层(出一键回落,已在该层再点=回 L1)、**长按=锁定该层**(再长按=解锁),
    打普通字母时不显示任何"当前层"图标;英文态的 `ab/Ab/AB` 大写芯片**紧贴 En**(点按开关 Caps),
    **拼音态也占着那 34dp**(隐形),中英切换时中区不挪。
    `ab`/`ALT`/`SYM` **三颗互斥:同一时刻只亮一颗**(后按的压过先前的),没轮到亮/没生效的那颗是灰的——`ab` 不再常亮
  - **中=L4 虚拟按键**——**只放 L1-L3 敲不出的键**:固定核心键(删不掉)+
    键位编辑器自定义键,两段都过一道筛(L1-L3 已能敲出的取值直接不显示),**没有动态键**;
    单行不滚动,**键间距固定 4dp**(间距不许被拉大),
    富余宽度加到键宽上**贴边填满**中区,**左右边距对称 6dp**,超出则贴左裁切;设置页开关控制显隐
  - **右=设置/收起按钮**(48dp 见方,宽 ≥ 高;齿轮按 24dp 原尺寸绘制,不放大)

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
