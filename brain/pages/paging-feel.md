---
id: paging-feel
title: "待修:翻页不跟手、动画太短、缓动要非线性"
category: decision
status: active
tags: [gesture, animation, paging]
created: "2026-09-30T13:23:54"
updated: "2026-10-09T08:53:32"
---

<!-- compiled_truth -->
---
id: paging-feel
title: "跟手不动点:基线每帧被重设;单测覆盖不到 View 私有状态"
category: decision
status: active
tags: [gesture, animation, paging, threshold, testing-gap]
created: "2026-09-30T13:23:54"
updated: "2026-10-02T13:10:00"
---

<!-- compiled_truth -->
## 头号根因:「每帧重设基线」形成不动点,位移锁死

2026-10-02 用户连报三轮才定位到:「翻页到头的弹性动画和跟手动画还是没有」
→「翻页触发不了了」→「**左右翻页仍然无法成功触发 全部都是打断后的反向动画**」。

**每一轮 symptom 都指向动画参数,每一轮的真正根因都在别处。** 最后这条
「全部都是反向动画」才是决定性的 —— 它说明 `travel` 恒为 0。

`takeOverPageDrag` 是 MOVE 分支里**每帧**调的,而 `beginPageDrag` 旧版**无条件下**
重设基线(`pageDragBaseX = x + pageSlide * pageDir`)。于是每帧形成不动点:

```
baseX  = x + pageSlide * dir
travel = -(x - baseX) * dir = pageSlide      ← 恒等于当前位移
slide  = slide(travel, …)      = pageSlide   ← 位移原地不动
```

手指在动、位移锁死在 0 ⇒ 跟手从来没有过、提交线永远到不了,
抬手一律走 `endPageDrag` 的反向收回分支。同一处的 `pageSpeed.reset()` 每帧执行
还有第二个后果:**惯性速度恒为 0**,甩也甩不动。

**为什么前几轮方向全错**:症状「跟手没实现」与「橡皮筋那次真 bug」完全一样,
所以默认去调死区/阈值/缓动。而**区分这两者的判据是「有没有跟手」以外的** ——
「全部走反向」说明进度恒 0,说明**状态**坏了,不是参数坏了。

## 第二条:单测覆盖不到 View 私有状态,别拿测试全绿当证据

253 个单测在**改之前也全过**。测试覆盖的是纯函数 `pageDragTravel` /
`pageDragSlide` / `DragGesture` —— 它们一直是对的。坏的是 `beginPageDrag` 这个
View 私有方法里的**每帧状态重置**,没有任何测试能看见。

**所以「测试全绿」在这个项目里不等于「手势是对的」。** 判断手势行为要看:
纯函数之外,`View` 里的状态机是否满足「只在起手时改基线」这类不变量。

## 第三条:`RubberDrag` 当年的那条结论是错的

`RubberDrag` 类注释里写着:「翻页那边没这个毛病,因为 `beginPageDrag` 在
`pageFrom/pageTo` 非空时**不**重置 —— 不对称就在这儿」。

**`beginPageDrag` 确实不重置两个角色,但它每帧重设基线。**
`RubberDrag.begin` 有「同方向重复调用是空操作」的守卫,`beginPageDrag` 一直没有。
**不对称的不是「角色」,是「基线」。**

**教训:一条"某处没这个毛病"的结论,要去核实那个"某处"到底在做什么,
而不是只看它字面上没做什么。** 当年这条错误结论直接把后来的三轮排查带偏。

## 改法与它的边界

给 `beginPageDrag` 补上与 `RubberDrag.begin` 对称的守卫:**同方向、且本来就是手指
在驱动 ⇒ 空操作**,基线与速度都不动。只有三种情况算真正起手:

1. 角色还没定(首次起手);
2. 方向反了(两个角色对调、位移取镜像,基线要跟着换算);
3. 同方向但手指**不在**驱动(接管上一次松手后正在飞的动画)—— 此时基线要重定到
   当前位移,否则新落指的 x 与上一条手势的基线毫无关系,位移会跳。

首次起手顺带清 `pageCommitted`:那是上一条手势的结论,不该被继承。

**边界(诚实记一笔)**:这个 bug 反复发生,是因为"每帧重设基线"这件事活在 View 私有
方法里,**类型管不住**。彻底做法是把翻页拖动状态也抽成纯类(与 `RubberDrag` 同一形状),
让"基线只在起手时设"成为可单测的不变量。v0.5.1 只保证行为正确,没扩大改动面;
若再复现就把 `PageDrag` 抽出来。

