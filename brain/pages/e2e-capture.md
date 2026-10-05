---
id: e2e-capture
title: "CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
category: project
status: active
tags: [e2e, ci, emulator, media, capture]
created: "2026-10-01T05:09:58"
updated: "2026-10-05T07:14:17"
---

<!-- compiled_truth -->
## 当前状态(2026-10-03,v0.7.0)

**librime 第一次真的跑起来了**:8 个确定性场景全 PASS、2 个视觉判卷 PENDING、
`crash 计数 0`、10 个场景耗时 **3 分 36 秒**。

这是换内核后第一次有运行证据 —— 之前全是「构建通过」这种弱证据。

## 四条元教训:红色的场景要先怀疑断言与工具

这一轮 10 个场景红了 6 次,**没有一次是产品功能坏**。六次给出六种伪装。

### 一、功能正常,但正常路径一条日志都没有

`page-flip` 断言 `fly judge` + `fly page: next`,三条全红。
真相:横滑在 **MOVE 里**就被接管去画跟手动画(`FlyDetector.paging = true`),
抬手走 `CandidateBarView.endPageDrag`,**不经过** ACTION_UP 的兜底分支,
而那两条日志**只打在兜底分支**上。

修法**不是把场景改松**,而是给正常路径补日志:`page begin:` / `page end:`
(带 `by=finger|fling` 与 `commit`,这样「翻页」与「到头弹回」分得开)。

**教训:实现换了路径之后,断言要跟着换。一个「一直红」的场景不是噪声,
是没人在看的失败。**

### 二、`logcat -s` 白名单:加日志 ≠ 可观测

`takeLog` 用 `logcat -d -s ImeService:*`。`-s` 把**不在列表里的 tag 全部丢掉**。
在 `CandidateBarView` 加的日志即使写了、跑到了,E2E 永远看不到。
必须同时把 tag 加进白名单。**新加日志要问「观察它的人能看到吗」。**

### 三、`screencap -d <displayId>` 是 Titan 单屏专用

`compose-space` / `candidate-center` 报 `Command failed`。
那个 `-d` 在模拟器上无效。讽刺的是 `tools/aitest/shots.mjs` 的文件头**早就写明
这件事**并用 `exec-out screencap -p`,只有 `harness.mjs` 没跟上。
**同一件事两份实现时,先确认两份都跟上了文档。**

### 四、`editText()` 读错控件,而且返回一个「看起来像内容」的真值

`l2-output` 拿到 `"密码 → 强制英文"` —— 那是**密码框的 hint**。
两个原因叠加:循环里每个 EditText 都覆盖一次结果(留下 dump 里最后一个,
而 playground 最后一个是 `et_password`)+ **空的 EditText,uiautomator 的
`text` 会回落到 hint**。

改成按 `resource-id` 取 `et_playground`。
**读错控件比功能坏了更难查** —— 它返回一个语义上说得通的字符串,
人会顺着它去想「为什么输出的是这句」,而不会想到「读的不是那个框」。
断言返回**空**时反而更安全。

## 就绪判据:能用确定性信号就别用代理指标

`compose-space` 拿到 `editText="ni"`(原始字母而不是「你」)。
读 `handleSpace()` 得知是 `candidates` 为空 → 走 `commitRaw()`;
而**「为什么候选是空的」在 logcat 里一个字都没有**。

补了 `ImeController.logCandidates`(候选数变化时打一行),真相浮出来:
librime 首次部署(4.6MB 词库转 `.ocd2` + 建 LevelDB)是**异步**的,那段时间里
`state.raw` 正常累积、`updateSelection comp=0..2 raw=ni` 正常、
`imeShown()` 为 true、前台是 PlaygroundActivity —— **所有代理指标都是绿的**,
只有引擎自己知道它没准备好。

于是就绪判据从「窗口在前台」改成**探针**:敲一个 `n`,等
`candidates[…]: n≥1`。而且要同时要求 `comp=<非零>`(按键到了)与 `n≥1`(引擎出了词)。

**探针必须放在每个场景的前置里**,不只是 preflight:场景前置会 `am start`
重启 Activity,焦点要重新建立,那个窗口里按键照样绕过 IME。
preflight 证明过一次「设备就绪」,**推论不出第 5 个场景前置之后仍然就绪**。
探针用完还要**清空输入框**(字段跨场景累积,残留字母会污染所有 `editText()` 断言)。

## 我自己造的两个坑(都是「等就绪」的判据错了)

**其一:另做一个「等引擎就绪」步骤,判据不可能满足。**
我先写了 `waitEngineReady()`,在 preflight 开头轮询 `candidates[…]: n≥1`。
它永远等不到 —— 那条日志**只在按过键之后**才出现,而那时一个键都还没按。
每个场景白等 2×120s,10 个场景把 job 的 45 分钟吃光(实测 31 分钟被取消)。

**等待必须由「按一个键」触发**,而按键正是探针干的事 ⇒ 合并成一个探针。

**其二:日志判据只看数量,探针等不到自己的信号。**
探针每次都是「清空 → 敲一个 n」,每次数量相同,第二次开始 `n` 没变
⇒ **一行都不打** ⇒ 判定成「引擎没就绪」。
判据改成 **(raw, n) 这一对**,并在 `clearComposition()` 里把签名失效。

**教训:任何「等某个信号出现」的判据,都要问「这个信号由谁产生、
它需要先做什么才会产生」。** 我两次都栽在这上面。

