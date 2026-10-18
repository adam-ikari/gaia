---
id: rime-candidate-consumption
title: "选词消费:由 GetCurrentStartPosition() 给,依据在 Segment::Close 与 Forward"
category: decision
status: active
tags: [rime, pinyin, 选词, 消费, 选字]
created: "2026-10-05T05:08:57"
updated: "2026-10-18T02:14:54"
---

<!-- compiled_truth -->
## 单位是**含撇 raw 的直接下标**,由 `GetCurrentStartPosition()` 给 —— 第三次定案(2026-10-18)

2026-10-05 修过一次(`commit_text_preview` 那条路是错的),2026-10-06 又推翻过一次,
**第二次推翻是错的,已撤回**。2026-10-17 曾把结论定为「两族坐标系、生产数非撇字符」,
**同日用户选路线「做分隔符的提交」:手工分节符直接喂引擎。生产的 `'` 不再跳过,
探针族与生产族合并 —— 两族问题就此终结。**

### 依据(探针 = 生产,坐标同族,证词直接有效)

- 探针 `run.sh "ni'hao"`:菜单含「你」(index 4),选中 ⇒ `consumed=3`,
  seg `[0,3)` **把撇一起算进覆盖段**(撇属于被吃掉的那一段),剩 `"hao"`。
- 探针 `run.sh "zhong'guo"`:选「中古」⇒ `consumed=8`,剩 `"o"`
  (两音节**之间**的撇也在覆盖段中:zhong'gu = [0,8))。
- 探针 `run.sh "ni'"`(尾撇)、`run.sh "xi'an"`(手工强制切分,直出西安):
  尾撇正常被引擎处理,`speller.delimiter: " '"` 原生认识撇,喂进去就是强制音节边界。

`Context::Select` → `ConcreteEngine::OnSelect` → `seg.Close()` + `Forward()`
的源码链不变;变的只是喂进引擎的串:**喂串 == state.raw**(见下)。

### 坐标恒等性(新前提,违反即消费切点错位)

引擎坐标当 raw 的**直接下标**,靠的是「喂引擎的串 == `state.raw`」。
这条由**入口**保证:`ImeState.appendRaw` 吞掉开头的撇与连续的撇
(raw 里撇不连续、不开头),于是 `PinyinInputMethod.normalize` 在生产输入上恒等。
入口 invariant 与 normalize 兜底两层都要在;破坏时会先在单测里现形
(`ImeStateRawInvariantTest` / `PinyinConsumptionTest`)。

### 已修(2026-10-18,#1 项最终形态)

- `RimeEngine.query`:跳撇循环删除,`'` 原样喂(旧注释「librime 不认识分节符」是错的)。
- `rawAfterConsuming`:**下标语义**(夹长 + 切点落撇丢撇的兜底)。
- `displaySpelling`:映射表撤掉,直接下标;接缝补撇只补缺(prefix 尾撇 / tail 首撇已有则不再补)。
- `ImeState.appendRaw`:分隔符去重(开头/连续吞掉)—— 恒等性的入口保证。
- 注释全链统一含撇口径:`readRemainder` @param、`PinyinConsumption` @param、
  `SyllableSpelling` @property、`PinyinInputMethod.normalize`、`nativeTopSpelling`(cpp)。
- 单测:`PinyinConsumptionTest` 13 条重钉(含 zhong'guo 下标 6/8 探针锚点)、
  `ImeControllerSpellingTest` 撇号用例重钉、新增 `ImeStateRawInvariantTest` 7 条;全量 377 绿。

### 教训(续写)

- **推翻一个结论的门槛要高于建立它**;观测要标明**在哪一族坐标系**里取的——
  当两族并存时,证词不可迁移(那次误引持续了 11 天才清账)。
  终极解法不是「分清两族」,而是**把族合并**:上午分清了两族,下午发现
  正确答案是消灭一族 —— 分清是必要的,却不是终点。
- 同日同一问题的答案是「跳过」「数非撇」「喂进去」三次反转:每次反转都要
  把**全链注释 + 单测期望**一起翻,留半链注释就得错半路(这次两把都翻干净了)。
- 拿「引擎不认识 X」当跳过 X 的理由之前,先喂一次试试 —— `delimiter`
  明明白白写在 schema 里。


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

- time: 2026-10-09T11:20:08
  kind: evidence
  summary: "用户确认「部分消费后继续出候选已经没有问题」。补的 setState:/draw: 埋点证明视图侧一直正常(n=45 slots=5 nonNull=5,画的是 hao 的新候选),所以那次报告与对账自杀是同一根因"
  source: "2026-10-09 真机埋点 + 用户确认"
  affects: [rime-candidate-consumption]

- time: 2026-10-16T02:51:22
  kind: reversal
  summary: "2026-10-16 连续消费真 bug:consumed 是**绝对坐标**(相对最初输入),不是相对当前 composition。探针新增 RIMEPROBE_CHAIN(同一 session 反复选单字)实测 zhongguoren:选「中」GetCurrentStartPosition 给 5、再选「国」给 8(不是相对新段的 3)、选完给 0。上层 readRemainder 一律拿它当**相对量**去截,而 state.raw 在第一次消费后已是尾巴 ⇒ 第二次起按绝对坐标多截、coerceAtMost 夹到尾部后 substring 得空串,剩余拼音连候选一起丢光(症状:连续单字消费几个字后剩余拼音不再出候选)。修:ImeState 加 rawBase(当前 raw 起点在引擎输入串里的偏移):refreshQuery 的 query 重建后归 0,消费后置 = 绝对 consumed,readRemainder 截前先算 rel=consumed-rawBase。cd04b5a 的探针只测单次 Select,而两种语义在单次下同值,所以这个累积错结构性不可见 —— 单测也一直绿。新增 ImeControllerTest 连续消费用例 + FakePinyinInputMethod.withConsumedSeq(绝对坐标序列)。"
  source: "2026-10-16 探针 chain + 单测"
  affects: [rime-candidate-consumption]

- time: 2026-10-18T01:42:27
  kind: decision
  summary: "2026-10-17 梳理撇号坐标:两族坐标系分家定案 —— 生产跳撇(去撇坐标)vs 探针喂撇(含撇坐标,luna_pinyin 原生认撇);rawAfterConsuming 修成非撇字符计数,与 displaySpelling 同族"
  source: "2026-10-17 梳理拼音消费(用户指令)"
  affects: [rime-candidate-consumption]

- time: 2026-10-18T02:14:54
  kind: decision
  summary: "2026-10-18(喂撇定案):手工分节符直接喂引擎,坐标全族统一为含撇下标,两族问题终结"
  source: "2026-10-17 用户挑路线「做分隔符的提交」;探针 run.sh ni'hao/z hong'guo 端点实测"
  affects: [rime-candidate-consumption]

- time: 2026-10-18T02:14:54
  kind: reversal
  summary: "喂撇定案:手工分节符原样喂引擎(query 跳撇删除),坐标全族统一为含撇下标,探针与生产并族;rawAfterConsuming 切回下标语义、displaySpelling 直映,ImeState.appendRaw 增加分隔符去重恒等性前提,全量 377 测试绿"
  source: "2026-10-17 用户「做分隔符的提交」;探针 ni'hao end=3 / zhong'guo consumed=8 实测"
  affects: [rime-candidate-consumption]