---

## 同一个手势有两条实现时,「有没有动画」取决于手落在哪

同一个「翻页」曾有两套实现:候选行收得到触摸,键盘表面的横滑走
`ImeService` 里 `ACTION_UP` 判一次 → `controller.pageStep(±1)` 的老路,
**抬手才翻、整条拖动不画位移、到头什么都不发生**。

**判断报障时先问一句:这个手势有几条入口?** 入口多于一条而只有一条被改,
症状就精确地表现为「改了还是那样」。候选栏/键盘表面/L5 三处滑动入口、
九处选字入口,都是这个形状。

## 合并成一份实现后,要逐个核对每个输入量的口径

合并后键盘表面「跟手有了、翻页触发不了」,两个原因叠着:

1. **死区被扣了两次。** `beginPageDrag` 基线在"死区被跨过那一帧",那 32px 死在基线
   **之前**;档位 60px 按**手指总行程**定。不换算要划 92px。换算后提交线 28px,而
   `progress` 分母 `threshold - dead` = `28 - 32` = **负数,进度恒 NaN**。
   所以外部驱动时死区必须传 **0**。
2. **`externalCommitPx` 漏进候选行自己的翻页。** 那是常驻值,用 `!= 0` 判断
   「是不是外部驱动」会把候选行提交线也改成 60px。改用每次起手重标的 `externalDrive`。

| 入口 | 基线 | 提交线 | 死区 |
| --- | --- | --- | --- |
| 候选行自己划 | downX | 行宽 × `pageCommitFrac` | `pageDeadPx` |
| 键盘表面划 | 死区跨过那一帧 | `externalCommitFromBase(60, 32)` = 28 | **0** |

## 打断一律反向,不要跳变

| 打断 | 原行为 | 改成 |
| --- | --- | --- |
| 橡皮筋 → 翻页 | `cancelRubber` 清零 | `settleRubberBack()` 从当前 raw 弹回 0,不弹轻震 |
| 翻页 → 橡皮筋 | 两个 active 同时为真,`pageDragging` 永远清不掉 | `reversePageTurn()` 动画收回原页,已提交的还撤回来 |
| 橡皮筋还在弹 + 翻页已开始 | `onDraw` 里二选一 | `drawPageTurn(canvas, extraSquash = rubberShift())` 两层形变合成 |

**合成而不是二选一**:先弹完再翻的话,橡皮筋一归零整页会突然出现在**已经走过的**
位移上。**两个互斥的状态必须由同一个函数收干净**,不能靠调用方记得先解一个。

**退场键同理**:L4/L5 消失的键原本「朝行外平移 + 淡出」,一边淡出一边横穿还留着的
那几把,读起来像布局在乱挤。用户要**原地消失** ⇒ 只淡出、位置一帧不动。

---

## 死区:常量值,不是比例

| 量 | 取值 | 可调? | 管什么 |
| --- | --- | --- | --- |
| **死区** `DEAD_DP` | **常量 16dp** | 不可调 | 抖动不启动拖动;死区内给 `DEAD_SLIP=0.33` 权重的最小响应 |
| **提交线** | 见上表 | 三档可调 | 过线/松手是否算翻页 |

此前两处都取 `触发线 × 1/4`,在 1440px 的候选行上标准档死区是 **59dp** ——
手指先走 59dp 屏幕纹丝不动。死区不单独设开关:它是防误触的量,调它的人只会去调
触发阈值。**死区与提交线必须成对理解**:`slide()` 在死区内按 `DEAD_SLIP` 打折,
所以「16dp 死区」不等于「前 16dp 无反馈」。

**从类型上不给"按比例算"留后路**:`progress`/`slide` 的 `dead` 是**必填参数**,
`DEAD_FRAC` 与 `dead(threshold)` 直接删掉。

**过阈值当帧就提交**。配套的反直觉细节:**拖动中提交时位移不能回零** ——
`onPageIndexChanged` 的"整页重切"分支若照常 `pageSlide = 0f` 并启动 settle 动画,
画面会先跳回旧页再滑过去,而手指还按着。必须靠 `pageDragging` 区分。

**附带推翻的测试前提**:`the old 24dp flick now falls inside the dead zone` 的名字和
前提都失效了 —— 旧的 24dp 一划(58px)**已经在常量死区之外**,改由提交线挡住。
结论不变但原因变了。**测试名字描述前提,前提被推翻时连名字一起改。**

---

## 屏宽类的三个数(2026-09-30,仍然成立)

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