## 三态判定:别让待判卷的场景永远红着

`candidate-center` / `layer-chips` 的判定**全部**交给 AI 视觉判卷,
自己一条确定性断言都没有。旧写法 `results.length > 0 && …` 在
`AITEST_API_KEY` 没配时必然判成 FAIL ⇒ **永远红着**。

永远红的场景会教会人无视红灯,连带真的失败也被看过去。改成三态:
无确定性断言 ⇒ **PENDING(待判卷)**,且**分母只算真的判过的** ——
把待判卷算进分母等于宣布「这次跑不可能全绿」,于是没人再看这个数字。
**一个永远达不到的指标等于没有指标。**

判卷答案**不**再猜成 pass/fail:`judge.mjs` 只产出自由文本,用正则去猜
「有没有『不』」会造出假信心(中文答复里出现「不」太常见)。

## 一个红色伪装成「一切正常」的 bug:翻页结构性失效

见 [[librime-android-build]] 的「坑零」。这是本轮唯一一个**真的功能坏**,
而它红了 6 次里的一次,前面 5 次都在修工具。**工具缺陷会掩盖功能缺陷** ——
所以要先修工具,修到能看见真相,才谈得上修产品。

## 子进程吊住事件循环:症状是「所有输出都打完了但进程不走」

`node harness.mjs` 打完汇总(汇总、证据路径、报告路径全在)、日志尾部看起来
一切正常,却赖着不走 —— 每个场景后面固定多等 282s,而
`ci-capture.sh` 给每个场景的 `timeout 300` 是硬上限。

根因:常驻 `logcat` 子进程让 node 事件循环一直「有活干」,
而我写的 `process.on('exit')` 里那句 `kill()` **永远执行不到** ——
exit 事件本身就要求事件循环先空下来。死锁。
修法:`logProc.unref()`。

**教训:父进程持有长驻子进程时,必须 `unref`,而且别指望 `process.on('exit')`。**
`exit` 里做的事,前提是事件循环已经空了。

## 模拟器 FATAL 其实是没有磁盘

```
FATAL | Not enough space to create userdata partition.
 Available: 6725.43 MB, need 7372.80 MB
```

接了 native 之后 E2E 多装 NDK(2.5GB)+ CMake,加上系统镜像与 Gradle 缓存
把 runner 的盘吃掉了。APK 打完就删,并在建 AVD 前打 `df -h`。

**action 输出前面一堆 INFO 时,先去尾部找 FATAL/ERROR。**
那个 FATAL 在 `android-emulator-runner` 输出的第 17 行,前面全是 INFO,
顺着读极容易看漏,然后开始怀疑 APK 与 AVD 配置。

## 断言的优先级(一直适用)

能用**文本 / logcat** 的不要用视觉。视觉判卷要 `AITEST_API_KEY`,
没配就 skipped ⇒ 那两个场景等于没判。所以新增断言优先找一条确定性的日志,
实在没有再加 `shot()` + judge。


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

- time: 2026-10-03T14:00:29
  kind: decision
  summary: "埋点一次定位 page-flip:不是环境,是只有一页候选(librime page_size 默认 5)"
  source: "2026-10-03 E2E run 37124100743 的常驻 logcat"
  affects: [e2e-capture, librime-android-build]

- time: 2026-10-03T18:15:56
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0:从「三个场景红了」到 8/8 确定性场景全绿,3分36秒"
  affects: [e2e-capture]

- time: 2026-10-03T18:16:05
  kind: evidence
  summary: "8/8 确定性场景全绿、crash 0、10 场景 3分36秒;六次红色里五次是工具缺陷"
  source: 2026-10-03 E2E run 37142471832
  affects: [e2e-capture, librime-android-build]

- time: 2026-10-05T07:13:38
  kind: decision
  summary: "2026-10-05 新增两条跨场景污染通道:(a) 设备上那个输入框 —— harness 的 probeIme 清了字段,但探针之后场景又打了字,而 ci-capture.sh 是每场景一个独立 node 进程,进程间唯一公共状态就是那个字段 ⇒ 修在 runScenario 的 finally 里统一清,不靠每个场景自觉。(b) librime 用户词频(LevelDB)—— 上屏一次它就学到一次,**没有任何 API 能撤销**,所以前面场景提交过某个字之后,后面场景的首选就变了。compose-space 断言 editText==你 因此被误伤,改成关系式(zone=2 && li=0 && 上屏==pageWords[0])。"
  source: 2026-10-05 E2E run 37272958949
  affects: [e2e-capture, rime-candidate-consumption]

- time: 2026-10-05T07:14:17
  kind: decision
  summary: "2026-10-05 第三条:takeLog 的 pattern 不能含非 ASCII。pattern 被塞进 adb shell | grep -E,设备侧按 locale 处理字节,5 个汉字(15 字节 UTF-8)传过去就匹配不上 —— 日志里那行明明在,takeLog 却收不到。症状是「断言说没有,可 full.log 里明明有」。改用 ASCII 前缀(from=),中文标签留给本地 JS 正则取(JS 没有编码问题)。全项目只有我新加的这几处踩到,既有 pattern 恰好全是 ASCII。"
  source: "2026-10-05 E2E run 37272958949,拿 full.log 对照 takeLog 输入逐段排查"
  affects: [e2e-capture]
