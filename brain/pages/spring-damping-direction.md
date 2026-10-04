---
id: spring-damping-direction
title: "阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
category: decision
status: active
tags: [animation, physics, spring, damping]
created: "2026-10-03T11:23:42"
updated: "2026-10-04T06:15:36"
---

<!-- compiled_truth -->
## 症状与根因

2026-10-04 用户在修完翻页落点之后报「弹性太强」。

**这不是有人调了参数,是行程变长把一个隐性比例放大了。**

过冲比 `exp(-πζ/√(1-ζ²))` 与**行程无关**,但**过冲的绝对像素 = 比例 × 行程**:

| ζ | 过冲% | 100px 行程 | 1440px 行程 |
| --- | --- | --- | --- |
| 0.72 | 3.84% | 3.8px(肉眼为零) | **55px**(明显回弹) |
| 0.90 | 0.15% | 0.2px | **2.2px**(肉眼接近零) |

落点 bug 修好之前,松手时 `pageSlide` 离落点只有一百多 px,所以 3.84% 是无害的。
当年 `SpringSpec.DEFAULT` 的注释写着「翻页的过冲量约 3.4%,一整页 1440px 也就 50px,
读起来是啪地到位」—— **那是在行程从未达到整页的前提下做的估算**,
而当时的行程确实到不了。修好之后行程真的是 1440px,同一个比例就变成 55px。

⇒ **`PAGE_COMMIT` 从 0.72 降到 0.9**。选 0.9 不选 1.0:临界阻尼是所有不过冲曲线里
最慢的一条,为零弹性接受更慢的收敛不划算。

**教训:阻尼比是「比例」,手感是「绝对量」。** 只记比例会忘乘行程 ——
而行程是会变的(被别的 bug 修好、被手势口径重设)。凡是**比例 × 某个可变量**
的量,都要同时盯住那个变量。

## 提交/收回两支弹簧(同一轮定下)

| | ζ | 过冲 | 语义 |
| --- | --- | --- | --- |
| `PAGE_COMMIT` | 0.9(欠) | 2.2px | 确认动作要冲量,但要小 |
| `PAGE_ROLLBACK` | 1.9(过) | 0 | 放弃要干脆,不来回晃 |

`SpringChannel.settle` 加了可选 `spec` 参数(默认不变,既有调用方零改动),
`pageSpring` 一支通道按次换 spec。

## 死区拆成横滑/上滑两个常量

同一轮用户要「翻页触发阈值更小」+「降低死区」+「不改上滑」——
**这三条在共用一个常量时是冲突的**。

2026-10-02 用户明确要求横竖统一(原话「左右滑动和上下滑动的死区都是固定大小」
「死区改为常量值」),当时还从 16dp 降到 8dp。现在按用户选择拆开:

- `DEAD_DP_SWIPE = 4f` —— 横滑(翻页):`CandidateBarView.pageDeadPx`、
  `L4PanelView.swipeDeadPx`(L5 是横滑)、`FlyDetector.swipeDeadPx`(含抬手兜底)
- `DEAD_DP = 8f` —— 上滑(飞字):**一字未动**

效果:翻页手指总行程 `16+8=24px` → `8+8=16px`(屏宽 1.7% → 1.1%)。

**教训:「统一成一个常量」和「两个方向要不同」不能同时要。**
真出现这种冲突时,拆开的代价比统一大 —— 这一带已经因为「死区被减两次」
出过 bug(`pageDragDeadPx` 在键盘面驱动时必须返回 **0** 而不是本常量,
因为基线定在「死区被跨过的那一帧」,死区已走完,再扣一次会让
`progress` 分母变负 ⇒ NaN)。所以新增常量后**每个消费者都要单独确认量纲**。

## 提交距离三档本次没动

键盘面 px 列(4/8/20)与候选行/L5 比例列(0.16/0.22/0.33)都没动 ——
用户只要求降死区。

## 单测钉「语义」不钉「数」

- 提交的过冲换算成**绝对像素**并要求 < 4px:钉的是「看不看得见」,
  不是钉阻尼比(比例本身不变,变的是行程)。
- 横滑死区必须**小于**上滑且两者**不相等**:防有人觉得「统一更简洁」合回去。

**上一轮的两条教训仍然成立**:过冲公式只对欠阻尼成立(ζ≥1 解是纯指数衰减,
峰值就是 1);百分比阈值不能随手拍。


## Timeline

- time: 2026-10-03T11:23:42
  kind: decision
  summary: "Created this page: 阻尼方向:确认动作要欠阻尼,回弹要过阻尼;速度只进一次"
  source: created via brain create-page
  affects: [spring-damping-direction]

- time: 2026-10-03T11:24:06
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 动画重做:用户连报三条症状后定位"
  affects: [spring-damping-direction]

- time: 2026-10-04T04:30:21
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 修翻页收尾落点 + 提交/收回分两支弹簧"
  affects: [spring-damping-direction]

- time: 2026-10-04T06:15:36
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-04 ζ 0.72→0.9:过冲比例 × 行程 = 隐性参数被行程放大"
  affects: [spring-damping-direction]
