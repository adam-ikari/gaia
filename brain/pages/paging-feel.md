---
id: paging-feel
title: "待修:翻页不跟手、动画太短、缓动要非线性"
category: decision
status: active
tags: [gesture, animation, paging]
created: "2026-09-30T13:23:54"
updated: "2026-10-02T10:50:27"
---

<!-- compiled_truth -->
---
id: paging-feel
title: "死区与提交线是一对:死区是常量,提交线可调"
category: decision
status: active
tags: [gesture, animation, paging, threshold]
created: "2026-09-30T13:23:54"
updated: "2026-10-02T10:50:00"
---

<!-- compiled_truth -->
三处拖动手势(候选行横滑翻页、候选行内起手的上滑飞字、L5 横滑)共用同一套换算
`DragGesture`,两条独立的线:

| 量 | 取值 | 可调? | 管什么 |
| --- | --- | --- | --- |
| **死区** `DEAD_DP` | **常量 16dp** | 不可调 | 抖动不启动拖动;死区内给 `DEAD_SLIP=0.33` 权重的最小响应 |
| **提交线** | 横滑 158/238/324dp(按行宽比例)<br>上滑 50/80/120dp(绝对距离) | 三档可调 | 松手是否算过线 |

**死区为什么必须是常量**(2026-10-02 用户连下四道指令才定案:不随阈值变 → 横竖都固定 →
不用比例 → 改为常量值):

此前两处都取 `触发线 × 1/4`。在 1440px 的候选行上,标准档死区就是 **59dp** ——
手指先走 59dp 屏幕纹丝不动。**这就是"翻页不跟手"的直接来源**:不是没实现跟手,
是前面空了一段那么长。而触发线本来就有三档,死区再跟着放大三档,于是「灵敏」和
「保守」差的不只是触发点,还有一段完全空白的起手距离 —— 调阈值时手感连带乱掉。

**死区不单独设开关**:它是防误触的量,不是手感偏好,调它的人只会去调触发阈值。

**死区与提交线必须成对理解**:`slide()` 在死区内按 `DEAD_SLIP` 打折,给出
"手在动、屏幕微动"的响应;死区只在死区**之前**完全静默。所以「16dp 死区」不等于
「前 16dp 无反馈」,而是「前 16dp 只有 1/3 幅度的微小跟随」。

**过阈值当帧就提交**:与上滑一致(上滑在 MOVE 里 `progress >= 1f` 就 `onFlyCommit`),
横滑此前是**抬手才判**。`setPageDrag` 里越线即 `onPage` + 轻震,手指不用抬。

配套的一处反直觉细节:**拖动中提交时位移不能回零**。`onPageIndexChanged` 的
"整页重切"分支里,若照常 `pageSlide = 0f` 并启动 settle 动画,画面会先跳回旧页
再滑过去,而手指还按着 —— 看着像被弹回去,与"过阈值就翻"想要的连贯正好相反。
必须靠 `pageDragging` 区分:位移接着走、不起动画,动画仍由手指驱动,松手交给
`endPageDrag` 收尾。

**死区一次改了四处**:候选行横滑 `pageDeadPx`、候选行内上滑 `flyDeadPx`、
L5 横滑 `swipeDeadPx`、键盘表面飞字 `FlyDetector.deadPx`(它脱离 View 拿不到
density,由 `ImeService` 注入)。

**从类型上不给"按比例算"留后路**:`progress`/`slide` 的 `dead` 是**必填参数**,
`DEAD_FRAC` 常量与 `dead(threshold)` 函数直接删掉。

---

## 附带推翻的一个测试前提

`the old 24dp flick now falls inside the dead zone` 这条单测的**名字和前提都失效了**:
旧的 24dp 一划在 598dp 逻辑宽对应密度下约 58px,死区改成常量 32px 之后,58px
**已经在死区之外**了 —— 它不再是被死区吞掉,改由提交线(475px)挡住。