- time: 2026-10-02T11:39:50
  kind: reversal
  summary: "翻根因:键盘表面横滑走的是另一条 UP 才判的老路,动画从来没写在那条路上;两条路改为共用一份实现"
  source: "2026-10-02 用户报障「翻页到尽头的弹性动画和跟手动画还是没有」+ 代码核算"
  affects: [paging-feel]

- time: 2026-10-02T11:40:30
  kind: decision
  summary: "翻页跟手/弹性只有一份实现(候选行与键盘表面共用);提交线各按各的量纲;v0.4.9 已发布并验过站点哈希"
  source: "2026-10-02 真机报障定位 + 251 个单测 + 站点验哈希"
  affects: [paging-feel]

- time: 2026-10-02T12:15:34
  kind: reversal
  summary: "死区被扣两次导致外部拖动进度 NaN;打断一律反向而非跳变;退场键只淡出不横移"
  source: "2026-10-02 用户连报三条:翻页触发不了 / 打断要反向动画 / L4L5 应原地消失"
  affects: [paging-feel]

- time: 2026-10-02T12:16:07
  kind: decision
  summary: "死区只算一次;打断一律反向;退场键原地淡出;v0.5.0 已发布并验过站点哈希"
  source: "2026-10-02 三条真机报障 + 253 个单测 + 站点验哈希"
  affects: [paging-feel]

- time: 2026-10-02T12:37:52
  kind: reversal
  summary: "跟手的真正根因:基线每帧被重设成 pageSlide,形成不动点;单测覆盖不到 View 私有方法"
  source: "2026-10-02 用户报「左右翻页仍然无法成功触发 全部都是打断后的反向动画」"
  affects: [paging-feel]

- time: 2026-10-02T12:38:31
  kind: decision
  summary: "跟手不动点的真正根因与「单测覆盖不到 View 私有状态」这条教训;v0.5.1 已发布并验过站点哈希"
  source: "2026-10-02 真机报障定位 + 站点验哈希"
  affects: [paging-feel]

- time: 2026-10-05T09:34:16
  kind: evidence
  summary: "2026-10-05 翻页提交链的完整时序(pageStep 埋点后拿到):begin# 打 8 次(一次手势!),rolesSet 从 false 变 true 后其余全是「同向+正在拖」的空操作;pageCommitted 在 slide=303 过 thr=302 那一刻才变 true ⇒ setPageDrag 的当帧提交生效 ⇒ onPage(1) ⇒ pageStep 0→1;抬手时 willSend=false 是**正确**的,它防的正是同一次手势提交两遍(page 会 0→1→2)。上一轮那次 page 停在 0 不是 end 的锅,而是那一轮当帧提交压根没发生。"
  source: "2026-10-05 E2E run 37289078764 真机 full.log"
  affects: [paging-feel, e2e-capture]

- time: 2026-10-08T18:16:09
  kind: evidence
  summary: "2026-10-08 真机实测:翻页 settle 在 ζ=1.0(临界阻尼,理论上不振荡)下仍超调 33px(ring 第1次 value=1393.15 target=1360),PAGE_TARGET_SPEED 已从 1800 降到 1500(response 455→530ms)仍不够。真正的变量是 entryV —— response 只按剩余行程算,没算抬手速度,而「距离短+速度快」正是最易过冲的组合。page-interrupt 场景断言「每段最多穿过目标 1 次」实测 3 次。⇒ response 的公式缺速度项,这是 v0.7.4 调 ζ 之后没人验过的组合。"
  source: "2026-10-08 Titan 2 page-interrupt 实测"
  affects: [paging-feel, spring-damping-direction]

- time: 2026-10-09T08:53:32
  kind: reversal
  summary: "推翻 2026-10-08 那条:「过冲的唯一变量是 entryV,response 公式缺速度项」不是根因。真正的根因是 spec 的 ζ 从未送到引擎(实际跑 androidx 默认 0.5),所以调 response 与调 ζ 都改不了过冲。修好之后 page-interrupt 的 ring 记录为零穿越"
  source: "2026-10-09 真机逐帧轨迹拟合 + getDampingRatio() 回读,commit c928fb8"
  affects: [paging-feel, spring-damping-direction]

- time: 2026-10-09T08:53:32
  kind: decision
  summary: "三条补完动画统一走 PageTiming.withResponse(行程 + 进场速度取大);page-interrupt 判据改为振幅单调递减 + 首过 ≤1px;page-interrupt-live 判据改为 takeover 行的 springRunning=true"
  source: "2026-10-09 真机 E2E 13/13"
  affects: [paging-feel]
