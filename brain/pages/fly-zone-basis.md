---
id: fly-zone-basis
title: "飞字分区基准：候选行必须通栏（实测宽 == 屏宽）"
category: decision
status: active
tags: [gesture, layout, ux]
created: "2026-10-10T17:41:34"
updated: "2026-10-10T18:15:33"
---

<!-- compiled_truth -->
上滑选字有**两条起手路径**，它们各自把横向空间五等分，而**基准不是同一个量**：

| 路径 | 分区基准 | 代码 |
| --- | --- | --- |
| 候选行内起手 | 候选栏**实测宽** `width / 5` | `CandidateBarView.cellWidth` |
| 键盘表面起手（事件经 ImeRootView 转发） | **整块屏宽** `x / 屏宽 × 5` | `ImeService.FlyDetector.zoneOf` |

## 承重不变量：候选行必须通栏

两者只在 `candidateBar.width == 屏宽` 时重合。此前 `candidate_row` 带 `bar_safe_edge`(16dp)
左右内缩，实测 1363px ≠ 1440px，五等分边界**逐区错开**。

**改的是布局，不是数学**（2026-10-10 用户定）：保留 `x / 屏宽 × 5`，因为 brain 里
2026-09-29 定过「5 区按屏宽平分 = 每区正好 2 个 Titan 2 物理键列」；把候选行改成通栏，
两条路自然重合。**这条不变量跨文件、编译期没有联系**，所以留了**三道**守卫：

1. **构建期**：`app/src/test/.../ui/CandidateRowFullWidthTest.kt` 直接解析 `ime_root.xml`，
   断言 `candidate_row` 没有任何横向 padding、且 `CandidateBarView` 是 `0dp + weight=1`
   铺满整行；顺带钉住状态行仍保留 `bar_safe_edge`（它不参与分区）。跑在
   `:app:testDebugUnitTest` 里，**是唯一不需要设备的**。
2. **运行时**：`ImeService.warnZoneBasisDrift()` 每次飞字 DOWN 对一次账，只报一次 warn。
3. **E2E**：`display-vs-commit` 场景的两条断言 —— `draw:` 埋点里的实测宽 == `wm size`，
   以及 `x = 屏宽/5 + 12`（Titan 2 上 300px）的边界探针。

## 布局文件不是单测的输入（踩过的坑，2026-10-10 实测）

单测用 `File` 读 res 里的 XML，而 **AGP 没有把 res 登记成 `testDebugUnitTest` 的输入**：
把 `paddingStart` 加回布局再跑一次，整条任务 `UP-TO-DATE`、测试一个字都没执行 ——
**看着绿，其实没跑**。所以 `app/build.gradle.kts` 里显式加了一句
`tasks.withType<Test> { inputs.file("src/main/res/layout/ime_root.xml") }`。
以后凡是「单测直接读仓库源文件」的守卫，都必须这样登记，否则它只在冷跑时有效。
（验证方式：改布局→不动别的→`./gradlew :app:testDebugUnitTest`，必须看到 FAILED 而不是 UP-TO-DATE。）

通栏的代价是**观感**：箭头图形靠 `arrowInset` 从 8dp 提到 **24dp** 保住「离屏幕边缘 24dp」
（那是 2026-10-02 用户认过的位置，通栏只改基准不改它）；状态行仍缩 16dp，两行左端元素
因此不再垂直对齐；槽 0/4 的按压高亮矩形一角进圆角区被啃掉几个 px。

## 影响面：只有边界窄带，词心一直是对的

**别把这条说成「按第 1 个词却选中第 0 个」**——错。五个槽心换算到屏幕是
174.9/447.5/720.1/992.7/1265.3px，全部落在自己的 `1440/5` 区内，**词心命中在旧基准下一直成立**。
错开的只有分区边界：偏差 `edgePx × (2i−5)/5` ⇒ 两条内侧边界偏 ±7.7px、两条外侧偏 ±23px
（约 3~10dp）。手指落在边界窄带里、或长词（缩字后半宽可达 ~50dp）的边缘压在边界上，才会选中邻词。
**推论：按槽心的常规用例永远测不出这个 bug**，所以 E2E 必须专门造边界探针。

