# APK 源码评审（2026-11-25）

范围：`app/src/main/java` 下自研 Kotlin 55 个文件、14,292 行。排除 vendored librime C++、资源、测试与构建脚本。五个模块并行静态评审，每条发现由主评审逐条核对行号与调用链；未跑真机，均为静态证据。

结论：P1 6 条、P2 11 条、P3 9 条。P1 集中在日志泄露、引擎生命周期和输入路径崩溃。

## P1

1. **长按状态只有一个槽，双键重叠会吞键或让麦克风常开** — `ime/ImeController.kt:329,386-404,456-461`
   按住字母 A 未松再按 B，`tryArmLongPress` 无 `lp.code != 0` 守卫，第二次 `lp.arm()` 覆盖第一次；A 的 UP 不满足 `code == lp.code`，字母 A 被吞。语音场景同理：`fired=true` 后按其他键会把 `voice` 覆盖为 false，松开空格不再 `stopVoiceInput()`，录音不停。
   修法：`tryArmLongPress` / `tryArmVoiceLongPress` 入口加 `if (lp.code != 0) return false`。

2. **输入内容逐字写进 logcat** — `ime/ImeController.kt:822-829,1138-1142,1167-1171,1225-1231`、`ui/CandidateBarView.kt:270-278,1902-1909`、`rime/RimeEngine.kt:231-235`
   每个字母的 `raw`、候选词表、选中的词与 `commit_text_preview` 明文写日志。项目口径是「输入内容 / 候选词 从不采集」（`telemetry/Redaction.kt:21`），E2E 用 `logcat -s ImeService:*` 抓日志（`tools/aitest/harness.mjs:238`）。
   修法：日志只留长度、序号或哈希，删掉 `raw` / `word` / `words` / `preview` 字段。

3. **输入上下文正文进日志并显示在实验场** — `service/ImeService.kt:1511-1517`
   `getTextBeforeCursor(200)` / `getTextAfterCursor(200)` 的结果原样写入 `syncComposing` 日志；`playground/PlaygroundActivity.kt:56` 又把这段日志显示在界面上。目标 App 的正文（消息、姓名、单据）离开原应用。
   修法：只记长度与脱敏前缀。

4. **Rime 引擎无释放点** — `rime/RimeEngine.kt:342-348`、`service/ImeService.kt:386-396`
   `close()` 全仓无调用；切换输入法或系统重建服务时 `onCreate` 又 `new RimeEngine` 覆盖 `ImeRuntime.rime`，旧 session 与 native 全局状态不释放。
   修法：`onDestroy` 调 `ImeRuntime.rime?.close()` 后置空。

5. **自定义键位文件的非法段名让输入法启动崩溃** — `keymap/KeyLayout.kt:488,528`
   `[L2x]` 这类段名在 496-501 行被记录为错误，但 `section` 仍被写成 `L2x`；528 行 `Layer.valueOf(section)` 没保护，异常逃出 `parseText`，`KeyLayoutStore.load` 的 `?: return factory` 接不住。
   修法：未知段名不写入 `section`，或 528 行同样包 `runCatching`。

6. **渲染泵一次异常后永久停摆** — `ui/RenderPump.kt:40-55`
   `run()` 的 `render()` 无 try/catch 与重启；视图 detach 后的一次 NPE 会让协程退出，之后 `request()` 全部无效，界面冻结；异常还会传到主线程。`SupervisorJob()` 不吞这个异常。
   修法：包 try/catch 并记日志，或装 `CoroutineExceptionHandler` 后重启。

## P2

7. **Android 12 及以下剪贴板密码明文进历史** — `service/ImeService.kt:100-117`、`ime/L5Ui.kt:436-440`、`ime/ClipHistory.kt:6-11`
   `EXTRA_IS_SENSITIVE` 仅 API 33+ 读取，旧系统一律 `false`；`clipLabel` 只在 `sensitive=true` 时遮罩。密码管理器复制的内容在面板上明文列出，IME 隐藏时仍在读剪贴板。
   修法：无平台标记时默认按敏感遮罩，用户主动切换明文；IME 隐藏时暂停读取。

