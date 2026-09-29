---
id: ime-interaction-model
title: "三层键盘与选字/翻页交互模型（模仿可可）"
category: decision
status: active
tags: [ime, ux, keymap]
created: "2026-09-23T04:27:03"
updated: "2026-09-29T22:51:51"
updated: "2026-09-27T22:55:16"
tags: [ime ux keymap]
---


<!-- compiled_truth -->
模仿可可的交互模型，并按 [[tt2-hardware-constraints]] 重新排布可行通道。总原则：**每个动作至少两条通道（物理键 + 屏幕手势）**。

## 三层键盘

| 层 | 进入方式 | 内容 | 默认映射要点 |
|---|---|---|---|
| L1 基础层 | 默认 | 字母、空格、回车、删除 | 中文态走拼音，英文态直接上屏 |
| L2 符号数字层 | `Alt` | 数字与键帽上档符号 | `q..p = 1234567890`；`a s d f g h j k l ; = ! @ # $ % ^ & * ( )`；中文态输出全角、英文态半角（可配置） |
| L3 编程层 | `Sym` | 每键输出自定义字符串或功能键 | 见下方预置映射 |

**L3 编程层预置**（第 1/2/3 行字母键位上放开发高频符号，位置刻意做成"成对括号相邻"）：

```
q {   w }   e [   r ]   t (   y )   u <   i >   o "   p '
a :   s ;   d =   f +   g -   h *   j /   k _   l |
z \   x `   c &   v %   b #   n @   m $   , ^   . ~   / ｜(管道)
```

空格在 L3 输出 `->`，回车仍为回车，删除仍为删除；`z/x` 组合可再放 `=>`、`!=`、`==`。每个键都可在设置里改成任意字符串或功能键（ESC/TAB/方向键/F5…），与可可"扩展键盘"同构。

## L1–L5 全景：物理三层 + 屏幕两行

| 层 | 载体 | 内容来源 |
|---|---|---|
| L1 / L2 / L3 | 物理键（Alt / Sym 换层） | 本机 `keymap.txt` 三段（出厂预置） |
| L4 | 状态行**中区**虚拟键 | 合成器按输入框类型组装 → 过滤 → 等宽槽版式 |
| L5 | 状态行**上方**独立虚拟键行 | 同一份键序的溢出尾巴 + 用户短语 |

**L4 显示规则（四道工序，2026-09-29 整条重理）**：

1. **合成（取源定序）**：语言补差键 → 字段核心键（按输入框合法性取子集）→ 字段键位套（`[L4 default|url|email|number|…]` 段或出厂预置）。
2. **去重**：标签+输出相同的只留一把。
3. **过滤：`L1-L3 已有的按键一律不进 L4`**——`KeyLayout.producible()` 取**本机** L1/L2/L3 三层输出的并集，命中就不显示（2026-09-29 用户定案，推翻 2026-09-28"不做程序筛选"）。**这一步必须运行时**：编译期把内容写死会失去不同手机的适配能力（键位表随机型不同，某层没有某键时 L4 要自动少一把/多一把）——用户明确否掉"编译时过滤器 + 合成器生成固定内容"。
4. **版式（`L4PanelView`）**：单行不滚动、键间距恒 4dp、左右边距对称 6dp、键宽下限 36dp、槽宽 `W = min(填充值, 72dp 封顶)`；`W=填充值` → 两端对齐，`W=封顶` → 整组居中；等宽+等距 ⇒ 中线恒过中心键心（奇数把）或中缝（偶数把）。分页按"前缀最长标签宽"判装得下，L4 页不裁字，装不下的尾巴切给 L5。

**L4 必显**（2026-09-29）：设置页的 L4 显隐开关已删；数字态也不整块隐藏——数字框里显示的是该类型的**短语组合**键位套（见 [[ime-strategy-registry]]：合法单字符全在 L2 会被筛掉，所以数字态 L4 放 `+86` 这类组合，出厂只有电话类型有）。

**重建判据 = 键序内容的 hash**（2026-09-29）：`ImeController.l4Signature()` 对最终键序（逐把 `标签+输出`）取 hash，内容变才重建键帽——不再手工拼"键位套 id|语言 id"，那种签名要靠人记得列全所有来源，漏一个就"键换了却不重绘"。

**L5 显示规则（2026-09-29）**：只装 **L4 装不下的溢出尾巴**（"现在还不做用户短语功能"——曾一度加的 `[L5]` 用户短语段已整体撤回）；**左侧对齐、键宽用自然宽**（不是 L4 那套等宽槽/居中：尾行是 L4 的续页，靠左才读成同一条序列，也就不会被截字）。行高 48dp、**键帽贴行底**、与状态行间距 6dp。键盘图标无溢出时 GONE，行开时高亮；组词态整行让位。行显隐会改 `contentTopInsets`，切换后要通知窗口重算，并**补一次延迟重排**——否则「L5 开着切输入框」时窗口没跟着长高，L5 顶被裁掉一截（键只剩 54px，应为 86px）。输入条背景**纯黑**。

## 修饰键语义：敲击 = 记忆动作，长按 = 临时层

总纲（2026-09-27 定案，`ImeController.HOLD_MS = 300ms`）：**"敲击"落状态，"长按"只在按住期间生效，松开即取消**；两者共用一次按下/松开，不互相打架。

- **长按 `Alt` = 按住 L2、长按 `Sym` = 按住 L3**：按下即进层，按住期间可连续出符号；松开时恢复按下前的 `lock`/`oneShot` 快照（= 取消）。长按期间按下任何其他键 = 这次长按已被"用掉"，松开必然取消。
- **`Alt` 单击** → L2 一次性（keyDown 生效）；**双击**（TapTracker 300ms 窗口）→ 锁定 L2；**再单击**解锁。双击刚建立的锁定不会被紧随的长按取消（收尾时保留 `holdTapN >= 2` 的状态）。
- **`Sym` 单击** → L3 一次性、**双击**锁定、**三击** L1→L2→L3 循环；敲击语义一律在 keyUp 判定（快速松开才算敲击）。
- 锁定态下 `Alt`/`Sym` 单击异层键 = 直接换锁（互为层切换键）。
- **`Shift`**：单击 = 大写一次（输入一个字母后自动取消；已在 Caps 态时单击 = 关掉锁定）；双击 = Caps 大写锁定；**长按 = 按住期间大写**——大写来自物理修饰键本身，松手即取消、不留任何状态。状态行有大写芯片提示：Caps = 实心，一次性 / 按住中 = 描边。把 Shift 当修饰键用过（按住期间按了别的键）就不算敲击，不会误武装"大写一次"。
- **按住 `Sym` 再按其他键 = Ctrl 组合 —— 已废弃（2026-09-27 反转）**：Sym 长按专用于进 L3；Ctrl 组合只由 `Ctrl/Fn` 键承担（`Ctrl+q/w/e/r/t` 五键选字），vim 的 `Ctrl+[`=ESC 走这条路。
- `Shift+Space` = 中英切换（可可语义）；`Shift+Enter` = 快捷发送（可选、默认关，需无障碍）。

## 选字通道（5 个候选位）

1. **屏幕候选栏**：点击候选直接上屏；**上滑 = 选字上屏**；**左右滑 = 翻页**；两侧箭头可点；长按候选删除该词的用户频次。
2. **键盘表面飞字（TOUCHPAD 5 区）**：组词中窗口临时全屏接住键盘表面触摸，按起点横坐标平分 5 区上滑选字。
3. **物理键**：`空格` = 第 1 候选；`Ctrl(Fn)+q/w/e/r/t` = 第 1..5 候选（可配置到别的键）；`Tab`/`DPAD_DOWN` = 高亮下移，`DPAD_UP` = 确认高亮，`DPAD_LEFT/RIGHT` = 翻页。

**上滑选字的手感（两条上滑通道共用同一套动画）**：

- **拖动即反馈**：一开始上滑，被选的词就随手指缓慢起升（0→26dp），同一行的其他候选同步渐隐（到阈值时剩 0.4）；
- **越过阈值才提交**：位移达到阈值立刻触发选字，完成段动画接管（词加速飞到 64dp 并淡出，其余候选加速淡完）；松手还没过阈值就把拖出来的效果淡回原位。
- **触发距离取保守档**：阈值档位由 15/25/40 整体上调为 **25/40/60，默认 40**（设置页"飞字触发滑动距离"）。老取值一次性迁移（pref 标记 `fly_threshold_v2`），不带标记的话已被迁移过的值会被反复上调。

## 翻页通道

候选栏左右滑、`DPAD_LEFT/RIGHT`（翻屏助手双击编辑模式后滑动）、候选框两侧箭头、可选音量键。每页 5 条，页码显示在候选栏中部。

## 时间线

- time: 2026-09-23T04:32:00
  kind: decision
  summary: 三层键盘 = L1 字母 / L2 符号数字(Alt) / L3 编程(Sym)，单击一次性、双击锁定、锁定态互切
  source: 用户需求"sym锁定切换三层键盘"+ 可可 alt 单双击锁定语义
  affects: [tt2-hardware-constraints]
- time: 2026-09-23T04:33:00
  kind: decision
  summary: 上滑选字/左右滑翻页落地为"屏幕候选栏手势 + 软键盘 5 区上滑 + 方向键"三条通道，放弃 TT2 键盘飞字
  source: [[tt2-hardware-constraints]]
  affects: [tt2-hardware-constraints, reference-cocoime]


## Timeline

- time: 2026-09-23T04:27:03
  kind: decision
  summary: "Created this page: 三层键盘与选字/翻页交互模型（模仿可可）"
  source: "用户需求 + 可可拼音输入法使用帮助"
  affects: [ime-interaction-model]

- time: 2026-09-23T04:29:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [ime-interaction-model]

- time: 2026-09-27T22:06:58
  kind: decision
  summary: "组词拼音由输入法悬浮条渲染(部分 App 如 Termux 不渲染 composing 区),浮在候选条上方 6dp 缝、不占 insets"
  affects: [ime-interaction-model]

- time: 2026-09-27T22:44:19
  kind: decision
  summary: "修饰键语义重做:Alt/Sym 长按=临时层(松开取消),Shift 单击/双击/长按大写+状态芯片;废弃 Sym=Ctrl 组合(Ctrl 只走 Fn/Ctrl 键);上滑选字改拖动反馈+越阈值提交,飞字阈值档位上调为 25/40/60 默认 40"
  source: "用户需求+实现"
  affects: [ime-interaction-model]

- time: 2026-09-28T10:39:25
  kind: decision
  summary: "状态行三区化:左=状态芯片(可触摸切换:模式中↔En、层 L1→L2→L3 循环落锁定、大写小写⇄Caps)/中=L4 紧凑键帽(24-26dp、不足一屏居中、超屏可滚,设置页 show_l4_panel 开关,关闭用 INVISIBLE 占位)/右=设置·收起按钮;层芯片常显(L1 显示 A 描边),删除双击窗口期 GONE+postDelayed 补渲染机制(单击=描边即时显示,双击=实心锁定),不再闪烁;组词中状态行(含 L4)整行让位给候选行"
  source: "用户需求(状态提示图标闪烁/混乱、L4 紧凑居中、状态栏触摸切换、L4 开关移入设置)"
  affects: [ime-interaction-model]

- time: 2026-09-28T12:33:23
  kind: reversal
  summary: "状态行中区 L4 面板取消横向滚动:横滑与手势抢同一条事件流,改为 LinearLayout 单行+不足一屏居中(超屏贴左裁切,靠键位编辑器精简解决),取代此前「超屏可滚」的定法"
  source: "用户反馈:状态栏中心的按键不能设计成滑动 这会和手势冲突"
  affects: [ime-interaction-model]

- time: 2026-09-28T12:33:23
  kind: decision
  summary: "L4 预置键只保留 L1/L2/L3 敲不出的:-> => != == ; \\$ 六个,删掉 Tab/Esc/Home/End/PU/PD/Ins/方向键(L3 已有)——状态行中区空间留给真正缺的键"
  source: "用户反馈:L4不需要那么多按键 只需要L1-L3没有的按键"
  affects: [ime-interaction-model]

- time: 2026-09-28T15:01:48
  kind: reversal
  summary: "Ctrl+Q/W/E/R/T 不再参与选字(2026-09-28):原先按显示槽映射(Q→槽0=候选2、W→槽1=候选5、E→槽2=首选、R→槽3=候选4、T→槽4=候选3),与左→右直觉相反,候选不足 5 个时空槽还没反应。定案:候选只由五键 Shift/Sym/空格/Ctrl/Alt 选,Ctrl 组合一律原样转发给应用(vim Ctrl+C/Ctrl+W 靠这个透传);对应文案(设置项 summary、Playground 提示、Fn 说明、README)已改为五键口径。"
  source: "用户 2026-09-28"
  affects: [ime-interaction-model]

- time: 2026-09-28T15:01:48
  kind: decision
  summary: "状态行(48dp)版式定案(2026-09-28):左区芯片顺序 拼/En → 大写 → ALT → SYM,大写芯片紧贴 En,拼音态置 INVISIBLE 占住那 34dp(中英切换中区不挪);ALT/SYM 两颗常显、未切换=灰描边,点按单次切换/长按锁定。中区 L4:**键间距固定 4dp 不许拉大**,富余宽度加到键宽上贴边填满,**左右边距对称 6dp**。右区按钮 48dp 见方,齿轮用 scaleType=center 按 24dp 原尺寸画(墨迹≈16dp),不被 fitCenter 放大成 32dp 去和芯片抢眼。"
  source: "用户 2026-09-28"
  affects: [ime-interaction-model]

- time: 2026-09-28T15:02:03
  kind: note
  summary: "格式文本往返坑(2026-09-28 修):KeyLayoutStore.load 在没有 files/keymap.txt 时会拿 default().formatText() 再 parseText,而 formatText 原先把 L4 标签原样拼进 标签=取值,标签含 '=' 的 != 被拼成 !==!=,按首个 '=' 切成 标签! + 取值=!=——与核心键对不上,状态行凭空多出一把输出为 =!= 的坏键(实测中区 7 个键帽)。已加 escapeLabel/unescapeLabel/转义感知的 splitLabelValue,并补两条单测(往返键数不变、标签含等号可往返)。"
  source: "2026-09-28 真机排障"
  affects: [ime-interaction-model]

- time: 2026-09-28T15:25:23
  kind: reversal
  summary: "L4 中区取消「最近符号」动态键(2026-09-28):删除 ImeState.recentSymbols/noteCommitted、recent_symbols 落盘与上屏才重建"
  source: "用户 2026-09-28:不要recent符号了"
  affects: [ime-interaction-model]

- time: 2026-09-28T15:25:23
  kind: decision
  summary: "状态行三颗芯片互斥(2026-09-28):ab/ALT/SYM 同一时刻最多一颗亮(彩色),后按的那颗亮、先按的自动变灰;亮着的那颗失效才退给另一颗还生效的,全没生效=全灰。英文空闲时 ab 不常亮(灰描边),拼音态大写芯片仍 INVISIBLE 占位;模式芯片 拼/En 不参与互斥(始终实心)。真机复核四种状态:全灰 / ALT 亮 / Caps 亮 / Caps 后按 Alt→Alt 亮、ab 灰。"
  source: "用户 2026-09-28 + 真机截图复核"
  affects: [ime-interaction-model]

- time: 2026-09-28T16:00:42
  kind: reversal
  summary: "L4 不放组合按键(2026-09-28):固定核心键从六把 -> => != == ; $ 砍到只剩两把单字符 ; 与 $,组合串一律去 L3 或键位编辑器 [L4] 段自定义。上一版固定核心键=高频组合符+标点的定法作废。此条只进 brain,不写 README(用户明确:这类决策不进 README)。"
  source: "用户 2026-09-28:L4不需要组合按键 / 不要写到README中 这种决策放到brain"
  affects: [ime-interaction-model]

- time: 2026-09-28T16:00:42
  kind: decision
  summary: "状态行左右等距的实现(2026-09-28):左右两个固定区改成等宽权重盒——chip_row 与新包的 btn_row 都 0dp+weight 0.6,L4 weight 1,三者总重 2.2 → 两盒等宽 → L4 到屏幕左右两侧距离相等(真机实测 399px/399px)。代价(用户选定):按钮盒内 gravity=end,齿轮左侧留一段约 154px 空隙,L4 不再紧贴齿轮。同时拼音态大写芯片由 INVISIBLE 改 GONE —— 「拼」右边 ALT/SYM 直接紧贴(实测 拼 14-95 → ALT 106-187 → SYM 198-287);中英切换 L4 不挪改由等宽权重盒保证(中区实测 CN/EN 都在 399..1037),不再靠那颗芯片占位。"
  source: "用户 2026-09-28 + 真机实测"
  affects: [ime-interaction-model]

- time: 2026-09-28T16:05:48
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [ime-interaction-model]

- time: 2026-09-28T16:05:48
  kind: decision
  summary: "双击判定窗口 200ms → 300ms(2026-09-28):TapTracker 默认 windowMs 改 300,与长按 HOLD_MS(300ms)同量级,手速慢也能连击;出窗口的测试等待从 250ms 改 350ms。"
  source: "用户 2026-09-28:双击判定时间长一点 300ms"
  affects: [ime-interaction-model]

- time: 2026-09-28T17:49:35
  kind: decision
  summary: "Shift 独立单击改开关自合(2026-09-28):任意大写状态(大写一次 ONCE / Caps 锁定)在双击窗口外再单击一次必然回 OFF。原 n==1 分支只特判 CAPS,ONCE 成了死状态——除了打一个字母这种无关动作没有任何按键能退出(用户报障:第二次按 Shift 大写没有解除)。不变量:任何一次 Shift 按压都不会把用户卡死,最坏情况(ONCE 时手快第二击落进双击窗口变 Caps)再按一次也必回 OFF。双击=Caps 锁定、再双击解除、长按=按住期间大写、输入字母取消 ONCE 均不变。"
  source: "用户 2026-09-28:大写状态在第二次按shift时应该解除"
  affects: [ime-interaction-model]

- time: 2026-09-29T01:21:49
  kind: decision
  summary: "新需求入队(todo):Alt+Space 呼出命令面板——输入应用名搜索候选,点按/回车快速打开应用"
  source: "用户需求 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T01:30:38
  kind: decision
  summary: "L5 虚拟键盘落地:L4 一行自然宽装不下的键尾巴移入 L5,齿轮左侧键盘图标(无溢出时 GONE,不挤齿轮/收起位置)点按 L4⇄L5 原地翻页、停在 L5 时高亮;L5 页内仍超宽才压下限→等比压缩,'键一把不裁'升级为两页合计成立;组词态状态行让位时图标随行隐藏"
  source: "用户需求+设计定案 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T01:34:37
  kind: reversal
  summary: "L5 形态修正:推翻\"状态行内 L4⇄L5 原地翻页\"——L5 是**状态栏上方**显示的独立虚拟按键行(用户纠正 2026-09-29);触发仍为齿轮左侧键盘图标(点按切行显隐+高亮),内容仍为 L4 装不下的溢出尾巴"
  source: "用户纠正 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T02:09:10
  kind: decision
  summary: "L5 机制完整定案:L5 = 状态栏上方快捷按键层,内容三类——(1)固定场景快捷按键:类似网址/邮箱输入框那种场景键集但可放更多,支持左右滑动翻页 + 物理键盘(触摸板)滑动翻页;(2)用户编辑短语;(3)最近输入内容。打开范围:除少数限定输入字符的模式(如锁 0-9)外全部模式可开(不再以 L4 溢出为前提)"
  source: "用户需求 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T02:09:10
  kind: decision
  summary: "L1-L4 不可编辑落法(用户选择):设置页移除「编辑三层键位映射」入口,LayerMapActivity 下线——出厂键位即最终键位;README 相关编辑器说明同步删除"
  source: "用户决策 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T02:34:23
  kind: decision
  summary: "邮箱模式 L4/L5 分工(2026-09-29 用户需求):L4 只放短域名后缀键 .com .net .org 等(外加 $ 等核心键);完整邮箱域名(@gmail.com 等)整体显示到 L5——不再是\"宽度装不下才溢出\",而是按键类显式分工"
  source: "用户需求 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T09:32:12
  kind: decision
  summary: "L4 等宽槽版式(2026-09-29):状态行中区**同时满足「居中」与「两端对齐」**——所有可见键取同一槽宽 W = min(填充值, MAX_KEY=72dp 封顶, 且不低于本页最长标签自然宽):键多/行窄时 W=填充值 → 组宽恰=中区宽 = **两端对齐**;键少/行宽时封顶收窄 → gravity CENTER **整组居中**;等宽+恒 4dp 间距 ⇒ 整行关于自身中点镜像对称 ⇒ **中线恒过中心键心(奇数把)或中缝(偶数把)**——这是用户对居中的定义,由等宽自然获得、无需吸附计算。取整余数按镜像成对回补(精确贴齐且不破坏对称),末 1px 奇数把给中心键、偶数把落末键。分页改按「前缀最长标签宽」判装得下(L4 页不裁字),超宽兜底由槽宽公式直接压掉并单行截字(ellipsize,不换行)。**取代** 2026-09-28 的「富余宽度加到键宽上贴边填满」(键宽不等 → 中线落在键内任意位置,正是用户不要的)与 2026-09-28 12:33 的「不足一屏居中」两条定案——二者各是新公式的 W=填充值 / W=封顶 分支,间距恒 4dp、6dp 对称边距、L4/L5 同规则不变。已实现于 L4PanelView,单测 + assembleDebug 通过"
  source: "用户 2026-09-29:L4 需同时满足居中和两端对齐;居中=中线落在键心或中缝"
  affects: [ime-interaction-model]

- time: 2026-09-29T10:54:10
  kind: evidence
  summary: "L4 等宽槽版式真机验证通过(Titan 2,1436×1440,density 384/2.4x,像素级扫描复核):① 拼音空闲 3 键 = 72dp 封顶,组居中(左 35/右 36px 对称),中线 717.5 过 $ 键心 716.5;② 网址框 4 键 = 143/142/142/143px 恰好铺满(首键 415 贴左、末键 1020 贴右,两端对齐),中线 717 落中缝;③ 邮箱 5 键铺满,中线 302.5 精确过中间键 .net 键心;④ L5 尾行 4 键等宽 230px(= 最长标签自然宽,不裁字优先于 72dp 封顶)整组居中、中线过中缝;全程间距恒 9px = 4dp。验证中修复居中定位坑:LinearLayout 的 mTotalLength 在 super.onMeasure 里按子 View 的 wrap 宽算死后,子 View 被改成 EXACTLY 槽宽也不重算,gravity CENTER 拿过时总长算偏移(实测整组右移 164px、末键被面板右缘裁掉)——居中机制改为 gravity START + layoutPage 回填首键外边距 (avail-组宽)/2(不依赖 mTotalLength),上条 decision 中的 gravity CENTER 字样以本条为准。单测 + assembleDebug 通过,临时日志已清理,改动未提交"
  source: "真机验证 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T13:20:20
  kind: reversal
  summary: "L4/L5 显示规则整条重理(2026-09-29 用户定案,推翻 2026-09-28'不做程序筛选'):**L1-L3 已有的按键一律不进 L4**,由 KeyLayout.producible() 运行时按本机真实键位表取三层输出并集来筛(恢复 2026-09-28 删掉的 producible,但位置移到注册表 ImeRegistry.l4Keys 合成之后)。**筛必须在运行时**:编译期写死会失去不同手机的适配能力(键位表随机型不同,某层没有某键时 L4 要自动少一把)。用户明确否掉'编译时过滤器+固定内容'的方案。L4 四道工序:①合成(语言补差键→字段核心键→字段键位套)②去重 ③producible 筛 ④等宽槽版式。"
  source: "用户 2026-09-29:L4不要显示L1-L3已有的按键 / 编译时过滤器不考虑了 这样失去了不同手机的适配能力"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T13:20:31
  kind: decision
  summary: "L5 定为两类内容(2026-09-29 用户定案,在 2026-09-29 01:34'L5=状态栏上方独立行、装 L4 溢出尾巴'之上扩类):**① L4 装不下的溢出尾巴 ② 用户短语**(键位文件 [L5] 段,新解析,接在尾巴之后,恒显示、不分输入框类型)。两类都空时键盘图标 GONE;有任一就出现。'最近输入内容'与 L5 翻页手势暂不做(用户选'两类')。同批定的还有:L5 键帽**贴行底**、与状态行间距 6dp(行高仍 48dp);L4 **必显**(设置页开关已删、数字态不隐藏)。"
  source: "用户 2026-09-29 问答:L5=两类(溢出尾巴+用户短语);L5 键贴底间距 6dp;L4 必显"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T13:21:55
  kind: decision
  summary: "2026-09-29 补 L1-L5 全景与 L4/L5 显示规则四道工序 + L5 两类内容"
  source: "用户 2026-09-29 定案"
  affects: [ime-interaction-model]

- time: 2026-09-29T13:32:37
  kind: evidence
  summary: "L4/L5 新规则真机通过(Titan 2,2.4x):电话框 L4=+86 +1 +81 +44(芯片 0-9+*#)、日期框 L4=-/(芯片 0-9-/:.)、纯数字框 L4 空但面板常显(芯片 0-9)、网址框 L4=； $ http:// https://(.com 溢出)、邮箱框 L4=$ .com .net .org .cn(L5=.edu @gmail.com @hotmail… @outlook… + 短语 +86 me);点 L5 短语 +86 原样上屏(不过数字白名单);**核心键 \\\\\\ 被新规则筛掉**(出厂 L2 有 l=\\\\\\、L3 有 z=\\\\\\\\),普通文本框 L4 只剩 ； $,与'L1-L3 已有的不进 L4'一致。另修一个真机 bug:L4 一把键都没有时 tailFrom 留着旧切点,L5 短语被误裁(日期框 cut=1 → 纯数字框只剩第二把短语),已在 L4PanelView.onMeasure 空分支把切点回 0。"
  source: "真机验证 2026-09-29"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T15:03:57
  kind: decision
  summary: "2026-09-29 撤回 L5 用户短语;L5 左侧对齐+自然宽;重建判据改键序 hash;输入条纯黑;L5 高度裁切修"
  source: "用户 2026-09-29 多条定案+真机验证"
  affects: [ime-interaction-model]

- time: 2026-09-29T15:04:13
  kind: reversal
  summary: "L5 用户短语功能撤回(2026-09-29):此前按'两类内容'加的键位文件 [L5] 段 + KeyLayout.l5 + ImeController.l5Phrases() + 首启落盘 keymap.txt 全部撤掉,L5 回到只装 L4 装不下的溢出尾巴。用户原话'现在还不做用户短语功能 这些不是用户短语'。连带撤掉'键位表首启落盘'(那是为了让短语可编辑才加的)。密码框冒出 +86/me 的报障因此自动消失。"
  source: "用户 2026-09-29"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T15:04:14
  kind: decision
  summary: "L5 版式定案(2026-09-29):**左侧对齐 + 键宽用自然宽**,与 L4 的等宽槽/居中/两端对齐不同——尾行是 L4 的续页,靠左才读成同一条序列,顺带不再被截字(邮箱尾行 @hotmail.com / @outlook.com 完整显示)。L4 保持'键多铺满两端对齐、键少封顶 72dp 居中'(用户确认保留)。"
  source: "用户 2026-09-29:L5是左侧对齐 / L4 保留铺满"
  affects: [ime-interaction-model]

- time: 2026-09-29T15:06:30
  kind: decision
  summary: "L4/L5 重建判据改**键序内容的 hash**(2026-09-29 用户定案'按键使用hash作为id判断是否需要重新绘制'):ImeController.l4Signature() 对最终键序逐把(标签+输出)取 hash,内容变才重建键帽,不再手工拼'键位套id|语言id'——那种签名靠人记得列全来源,漏一个就'键换了却不重绘'。回归断言:同键序重复取签名必须相同(否则每次按键都重建)。"
  source: "用户 2026-09-29"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T15:06:30
  kind: evidence
  summary: "纯黑背景 + L5 高度裁切修复真机通过(Titan 2,2.4x,像素扫描):输入条背景 ImeTheme.DEFAULT.barBg 与 status_bg/candidate_bg 全改 #000000(其余 6 套主题色板不动);L5 高度裁切(「L5 开着切输入框」时键只画出 54px、应为 86px)由 syncL5 补一次 mainHandler.post 延迟 notifyInsetsChanged + onStartInput 里 L5 可见时也通知,复测同场景键带恢复 1239-1324=86px;数字态 "
  affects: [ime-interaction-model]

- time: 2026-09-29T15:35:42
  kind: decision
  summary: "L2 出厂键位表改为**严格按 Titan 2 物理键盘丝印**(2026-09-29 用户定案L2的映射必须严格按照titan2物理键盘的丝印),基准是仓库根目录 .TitanKey.kcm(厂商键位表,只含 A-Z + SPACE/ENTER)每个 key 的 alt: 值。逐键核对后**唯一错项是 l**:丝印是撇号,代码写成了反斜杠 —— 已改(Alt+l 现在出撇号)。其余 25 键与 kcm 一致(Q=0 W=1 E=2 R=3 S=4 D=5 F=6 X=7 C=8 V=9;A=@ T=( Y=) U=- I=_ O=/ P=: G=* H=# J=+ K=双引号 Z=! B=. N=, M=?)。单测从抽查 20 键改成**26 键全量断言** + 断言丝印上没有反斜杠。附带结论:丝印上没有反斜杠,所以它只剩本项目自定的 L3 编程层(Sym+z)一处,L4 自然被筛掉。"
  source: "用户 2026-09-29 纠正 + .TitanKey.kcm 逐键核对"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T15:35:42
  kind: decision
  summary: "L5 行高 48dp→42dp(2026-09-29 用户报障L5键盘的上边距太宽了):键仍是 36dp 与状态行 L4 同款、仍贴行底;行高缩到 42dp 后上边距 12dp→6dp,与下边(L5↔状态行 6dp)、L4 行自身上下留白三者一致。真机像素实测:行顶 y=1210→1224,键带 1239-1324(86px=36dp)不变。"
  source: "用户 2026-09-29 + 真机像素实测"
  affects: [ime-interaction-model]

- time: 2026-09-29T17:02:09
  kind: decision
  summary: "状态栏左侧芯片改纯状态显示(只显示已触发的状态)+ 去掉触摸切换(改按键切换)(2026-09-29 用户定案)
- 左区芯片:层芯片/大写状态**没触发就 GONE**(此前 ALT/SYM 两颗常显灰芯片、大写常显 ab);语言芯片 `拼/En` 恒显
- **芯片不再响应触摸**:四个芯片的 click/longClick 全删,ImeController.tapLayerChip / longPressLayerChip / tapShiftChip 三个方法与 `LitChip` 三颗互斥状态机(及 litLayerPrev/litCapsPrev)整体删除
- **切换一律走物理按键**:Alt 单击=一次性、双击=锁定;Sym 同;Shift 单击=大写一次、双击=Caps 锁定;Shift+Space=中英切换
- 挖出并修一个真坑:触摸路径原本挡着「数字类的 L2 字段锁解不掉」(解锁后 L1 全被吞、没法打字),删掉触摸后物理 Alt 会解开 → 守卫挪进 `layerKeyTap`,对应测试改写成物理键
- L4/L5 显示规则、数字态键位套、L2 丝印对齐等 2026-09-29 早先几条定案见本轮前几条 timeline 与两页 compiled_truth"
  source: "用户 2026-09-29"
  affects: [ime-interaction-model, ime-strategy-registry]

- time: 2026-09-29T17:02:09
  kind: decision
  summary: "状态栏左右区统一风格 + L4 中区加宽 + 锁定统一下划线 + 大写改 Shift 箭头(2026-09-29 用户定案)
- **左右统一风格**:右区三键(键盘/齿轮/收起)全改 **48dp 见方**、`scaleType=center` 按原尺寸画;收起三角不再用 wrap_content 窄盒 + paddingEnd 6dp(它比另两个键窄,右区视觉重量与左区不一致)
- **L4 中区加宽**:chip_row 与 btn_row 权重 0.6/0.6 → **0.4/0.4**(中区别拿 45% → 约 60%),一行能多放一把键、少溢出到 L5。**两侧权重必须同步改**:权重相等是「L4 到屏幕左右两侧距离相等」这个硬要求的前提(用户复核过),改一侧不改另一侧就会破
- **锁定统一下划线表示**:层芯片与大写状态锁定=底部一根 2dp 实线(新增 drawable `chip_lock_underline`),一次性/按住=无下划线。此前是「锁定=实心色块、一次性=描边色块」的按钮样式
- **大写状态标识改 Shift 上箭头图标**(新增 `ic_shift` / `ic_shift_lock` 线稿,24dp 视口 1.5px 圆头描边,与 ic_settings/ic_keyboard 同一套语言),替代 `ab`/`Ab`/`AB` 文字芯片;48dp 见方无背景,与右区三键同款;Caps 锁定=箭头叠实心下划线(Android 键盘同一套语言)
- 语言芯片(拼/En)恒显但不再是锁定/非锁定两态:主语言=实心主色、非主语言=普通前景色无背景"
  source: "用户 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T17:02:10
  kind: reversal
  summary: "L4 固定核心键删掉 `\\`(2026-09-29 用户定案,推翻 2026-09-28点名固定显示"
  affects: [ime-interaction-model]

- time: 2026-09-29T17:02:10
  kind: decision
  summary: "实验场新增「键位总览」按钮(2026-09-29 用户定案在应用中把"
  source: "用户 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T22:51:51
  kind: decision
  summary: "候选行**横滑翻页改成跟手拖动**(用户定案「翻页阈值也需要调整 …… 横向滑动没有到阈值的之后是跟手的动画,跟上滑动画一致,松手或者反向滑动可以反向动画」)。旧的是写死 24dp 的**抬手**判据:既不跟手,又在 598dp 宽的行上只占 4%、随手一碰就翻。新模型与上滑飞字同构:死区 = 行宽 6% → 跟手(位移 1:1,pageU)→ 松手过**行宽 1/3** 才提交,否则反向动画收回原页;正在播的动画可被下一次横滑**接管**(同向接着走、反向把旧页/新页对调且进度取镜像,画面不跳)。换算抽成纯函数 pageDragProgress/pageCommitPoint,单测 CandidateBarPageDragTest 钉住。相邻页槽由新增的 ImeState.pageSlotsAt(p) 提供(越界=那一侧没页)"
  source: "用户定案 2026-09-29"
  affects: [ime-interaction-model]

- time: 2026-09-29T22:51:51
  kind: decision
  summary: "输入法隐藏时**大写/Alt(L2)/Sym(L3) 全部回到初始态**(用户定案)。新增 ImeController.resetLayers():lock/oneShot/hold 清空、shift 回 OFF、shiftHeld 清掉,连同长按与连击那套内部状态(holdMod/snapLock/snapOneShot/holdTapN/shift*/symDown/suppress*)一起清——那些记的是本次长按/连击的前置状态,跨过一次隐藏就该丢弃,否则下次长按 Alt 会去恢复一份过期快照。挂在 **onWindowHidden**(不是只挂收起三角),App 主动收键盘这条路径同样覆盖。语言与输入框类型不动(换框/换语言是 resetForNewInput / onStartInput 那套)"
  source: "用户定案 2026-09-29"
  affects: [ime-interaction-model]