结论(24dp 一划不翻页)不变,但原因变了,而且它现在会跟手一小段,比"纹丝不动"好。
断言相应改成"进度 > 0 但 < 1"。**测试名字描述前提,前提被推翻时连名字一起改**,
否则下一个读的人会按旧前提去找一段不存在的因果。

---

## 屏宽类的三个数(2026-09-30,仍然成立)

Titan 2 的屏宽**三个数同时成立**,各自回答不同的问题:

| 量 | 值 | 是什么 | 谁在用 |
| --- | --- | --- | --- |
| 物理面板 | 1440 × 1440 | 物理像素 | 候选行通栏宽 = **1440px**、截图/录屏尺寸 |
| 原生逻辑宽度 | **576dp** | 1440 ÷ 原生 density 2.5 | 站点响应式断点(浏览器 CSS px 这条线) |
| 改后的最小逻辑宽度 | **598dp** | 用户在开发者选项里改过的当前值 | 代码注释里的屏宽、布局算例 |

写注释的规矩:屏宽类写 598dp 并注明"用户改过的最小逻辑宽度";dp→px 换算**不写死
density**(原生 2.5,用户改过后约 2.41);像素尺寸直接写 1440。

**通用教训**:同一台设备的"物理尺寸 / 原生逻辑尺寸 / 用户改过的逻辑尺寸"是三个量,
`dp = px / density`,而 **density 本身是设置项的函数**。任何"从 px 推 dp 然后怀疑
dp 错的推理"都是错的 —— dp 根本不由 px 单独决定。


## Timeline

- time: 2026-09-30T13:23:54
  kind: decision
  summary: "Created this page: 待修:翻页不跟手、动画太短、缓动要非线性"
  source: "2026-09-30 用户报障(真机手感)"
  affects: [paging-feel]

- time: 2026-09-30T13:24:27
  kind: decision
  summary: "翻页三项报障的根因与修法:进度被当位移用致 3 倍放大、死区 8% 行宽、settle 线性且太短"
  source: "2026-09-30 用户报障 + 代码核算"
  affects: [paging-feel]

- time: 2026-09-30T14:47:49
  kind: decision
  summary: "三项全部修完(跟手/时长/非线性)+ 惯性物理、橡皮筋、震动开关;记录单测拦下的自身 bug"
  source: "2026-09-30 实现 + 单测结果"
  affects: [paging-feel]

- time: 2026-09-30T15:48:21
  kind: decision
  summary: "Titan 2 参数校正:1440×1440 / density 2.5 / 逻辑宽 576dp,清掉代码里按 598dp×2.4 推的错数"
  source: "2026-09-30 用户给出准确分辨率与逻辑宽度"
  affects: [paging-feel]

- time: 2026-09-30T15:57:11
  kind: decision
  summary: "Titan 2 参数分清两个量:物理 1440×1440(px,通栏宽)vs 逻辑宽 598dp(用户在手机改的最小逻辑宽度)"
  source: "2026-09-30 用户澄清:598dp 是他改的最小逻辑宽度"
  affects: [paging-feel]

- time: 2026-10-01T01:33:56
  kind: decision
  summary: "Titan 2 屏宽:1440×1440 物理 / 576dp 原生 / 598dp 用户改的最小逻辑宽度,三者并存都对"
  source: "2026-09-30 用户:576 和 598 都是对的"
  affects: [paging-feel]

- time: 2026-10-02T10:50:05
  kind: decision
  summary: "死区从「触发线×1/4」改为常量 16dp,且从类型上删死比例(dead 改必填参数);横滑过阈值当帧即翻"
  source: "2026-10-02 用户四轮指令:死区不随阈值变 → 横竖都固定 → 不用比例计算 → 改为常量值"
  affects: [paging-feel, release-flow]

- time: 2026-10-02T10:50:27
  kind: decision
  summary: "死区改为常量 16dp(横滑/上滑/L5 共用),过阈值当帧提交;v0.4.8 已发布并验过站点哈希"
  source: "2026-10-02 实现 + 250 个单测 + 真机口径的重新推导"
  affects: [paging-feel]
