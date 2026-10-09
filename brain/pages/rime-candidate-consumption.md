---
id: rime-candidate-consumption
title: "选词消费:由 GetCurrentStartPosition() 给,依据在 Segment::Close 与 Forward"
category: decision
status: active
tags: [rime, pinyin, 选词, 消费, 选字]
created: "2026-10-05T05:08:57"
updated: "2026-10-09T10:16:34"
---

<!-- compiled_truth -->
## 单位是**引擎坐标的字符偏移**,由 `GetCurrentStartPosition()` 给 —— 这条成立

2026-10-05 我修过一次(`commit_text_preview` 那条路是错的),2026-10-06 又**推翻**过一次。
**第二次推翻是错的,已撤回。**

### 为什么不许只读 `Context::Select` 就下结论

`Context::Select`(`context.cc:118`)确实只做了两件事:

```cpp
Segment& seg(composition_.back());
seg.selected_index = index;
seg.status = Segment::kSelected;
select_notifier_(this);   // ← 我 2026-10-06 漏掉了这一行
```

`select_notifier_` 是**承重**的:`engine.cc:75` 订阅了它

```cpp
context_->select_notifier().connect([this](Context* ctx) { OnSelect(ctx); });
```

`ConcreteEngine::OnSelect`(`engine.cc:259`)才是真正干活的:

```cpp
Segment& seg(ctx->composition().back());
seg.Close();
if (seg.end == ctx->input().length()) {
  seg.status = Segment::kConfirmed;
  if (ctx->get_option("_auto_commit")) ctx->Commit();   // 本项目**没配**这个开关
  else ctx->composition().Forward();
} else {
  ctx->composition().Forward();
  ... Compose(ctx);
}
```

**两处关键:**

1. `Segment::Close()`(`segmentation.cc:17`)**明确实现了部分消费**:

   ```cpp
   void Segment::Close() {
     auto cand = GetSelectedCandidate();
     if (cand && cand->end() < end) {
       // having selected a partially matched candidate, split it into 2 segments
       end = cand->end();
       tags.insert(kPartialSelectionTag);
     }
   }
   ```

   librime 本来就为「选中只覆盖一段的候选」设计了这个行为,并给它打了标记。

2. `Segmentation::Forward()`(`segmentation.cc:114`)追加一个**起点 = 刚吃掉那段末尾**的空段:

   ```cpp
   if (empty() || back().start == back().end) return false;
   push_back(Segment(back().end, back().end));
   ```

⇒ 选完之后 `back().start` = 刚吃掉那段的长度,`GetCurrentStartPosition()` 就是它。

**所以 `rime_jni.cpp` 那个注释是对的,`GetCurrentStartPosition()` 不是「末尾段起点」那么笼统的说法 —— 在 `Select` 之后,它是「被选中的那段的终点」。**

### 分词链是全的(`luna_pinyin.schema.yaml:82`)

```yaml
segmentors:
  - ascii_segmentor
  - matcher
  - abc_segmentor
  - affix_segmentor@alphabet
  - affix_segmentor@pinyin     # ← 自动按拼音音节切,连读分节的主力
  - punct_segmentor
  - fallback_segmentor
```

`xian` → 西安 走的是 **`matcher`**(按整串查词典),不是音节切分。
`xian` 恰好也是合法音节所以不矛盾,但**两条路是两条**,说成一条就是错的。

### 仍然没有定论的那一件

`Select` 走完之后「剩下的部分」到底以什么形态存在,以及为什么真机上候选栏会空,
**至今没有运行时证据**。手头唯一的运行时记录是 2026-10-05 的:

```
consume: index=0 word="妳" consumed=0 preview="null"
```

按上面的源码链,单音节 `ni` 选「妳」时 `Forward()` 应推出 `Segment(2,2)`,
`GetCurrentStartPosition()` 应当是 **2**,且 composition 非空 ⇒ preview 不该是 null。
**实测与源码推导不符,差在哪没查清。**

用户没有 adb、给不了 logcat,本机模拟器不可用 ⇒ **拿运行时证据的三条路全断**。
所以现在的状态是:**模型可信(源码支持),但与实测有矛盾,矛盾未解释。**

要往下走必须先有一条**用户能看见**的通道(比如状态行上显示
`raw / consumed / rest / n`),而不是继续推理。

### 教训(比结论本身更值钱)

2026-10-06 我因为只读了 `Context::Select` 的函数体,就把一个**成立了两天**的结论
推翻并写进这一页的正文,还顺手把标题也改成新结论 —— 形态上是「纠正」,实质是
**把对的换成了错的**。

- **涉��� `Select` 的任何结论,必须把 notifier 链走完**
  (`Context::Select` → `select_notifier_` → `ConcreteEngine::OnSelect`)。
