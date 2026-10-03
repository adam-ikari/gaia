---
id: e2e-capture
title: "CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
category: project
status: active
tags: [e2e, ci, emulator, media, capture]
created: "2026-10-01T05:09:58"
updated: "2026-10-03T11:49:31"
---

<!-- compiled_truth -->
## 好消息:2026-10-03 E2E 第一次把 librime 跑起来了

`fly-select` 场景通过,断言 `hasComposition=true` —— 也就是说
JNI 加载成功、librime 部署完成、**真的查出了组词**。crash 计数 0。
这是换内核后第一次有运行证据(之前全是「构建通过」这种弱证据)。

## 三个场景红了,全是**工具缺陷**,不是功能坏

这是本项目 E2E 至今最重要的一条教训:**红色的场景要先怀疑断言与工具,
再怀疑产品**。三个红的给出三种不同的伪装。

### 一、翻页成功这条路在 logcat 里一个字都没有

`page-flip` 断言 `fly judge` + `fly page: next`,三条全红。

真相:横滑在 **MOVE 里**就被接管去画跟手动画(`FlyDetector.paging = true`),
抬手走 `CandidateBarView.endPageDrag`,**不经过** ACTION_UP 的兜底分支。
而 `fly judge` / `fly page: next` 两条日志**都只打在兜底分支**上 ——
那条分支只在「MOVE 压根没接管」(事件被合并成 DOWN+UP)时才轮得到。

⇒ **功能可能完全正常,只是没有证据。** 场景是跟手动画实现之前写的,
断言一直打在**旧路径**上,没人发现,因为它「一直红着」而被当成了已知噪声。

修法**不是把场景改松**,而是给正常路径补日志:`page begin: dir=… 跟手翻页/到头走橡皮筋`
+ `page end: step=… by=finger|fling … commit=…`。
带上「过线方式」与 `commit`,这样「翻页」与「到头弹回」分得开 ——
到头那条路也走同一个 `endPageDrag`,只看结果分不出来。

**教训:实现换了路径之后,断言要跟着换。** 一个「一直红」的场景
不是噪声,是**没人在看的失败**。

### 二、`logcat -s` 白名单:加日志 ≠ 可观测

`takeLog` 用的是 `logcat -d -s ImeService:*`。`-s` 会把**不在列表里的 tag
全部丢掉**。所以在 `CandidateBarView` 里加的日志,即使写了、即使代码跑到了,
E2E 永远看不到。必须同时把 tag 加进白名单:`-s ImeService:* CandidateBar:*`。

**教训:新加的日志要问「观察它的人能看到吗」。** 有 tag 白名单/日志级别过滤/
采样率的地方,加日志和让它可见是**两件事**。

### 三、`screencap -d <displayId>` 是 Titan 单屏专用,模拟器上直接失败

`compose-space` / `candidate-center` 报
`Command failed: adb shell screencap -d 4627039422300187648 -p /sdcard/…`。

那个 `-d` 是 Titan 单屏的 displayId,在模拟器上无效。
讽刺的是 `tools/aitest/shots.mjs` 的文件头**早就写明了这件事**,并且用的是
`exec-out screencap -p`,只是 `harness.mjs` 没跟上。

⇒ 统一成 `exec-out screencap -p`:不过 sdcard、不用 pull、字节流直出。

**教训:同一件事在一个仓库里有两份实现时,先确认两份都跟上了文档。**
一处更新一处没更新,比两份都没更新更危险。

### 四、`editText()` 读错控件,而且给出一个「看起来像内容」的真值

`l2-output` 拿到 `"密码 → 强制英文"` —— 那是 `playground_password_hint`,
**密码框的 hint**。两个原因叠加:

1. 循环里每个 EditText 都覆盖一次结果 ⇒ 留下 dump 里**最后一个**,
   而 playground 最后一个是 `et_password`;
2. **空的 EditText,uiautomator 的 `text` 属性会回落到 hint。**

⇒ 改成按 `resource-id` 精确取 `et_playground`。

**教训:读错控件比功能坏了更难查** —— 它返回一个**语义上说得通的字符串**,
人会顺着它去想「为什么输出的是这句」,而不会想到「读的不是那个框」。
断言返回**空**时反而更安全。

## 模拟器 FATAL 其实是没有磁盘

启动后立刻退出、600s 超时的真正原因:

```
FATAL | Not enough space to create userdata partition.
 Available: 6725.43 MB, need 7372.80 MB
```

接了 native 之后 E2E 多装 NDK(2.5GB)+ CMake,加上系统镜像与 Gradle 缓存
把 runner 的盘吃掉了。APK 打完就删 NDK/CMake/`~/.gradle/caches`,并在建 AVD 前打 `df -h`。

**教训:action 的输出前面一堆 INFO 时,先去尾部找 FATAL/ERROR。**
`android-emulator-runner` 那个 FATAL 在第 17 行,前面全是 INFO,
顺着读极容易看漏,然后开始怀疑 APK 与 AVD 配置。

## 断言的优先级(这个项目一直适用)

能用**文本 / logcat** 的,不要用视觉。视觉判卷要 `AITEST_API_KEY`,
没配就 skipped ⇒ **CI 全绿但其实什么都没判**。
所以新增断言优先找一条确定性的日志,实在没有再加 `shot()` + judge。


## Timeline

- time: 2026-10-01T05:09:58
  kind: decision
  summary: "Created this page: CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
  source: "2026-10-01 实测"
  affects: [e2e-capture]

- time: 2026-10-01T05:10:12
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 连续九轮 CI 实测"
  affects: [e2e-capture]

- time: 2026-10-01T05:10:12
  kind: evidence
  summary: "2026-10-01:九轮 CI 后 E2E 全绿,9 张图 + mp4 + manifest 一次产出,0 失败步骤;站点已接入并在 gaia 仓库上线"
  source: run 36817406644
  affects: [e2e-capture]

- time: 2026-10-01T06:02:36
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 端到端断言时发现,读 ImeRootView 定位到根因"
  affects: [e2e-capture]

- time: 2026-10-01T06:02:36
  kind: evidence
  summary: "发现:组词未上屏时输入法窗口全屏吃掉所有触摸,App 里点不动任何控件(ImeRootView.dispatchTouchEvent + flyMode 全屏)。已记入待确认,采集侧用每步收尾规避"
  source: "tools/aitest/shots.mjs 的 focused 断言 + ImeRootView.kt:66"
  affects: [e2e-capture]

- time: 2026-10-01T08:03:23
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 屏幕对齐 Titan 2 网格"
  affects: [e2e-capture]

- time: 2026-10-01T08:03:23
  kind: evidence
  summary: "屏幕对齐 Titan 2 网格(1440x1440@320dpi=576dp)后 E2E 全绿:9 张图 + 89s 录屏,0 失败。中区从 159dp 变 346dp,L4 里 http:// https:// www. 回来了。三个坑:不能事后 wm size、AOSP Launcher3 在正方形屏必崩(divide by zero)、avdmanager 不可用(rc=0 却什么都没建)→ AVD 手写"
  source: run 36832802217
  affects: [e2e-capture]

- time: 2026-10-03T11:49:01
  kind: evidence
  summary: "v0.7.0 首跑:三个场景红了全是工具缺陷不是功能坏;翻页正常路径一条日志都没有"
  source: "2026-10-03 E2E run 37118473329(E2E 第一次真跑 librime)"
  affects: [e2e-capture, librime-android-build, paging-feel]

- time: 2026-10-03T11:49:31
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 首跑 + 三处修复"
  affects: [e2e-capture]
