---
id: ime-interaction-model
title: "三层键盘与选字/翻页交互模型（模仿可可）"
category: decision
status: active
tags: [ime, ux, keymap]
created: "2026-09-23T04:27:03"
updated: "2026-09-28T17:49:35"
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
3. **物理键**：`空格` = 第 1 候选；`Ctrl(Fn)+q/w/e/r/t` = 第 1..5 候选（可配置到别的键）；`Tab`/`DPAD_DOWN` = 高亮下移，`DPAD_UP` = 确认高亮；`DPAD_LEFT/RIGHT` = 翻页。

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