8. **L5 tab 集合变化不触发重建** — `ui/L5PanelView.kt:131-140`
   签名只含 `theme.id/tabIndex/pageIndex/pageKeys().hashCode()`，不含 tab 数量与顺序。剪贴板从空变非空时 tab 条不出现；清空后旧 tab 残留，点击按下标选错页。
   修法：把 `ui.tabs` 的 id / 标签序列纳入签名。

9. **语音返回 STARTED 时还没开始录音** — `service/voice/SherpaLocalEngine.kt:99-104,156-163`
   模型未就绪时 `start()` 置 `pendingStart` 后直接返回 `STARTED`；异步加载失败几秒后才报 `UNAVAILABLE`，期间既没有空格也没有识别结果。
   修法：`STARTED` 只在实际开始录音后返回，或先补空格、成功时撤回。

10. **librime native 调用跨线程** — `service/ImeService.kt:350-358`、`rime/RimeEngine.kt:111,125`、`rime/RimeNative.kt:20-24`
    `rime-boot` / `rime-deploy` 线程调用 `initialize` / `createSession`，而契约要求除维护入口外全在 IME 主线程。librime session 非线程安全。
    修法：部署留后台，session 操作回主线程。

11. **软键盘单指坐标与 DOWN 即点击** — `ui/SoftKeyboardView.kt:66-67,124-141`
    `downX/downY` 只有一份，不处理 `ACTION_POINTER_DOWN/UP`；`ACTION_DOWN` 就 `performClick()`，真正的键在 UP 才 `press()`。双指或上滑会按错键或提前发出点击。
    修法：按 `pointerId` 记录起手坐标，点击与 `press()` 一起放到确认点按时。

12. **音量条上升无动画、NaN 会无限自绘** — `ui/VoiceLevelView.kt:28-58`
    `setLevel` 的 `if (target > shown) shown = target` 让上升第一帧跳到目标；`onDraw` 的 `if (target - shown < 0.01f)` 缺绝对值，下降时恒真。`coerceIn` 对 NaN 原样返回，`shown != target` 恒真，持续 `postInvalidateOnAnimation()`。
    修法：`abs(target - shown) < 0.01f`，`setLevel` 处理 NaN。

13. **翻页时旧候选换入动画继续写槽位** — `ui/CandidateBarView.kt:311,379-391,2053`
    `pageTurned` 跳过 `startSwapAnim`，但没有 `swapAnim?.cancel()` / `swapSlots.clear()`；旧 `onFrame` 继续按旧索引写，新页槽位带旧位移与半透明。
    修法：翻页与 `rawChanged` 分支先取消并清空。

14. **onDraw 每帧创建 TextView** — `ui/L4PanelView.kt:394-418`
    键序退场动画每帧 `TextView(context)`、`setTextSize` 并对每个 ghost `measureText`。
    修法：测量用 Paint/TextView 提为字段复用。

15. **权限弹窗期间旋转屏幕丢失待处理状态** — `settings/SettingsActivity.kt:56,61-62,102-104`
    `pendingVoiceEnable` 是普通实例字段，`onSaveInstanceState` 未保存；回调落到新 Fragment 时直接 return，开关停在关。
    修法：保存到 instance state，或回调以实际权限为准。

16. **备份包含模型与用户词库** — `AndroidManifest.xml:18`
    `allowBackup="true"` 且无 backup rules，模型文件与 `rime/user/` 被打包进备份。
    修法：`allowBackup="false"`，或用规则排除。

17. **同一分钟内多次崩溃互相覆盖** — `telemetry/CrashReporter.kt:98,104-109`、`telemetry/Redaction.kt:80`
    崩溃文件名只到分钟，`f.writeText` 覆盖前一次，最早根因栈丢失；`MAX_FILES=50` 不起作用。
    修法：文件名加秒或序号，同名时另选候选名。

18. **关键路径缺少测试** — `test/.../SherpaLocalEngineTest.kt:11-28`、`test/.../FakePinyinInputMethod.kt:112`
    语音 start→cancel→迟到结果、`VoiceLevelView` / `SoftKeyboardView` / `ImeRootView` 的触摸与绘制、`RimeEngine.currentCandidates` 不重喂分支均无测试；替身把 `consumed == 0` 折成 `null`，与真引擎语义相反。
    修法：给语音与 native 抽测试缝，替身对齐 `0` 语义，补 View 触摸回归。