- **推翻一个结论的门槛要高于建立它**:至少要有一条与新结论矛盾的**观测**,
  而不是一段「看起来更深的源码阅读」。
- 同一天我还因为 `rg -A12` 截断,得出「方案里只有两个 segmentor」的相反误判。
  两次都是**没读全就开始推**。


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

- time: 2026-10-06T09:56:00
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-06 重读 OnSelect/Close/Forward + schema segmentor 全链;推翻自己同日的错误推翻"
  affects: [rime-candidate-consumption]

- time: 2026-10-06T09:56:11
  kind: reversal
  summary: "**撤回本日前一条的推翻。** 我因为只读 Context::Select 的函数体就断定 GetCurrentStartPosition 是「末尾段起点」,把一个成立两天、源码完全支持的结论推翻并改了标题。实际:select_notifier_ 被 ConcreteEngine 订阅(engine.cc:75)→ OnSelect → Segment::Close()(segmentation.cc:17,注释原文「having selected a partially matched candidate, split it into 2 segments」)→ Forward()(segmentation.cc:114 追加起点=被吃那段末尾的空段)⇒ 选完后 back().start 就是被吃掉的量。教训:涉 Select 的结论必须走完 notifier 链;推翻一个结论的门槛要高于建立它"
  source: "2026-10-06 重读 engine.cc:75/259、segmentation.cc:17/114"
  affects: [rime-candidate-consumption]

- time: 2026-10-06T10:06:15
  kind: reversal
  summary: "**真正的根因不是「量怎么算」,是「什么时候读」。** 探针实测:nihao 选「你」(只覆盖 ni)后引擎给出 consumed=2、两段 seg[0]=0..2 kSelected / seg[1]=2..5 kGuess,新菜单 45 个(好 号 浩 豪 耗)—— **引擎完全正常,菜单就在那儿**。但 ImeController.selectCandidate 把读菜单放在 host.commitText **之后**;commitText 走 setComposingText+finishComposingText,App 组词区从 nihao 变「你」⇒ 系统回调 onUpdateSelection ⇒ ImeService:405 的对账认为组词区被外力破坏 ⇒ abandonComposition() 把整个引擎清掉 ⇒ 读到空菜单。两次旧修复(2026-10-05 走 refreshQuery、2026-10-06 走 currentCandidates)**都栽在同一个位置**——不是读法有问题,是都在 commitText 之后读;重建同样要先问引擎,所以一起空"
  source: "tools/rimeprobe 宿主机实测(2026-10-06)"
  affects: [rime-candidate-consumption]

- time: 2026-10-06T10:06:15
  kind: reversal
  summary: "**撤回我 2026-10-06 那条「重建算不出 hao 的候选」的判断。** refreshQuery 是 clear_composition()+逐字重喂,纯函数,hao 的候选它算得出来。错的是读的时候引擎可能已经被丢掉 —— 重建得先问引擎,所以两条路一起空。注释已改成这条"
  source: "同上"
  affects: [rime-candidate-consumption]

- time: 2026-10-06T14:27:33
  kind: reversal
  summary: "探针又抓到一个单测里结构性不可见的真 bug:`rawAfterConsuming` 的文档写着「喂进去之前 `'` 被剥掉了」,实现据此数**非撇字符**;而 `normalize` 明确**保留** `'`。引擎实测 `ni'ha'o` 选「你」consumed=3、它自己剩下的输入是 `ha'o` == `slice(3)` —— 下标语义,撇算在坐标里。旧实现在同样输入上给 `a'o`。两种语义只在 raw 没撇时相同,而屏上没有撇键、单测用例恰好同值 ⇒ 单测里结构性不可见。同一形状的第五例(「查不到 ≠ 没有什么」变体:错的**理由**比错的代码活得更久,还会被测试的注释加固)。同时记下撤回:我发明的「剩余没候选就当全吃掉」被自己的探针否掉 —— 拿来举证的 `o` 其实有 7 个候选,真正没候选的 `i` 是合法用户输入,夹取会静默丢字符"
  source: "2026-10-06 tools/rimeprobe 实测(ni'ha'o / zhong'guo)"
  affects: [rime-candidate-consumption]

- time: 2026-10-09T10:16:34
  kind: reversal
  summary: "部分消费后继续生成候选词从来没工作过:引擎侧与 App 侧都正确(consumed=2、剩 hao、45 候选、组词区 [1,4) 长度 3),但 onUpdateSelection 的对账把 setComposingText 自己的回声当成外力破坏 —— 一次写入会收到两条中间态(comp=0..1、comp=-1..-1)才轮到真的那条,于是 abandonComposition 自杀"
  source: "2026-10-09 真机逐条量出,commit ab878f9"
  affects: [rime-candidate-consumption, unverified-as-fact, paging-feel]