## xMax 那个字面量

`FlyDetector` 曾有 `var xMax = 1440f`：Titan 2 的物理像素数被钉成**字段初值**，
`applyPrefs()` 还没跑、或屏宽不是 1440 时，1440 就冒充真实屏宽参与分区。改成
`zoneBasis: () -> Float`（宿主提供，现取 `displayMetrics.widthPixels`），本类不再有可过期的字段。

**为什么是函数而不是当场取值**：`flyDetector` 是 service 的属性初始化器，跑在**构造期**，
那时 IME 还没 attach base context，`resources` 会抛；而它每次手势都要被调用，那时早已就绪。
`density` 是设置项的函数（用户改过 → 约 2.41），但 `widthPixels` 不随之变（见 [[paging-feel]]
「屏宽类的三个数」）。

## 本次没动的

纵向触发阈值 `threshold=160f`、设置页三档 100/160/240、`PAGE_THRESHOLD_TIERS` 的 px 列、
飞字抬升 `dip(26)`/`dip(64)` —— 用户明确「只管横向」；且 [[paging-feel]] 定了
「死区用常量、不用比例」，把它们改成屏宽比例会连带改掉手感。

## 还缺的设备证据

真机 `tools/aitest/local.sh display-vs-commit` 没跑过（本机没有 `/dev/kvm`，
`local.sh` 明确拦模拟器），手感回归同样待做：翻页提交线随行宽从 300px 变 317px（+5.7%）。
**这三道守卫全绿也不等于分区改动已被真机确认。**


## Timeline

- time: 2026-10-10T17:41:34
  kind: decision
  summary: "Created this page: 飞字分区基准：候选行必须通栏（实测宽 == 屏宽）"
  source: "2026-10-10 用户「输入法需要根据屏幕宽度计算飞字区域 不要写死」"
  affects: [fly-zone-basis]

- time: 2026-10-10T17:42:07
  kind: decision
  summary: "定案：上滑分区的两个 owner 只在「候选行通栏」这条布局前提下重合；xMax 字面量删除，基准由 FlyDetector 现取屏宽"
  source: "2026-10-10 用户定案 + 本次改动"
  affects: [fly-zone-basis]

- time: 2026-10-10T18:02:35
  kind: decision
  summary: "三道守卫(构建期布局断言 / 运行时对账 / E2E 探针)+ 更正场景名"
  source: brain update-truth
  affects: [fly-zone-basis]

- time: 2026-10-10T18:08:22
  kind: note
  summary: "分区基准只有两个 owner 是活的;SoftKeyboardView.zoneIndexAt 是第三套按自身 width 的五等分,但全仓没有任何实例化(Kotlin 无调用、XML 无声明),它是「将来的屏幕软键盘」占位。将来启用时它必须通栏、或改用同一个基准,否则这次的边界漂移会换个地方复发。bar_safe_edge 在 Kotlin 里零引用,所以通栏不需要动任何坐标换算"
  source: "2026-10-10 静态核对全仓 zoneOf / FLY_ZONES / bar_safe_edge 引用"
  affects: [fly-zone-basis]

- time: 2026-10-10T18:15:33
  kind: evidence
  summary: "离线证明这两条断言真有判别力:实测宽 1363px(旧)时 34 条断言红 5 条 —— 4 条实测宽断言 + 1 条边界探针(画 W00 / 上屏 W01),而四个槽心用例全绿(印证「常规用例测不出」);1440px(新)时红 0 条。局限:假设备是我建的模型,它只证明断言会分辨新旧,不替代真机证据"
  source: "2026-10-10 用假设备(按自身实测宽五等分的候选栏 + 按屏宽五等分的键盘面)离线驱动 display-vs-commit 那一步"
  affects: [fly-zone-basis]