## P3

19. 调试 Activity 可被第三方显式拉起：`AndroidManifest.xml:53,60` 的 `KeyProbeActivity` / `PlaygroundActivity` 均 `exported=true` 且无权限；两者吃掉大部分硬键。改 `exported=false` 或加签名级权限。
20. 手势计时用墙钟：`ime/ModifierGestures.kt:17`、`ime/KeyEventGate.kt:81` 默认 `System.currentTimeMillis()`，时间回拨会让长按 / 静默窗判定反向。改 `SystemClock.uptimeMillis()`。
21. 两张相同的候选顺序表：`ime/ImeState.kt:326,335` 的 `DISPLAY_ORDER` 与 `COMPACT_ORDER` 值相同，分支当前无效果，将来只改一处会让五键选字错位。删一张。
22. `flySpeed` 写入后无读取：`ui/CandidateBarView.kt:191,1260,1431`。删除字段与两处写入。
23. L5 tab 滚动回调捕获旧下标：`ui/L5PanelView.kt:169,178-188`，快速切 tab 后会把位置拽回旧 tab。
24. 每次按键新建正则：`strategy/PinyinInputMethod.kt:80` 的 `Regex("'+")` 提到 companion object。
25. 崩溃全文栈写 logcat：`telemetry/CrashReporter.kt:91`，异常 message 可能含变量值；logcat 只留一行提示。
26. 过期注释仍以事实口吻描述已推翻的结论：`ui/RubberDrag.kt:16-17`。
27. `CandidateBarView.kt:1530-1540` 两行相邻日志内容重复；`ui/CandidateBarView.kt:1290-1293` 的 `cancelFlyDrag` 未检查 `flyDriven`，飞字动画中 ACTION_CANCEL 会两路同写 `flyP`。

## 证据边界

- 所有条目来自源码静态阅读，未在 Titan 2 真机或模拟器上复现；涉及 Native 内存泄漏、跨线程、时间回拨的条目为契约违反与代码推导。
- 已确认的契约来源：`rime/RimeNative.kt:20-24`、`telemetry/Redaction.kt:21`、`ime/ImeState.kt` 与 UI 各文件的现有单测。
- 行号对应 2026-11-25 的 `master`（HEAD `d15cd73`）。

## 修复记录（2026-11-25）

27 条全部改完。`./gradlew testDebugUnitTest` 415 条通过，`processReleaseManifest` 通过；未跑真机与 E2E。

- 敏感日志按 `BuildConfig.DEBUG` 分流：debug 保持原格式（`env-reset.sh` / `harness.mjs` / `scenarios.mjs` 的解析不变），release 只打长度与数量。
- `PlaygroundActivity` 在主 manifest 为 `exported=false`，`app/src/debug/AndroidManifest.xml` 覆写为 `true`：E2E 在非 root 真机用 `am start` 拉起它；release 仍收紧。`KeyProbeActivity` 全构建不导出。
- librime 部署留后台，`createSession` 移到 IME 主线程；`close()` 的销毁与置 0 进同一把锁。
- 语音模型未就绪时 `start()` 返回 `FAILED` 且不设 `pendingStart`：控制器补一个空格，模型迟到加载不会再偷偷录音，也不再双 Toast。
- 剪贴板用 `knownSensitive` 区分平台是否给出标记：旧系统默认遮罩，API 33+ 按系统标记显示。
- 手势计时默认改 `SystemClock.uptimeMillis()`；`ImeController` 注入 `nanoTime/1e6` 的单调时钟，避免 JVM 单测里 `SystemClock` 桩恒 0。

**真机复验补修（2026-11-26）**：在 Titan 2 上用 beta 包敲拼音抓 logcat，发现 `ImeService.kt:626` 的 `updateSelection ... raw=$composing` 未做 debug/release 分流，release 仍会写拼音原串。已改成 debug 留原值、release 打 `rawLen`。重编 beta 装回真机再验：日志只有 `rawLen`、`wordLen`、`words=5`（数量）、`beforeLen/afterLen`、`consume: index/consumed`，没有原串、词表、preview 或上下文正文。
