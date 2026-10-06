---
id: rime-candidate-consumption
title: "选词消费:单位是**段**不是字符 —— 前两次都取了对的量、错的模型"
category: decision
status: active
tags: [rime, pinyin, 选词, 消费, 选字]
created: "2026-10-05T05:08:57"
updated: "2026-10-06T09:44:56"
---

<!-- compiled_truth -->
## 两次都错了,因为错的是**模型**本身:不是「量从哪来」

2026-10-05 我两次修同一个症状,两次都在**同一个错误模型**里:

| 版本 | 取「吃掉的量」的方式 | 错在哪 |
| --- | --- | --- |
| v0.7.10 之前 | `commit_text_preview` 减去选中词 | 它是**预览**(每段填候选文字),不是输入记录 |
| v0.7.11 改成 | `Composition::GetCurrentStartPosition()` | 它是 `back().start` —— **末尾段的起点**,不是吃掉的字符数 |

两次都默认了一件事:**选完候选之后,composition 里剩下的是「未消费的输入」**。
那个前提是错的。

## 源码级定论(2026-10-06 读 librime src/rime/)

```cpp
// context.cc:118  Context::Select
bool Context::Select(size_t index) {
  if (composition_.empty()) return false;
  Segment& seg(composition_.back());   // ← 只看**末尾那一段**
  if (auto cand = seg.GetCandidateAt(index)) {
    seg.selected_index = index;
    seg.status = Segment::kSelected;   // ← 只打标记
    select_notifier_(this);
    return true;
  }
  return false;
}

// segmentation.cc:135
size_t Segmentation::GetCurrentStartPosition() const {
  return empty() ? 0 : back().start;   // ← 也是 back()
}
```

⇒ **`Select` 不消费前缀**,它只把末尾段标成 `kSelected`。
⇒ 因此选完之后 composition 里**还是 `nihao`**,`GetCurrentStartPosition()`
在单段输入下就是 **0** ⇒ 上层走 `clearComposition()` ⇒ 缓冲清空、候选置空。

## 为什么这个错特别能骗人

librime 的模型是**段**,不是字符偏移:

- `back().start` 在**多段**输入(`ni'hao`)下恰好非 0 且看起来像偏移量 ⇒ **半对**
- 单段(`nihao`)下是 0 ⇒ 完全错

而绝大多数输入是单段。所以「多次消费看起来在工作」与「它根本不对」同时成立。

这与 `paging-feel` 里那条教训同一个形状:**判据要在最常见的情形下也分得清,
不能只在少数情形下成立。**

## 由此作废的实现

2026-10-06 加的 `InputMethod.currentCandidates(pinyin, limit)`
(选完直接读 `ctx.menu`,不再 `clear_composition` + 逐字重喂)**建立在同一个错模型上**:
此刻 composition 还是 `nihao`、菜单仍是末尾段的菜单 ⇒ 它必然无效。

真机实测:开发版 `0.7.11-dev.15337f3` 里确认含那个改动
(`git merge-base --is-ancestor 9f93443 15337f3` 通过),**而症状不变**。

## 正确方向(未实施)

单位必须是**段**,不是字符:

1. 选完让 librime **推进到下一段**(`select_notifier_` 之后由谁做要读源码),
   而不是去算「还剩几个字符」
2. `state.raw` 与 librime 的分段要按**段**对齐 —— 现在的 `rawAfterConsuming`
   是按字符切的
3. 手动分隔符已取消(用户 2026-10-06「自动分割 无需分隔符」),
   所以 `state.raw` 的分段只能来自物理键盘的撇(`ascii_segmentor` 仍在链上)

## 判据本身也要重写

`tools/aitest` 的 `consume-partial` 现在断言 `after == raw_before.slice(consumed)`
—— 那是**字符偏移**模型的断言,修法换了以后它本身要重写。

已补的 `n > 0` 断言(剩余拼音必须有候选)与单位无关,可以留 —— 它正是这次
唯一没被模型污染的那条。


## Timeline

- time: 2026-10-05T05:08:57
  kind: decision
  summary: "Created this page: 选词消费:吃掉的量必须由引擎给,不能从 commit_text_preview 反推"
  source: "2026-10-05 真机 E2E 日志 + librime 源码"
  affects: [rime-candidate-consumption]

- time: 2026-10-05T05:09:07
  kind: reversal
  summary: "推翻「commit_text_preview = 未消费的原始输入 + 已选中的候选文字」这个模型:Composition::GetCommitText 里每个段都填候选文字(Segment::selected_index 默认 0,else 分支几乎走不到),所以剩下那部分是汉字。真机实测 nihao 选「你」把缓冲截成 n(应 hao)。改用 Composition::GetCurrentStartPosition() 直接问引擎要 consumed 的个数。"
  source: "2026-10-05 真机 E2E 日志 + librime src/rime/composition.cc + segmentation.h"
  affects: [rime-candidate-consumption, librime-android-build, e2e-capture]

- time: 2026-10-05T05:09:20
  kind: decision
  summary: "顺带修 ImeController.selectCandidate 给引擎的是页内下标:librime Select(index) 索引整份菜单(0..44),两者只在第 0 页相等。上屏的字不受影响(用我们自己的候选表),症状是「翻页后缓冲被截错」,第 0 页完全正常。改为 globalIndex = page * pageSize + li。"
  source: "2026-10-05 读 ImeController.selectCandidate 与 librime Context::Select/menu.cc 的索引口径"
  affects: [rime-candidate-consumption, paging-feel]

- time: 2026-10-06T09:39:34
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-06 读 librime 源码 context.cc/segmentation.cc 定论"
  affects: [rime-candidate-consumption]

- time: 2026-10-06T09:44:56
  kind: evidence
  summary: "currentCandidates(读 ctx.menu 而不重建)建立在与前两次相同的错模型上,所以必然无效 —— 此刻 composition 还是 nihao、菜单仍是末尾段的菜单。用户没有 adb,给不了 logcat,所以**日志路线整条不可用**;能定论的是 librime 源码。这条也说明:修复前先确认「现象是不是我以为的那个现象」,否则改的是别的地方"
  source: "2026-10-06 真机实测:开发版 15337f3 里含改动而症状不变"
  affects: [rime-candidate-consumption]
