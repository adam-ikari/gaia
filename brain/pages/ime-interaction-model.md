---
id: ime-interaction-model
title: "三层键盘与选字/翻页交互模型（模仿可可）"
category: decision
status: active
tags: [ime, ux, keymap]
created: "2026-09-23T04:27:03"
updated: "2026-10-10T17:42:25"
updated: "2026-09-27T22:55:16"
tags: [ime ux keymap]
---


<!-- compiled_truth -->
模仿可可的交互模型，并按 [[tt2-hardware-constraints]] 重新排布可行通道。总原则：**每个动作至少两条通道（物理键 + 屏幕手势）**。

## 术语：状态行不叫"芯片"

状态行左区那几颗小方块最早按"芯片"（工业设计语言）命名，2026-09-30 用户定：**这一层关心的是"输入法此刻处于什么状态"，与画成什么形状无关**，所以代码与文档一律用抽象的 **status / 状态**：`ui/StatusRow.present`（纯函数，判定规则全在这里）、`status_mode` / `status_shift` / `status_alt` / `status_sym` / `status_fullwidth`（view id）、`ImeTheme.statusL2/L3`、`drawable/status_lock_underline`。规则读起来要是产品语言（"层与大小写互斥""锁定态""借用"），不是排版术语。

**状态行是状态的纯函数**（公理 A）：只吃状态，不吃时间。判定在纯函数 `StatusRow.present(state, spec)`，`ImeService.renderStatus` 只负责把单子贴到 TextView，于是每条规则都有 JVM 回归测试（`StatusRowTest`）。**大写与层互斥**是唯一带产品判断的规则：符号层上大小写不参与输出（`commitText` 的 `upper` 只在字母上起作用），层生效时藏起大小写、回 L1 自己回来，**大写状态本身不清**。曾有一条"敲击窗口内不显示暂定态"的规则，因为敲击判定统一到 keyUp 后只剩副作用（真机报障：alt 状态长按 alt，那颗状态消失 0.5s）已删除。

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

## 修饰键状态机（2026-09-30 整条重整，状态迁移图）

### 状态的形状：一个持续态 + 最多一层临时覆盖

不是三个可空字段（`lock`/`oneShot`/`hold` 那种），而是一个和类型 `LayerState`——非法组合写不出来，并存由类型自己说明：

| 状态 | 性质 | 含义 |
|---|---|---|
| `L1` | 持续态 | 不在层上（= 初始状态） |
| `Locked(L)` | 持续态 | **双击产生的锁**，常驻；再敲同层键解除。**唯一的锁来源** |
| `OneShot(L, snap)` | 临时 | **借用** L 一个键，用掉 / 再敲同层键就恢复 `snap` |
| `Hold(L, snap)` | 临时 | 按住期间生效，松手恢复 `snap`（长按即取消） |

`snap` = 恢复目标，**只可能是 `L1` 或 `Locked`**，由 `LayerState.borrow()` 一处保证（剥掉所有临时层 = `base`）。读法：`lockedLayer` 走 `base`，所以**按住期间、借用的底层锁都仍然读得到**。

### 迁移图

```
                        长按 Alt/Sym(任何状态都进)
              ┌───────────────────────────────────────┐
              │                                       │  松手 = 长按 / 被别的键用掉
              v                                       │  ⇒ 恢复 snap(什么都不留)
        Hold(L, snap) ────────────────────────────────┘
              │
              │ 松手 = 快速独立敲击(按住 <300ms 且期间没按别的键) ⇒ 落定,判据见下表
              v
   ┌──────────────────────────────────────────────────────────────────────────┐
   │ 数字类且已锁 L2  → Locked(L2)        守卫:数字类的锁解不掉(否则 L1 全被吞)  │
   │ 命中解锁吸收窗    → 不变              250ms 内吞掉紧随的那一下              │
   │ n>=2(双击)      → Locked(L) + 清计数  锁在别的层时 = 把锁搬过去            │
   │ Locked(同层)     → L1 + 吸收窗         敲一下 = 解锁                       │
   │ 临时层 & 敲的正是当前那层或底下锁着的那层 → snap + 吸收窗(收掉临时层)      │
   │ 其余单击         → OneShot(L, snap=底层持续态)   ← 借用                   │
   └──────────────────────────────────────────────────────────────────────────┘

   OneShot(L, snap) ──下一个键消费──→ snap
                   ──再敲同层键────→ snap + 吸收窗

   借用实例（用户 2026-09-30 定案「临时借用返回上一个锁定状态,没有锁定状态返回初始状态」）：
     Locked(L2) ──单击 Sym──→ OneShot(L3, Locked(L2)) ──敲一个键──→ Locked(L2)   ← 回锁
     L1        ──单击 Sym──→ OneShot(L3, L1)         ──敲一个键──→ L1            ← 回初始
     OneShot(L3, Locked(L2)) ──单击 Sym──→ Locked(L2)   (取消借用)
     OneShot(L3, Locked(L2)) ──单击 Alt──→ Locked(L2)   (敲底下锁着的那层 = 收掉借用)
     旧借用被丢掉:OneShot(L2, L1) ──单击 Sym──→ OneShot(L3, L1) (一次性层只有"下一个键"的寿命)
```

**判定的主轴是敲击次数 `n`**（`n` = 敲击窗口内的连续次数，窗口 **400ms**，2026-09-30 用户定"再长一点"；长按阈值另算，**300ms**）：

- `n>=2`（双击）**必须排第一**——第二击的可见层正是这个键，排在"取消"之后会被当成取消借用；
- 锁定之后**清掉这一键的连击计数**：下一击重新算单击，否则窗口内第三击又是 `n>=2`，"锁着再敲一下 = 解锁"就只在窗口外成立；
- **只有单击和双击两种有效事件**（2026-09-30 用户定"三连击无效"）：窗口内第三次及以后不是新事件。三击 = 单击 + 双击 + 锁定态下的一次单击 = 解锁。原先那条 `n>=3 → L1↔L2/L3 换层` **够不到**（第二击就进 Locked 分支）⇒ 从来没生效过，已删。

**解锁吸收窗 250ms**（层与大写同一条规则）：解除/取消后紧随的同键敲击被吞掉，双击解除后手抖再按一下不会又锁上。

**三个修饰键同一套骨架**：`keyDown` 记快照进 `Hold(L, snap)` / `shiftBeforeHold` → `keyUp` 判是不是快速独立敲击 → **是**才落定，**否**就恢复快照。所以长按永远不会留下一次敲击，也不需要"记住这次敲了几个来补救"的字段。**按键入口不 Flow 化**（`onKeyDown` 必须同步返回消费判定），**输出侧不整体流化**（`InputConnection` 写入必须同步）。

**大写是独立状态机**（`ShiftMode` 单一取值，取代原先"落定状态 + 是不是按着"两个字段）：`ab` 未大写 / `Ab` 大写一次（含物理 Shift 正按住）/ `AB` Caps 锁定，**字形表达、不带下划线**（层状态仍用下划线，两套语言不混用）。单击 = OFF⇄ONCE（Shift 永远是自己的退出键，不存在按不掉的死态）；双击 = CAPS 锁定 / 再双击解除；关掉大写也进 250ms 吸收窗。拼音态整个忽略（拼音没有大小写）。把 Shift 当修饰键用过（按住期间按了别的键）就不算敲击。

**其它迁移**：字母键消费 `OneShot` 与 `ONCE`；聚焦数字类 → `Locked(L2)`，离开数字类且锁着 L2 → `L1`；切语言 → 语言策略 `onEnter` 清大写（**层不清**，换一个输入框不该丢用户选的层）；窗口隐藏 → 全回初始；换输入框 → 只清组词。

- **按住 `Sym` 再按其他键 = Ctrl 组合 —— 已废弃（2026-09-27 反转）**：Sym 长按专用于进 L3；Ctrl 组合只由 `Ctrl/Fn` 键承担（`Ctrl+q/w/e/r/t` 五键选字），vim 的 `Ctrl+[`=ESC 走这条路。
- `Shift+Space` = 中英切换（可可语义）；`Shift+Enter` = 快捷发送（可选、默认关，需无障碍）。

## L1–L5 全景：物理三层 + 屏幕两行

| 层 | 载体 | 内容来源 |
|---|---|---|
| L1 / L2 / L3 | 物理键（Alt / Sym 换层） | 本机 `keymap.txt` 三段（出厂预置） |
| L4 | 状态行**中区**虚拟键 | 合成器按输入框类型组装 → 过滤 → 等宽槽版式 |
| L5 | 状态行**上方**独立虚拟键行 | 同一份键序的溢出尾巴 + 用户短语 |

**L4 显示规则（四道工序，2026-09-29 整条重理）**：

1. **合成（取源定序）**：语言补差键 → 字段核心键（按输入框合法性取子集）→ 字段键位套（`[L4 default|url|email|number|…]` 段或出厂预置）。键位有**优先级**（`L4Key(label, out, priority)`：固定键 > 模式短语 > 冷门符号 > 用户短语），撞车时优先级高的赢。
2. **去重**：标签+输出相同的只留一把。
3. **过滤：`L1-L3 已有的按键一律不进 L4`**——`KeyLayout.producible()` 取**本机** L1/L2/L3 三层输出的并集，命中就不显示（2026-09-29 用户定案，推翻 2026-09-28"不做程序筛选"）。**这一步必须运行时**：编译期把内容写死会失去不同手机的适配能力——用户明确否掉"编译时过滤器 + 合成器生成固定内容"。
4. **版式（`L4PanelView`）**：单行不滚动、键间距恒 4dp、左右边距对称 6dp、键宽下限 36dp、槽宽 `W = min(填充值, 72dp 封顶)`；`W=填充值` → 两端对齐，`W=封顶` → 整组居中；等宽+等距 ⇒ 中线恒过中心键心（奇数把）或中缝（偶数把）。分页按"前缀最长标签宽"判装得下，L4 页不裁字，装不下的尾巴切给 L5。

**L4 必显**（2026-09-29）：设置页的 L4 显隐开关已删；数字态也不整块隐藏——数字框里显示的是该类型的**短语组合**键位套（见 [[ime-strategy-registry]]：合法单字符全在 L2 会被筛掉，所以数字态 L4 放 `+86` 这类组合，出厂只有电话类型有）。**邮箱模式允许中文**（2026-09-30 用户定案，曾加过 ASCII 过滤又被推翻）。

**重建判据 = 键序内容的 hash**（2026-09-29）：`ImeController.l4Signature()` 对最终键序（逐把 `标签+输出`）取 hash，内容变才重建键帽——不再手工拼"键位套 id|语言 id"，那种签名要靠人记得列全所有来源，漏一个就"键换了却不重绘"。

**L5 显示规则（2026-09-29）**：只装 **L4 装不下的溢出尾巴**（"现在还不做用户短语功能"——曾一度加的 `[L5]` 用户短语段已整体撤回）；**左侧对齐、键宽用自然宽**（不是 L4 那套等宽槽/居中：尾行是 L4 的续页，靠左才读成同一条序列，也就不会被截字）。行高 48dp、**键帽贴行底**、与状态行间距 6dp。键盘图标无溢出时 GONE，行开时高亮；组词态整行让位。行显隐会改 `contentTopInsets`，切换后要通知窗口重算，并**补一次延迟重排**——否则「L5 开着切输入框」时窗口没跟着长高，L5 顶被裁掉一截。输入条背景**纯黑**。

## 选字通道（5 个候选位）

1. **屏幕候选栏**：点击候选直接上屏；**上滑 = 选字上屏**；**左右滑 = 翻页**；两侧箭头可点；长按候选删除该词的用户频次。
2. **键盘表面飞字（TOUCHPAD 5 区）**：组词中窗口临时全屏接住键盘表面触摸，按起点横坐标平分 5 区上滑选字。
3. **物理键**：`空格` = 第 1 候选；`Ctrl(Fn)+q/w/e/r/t` = 第 1..5 候选（可配置到别的键）；`Tab`/`DPAD_DOWN` = 高亮下移，`DPAD_UP` = 确认高亮，`DPAD_LEFT/RIGHT` = 翻页。

**上滑选字的手感（两条上滑通道共用同一套动画）**：

- **拖动即反馈**：一开始上滑，被选的词就随手指缓慢起升（0→26dp），同一行的其他候选同步渐隐（到阈值时剩 0.4）；
- **越过阈值才提交**：位移达到阈值立刻触发选字，完成段动画接管（词加速飞到 64dp 并淡出，其余候选加速淡完）；松手还没过阈值就把拖出来的效果淡回原位。
- **触发距离取保守档**：阈值档位由 15/25/40 整体上调为 **25/40/60，默认 40**（设置页"飞字触发滑动距离"）。老取值一次性迁移（pref 标记 `fly_threshold_v2`）。

## 翻页通道

候选栏左右滑、`DPAD_LEFT/RIGHT`（翻屏助手双击编辑模式后滑动）、候选框两侧箭头、可选音量键。每页 5 条，页码显示在候选栏中部。

## 组词缓冲与悬浮条

中文拼音由输入法自己的悬浮条渲染（部分 App 不渲染 `composing` 区，如 Termux），浮在候选条上方 6dp 缝、**不占 insets**。


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

- time: 2026-09-30T08:46:01
  kind: decision
  summary: "状态行左区芯片互斥规则(2026-09-29 用户定案):英文态下大写芯片与层芯片互斥、层优先(层生效时藏起「A」,回 L1 自恢复,大写状态不清);大写锁定样式与 alt/sym 锁定同款(同走 ImeService.renderChip,盒子统一 34dp/12sp)"
  source: "用户报「英文状态大小写状态和 alt/sym 模式是互斥的」「英文大小写锁定图标的样式和 alt/sym 锁定不同」"
  affects: [ime-interaction-model]

- time: 2026-09-30T11:03:38
  kind: reversal
  summary: "邮箱不再按'纯 ASCII'过滤 L4 键:曾加 FieldProfile.l4AsciiOnly 把全角补差键(… —— 、 「」)从邮箱框全滤掉(RFC 5321/5322 依据),用户当天定案「允许邮箱输入中文」后撤销——邮箱框里中文真会出现(显示名、备注),为可投递地址的严格性撤掉中文标点是误伤。该字段已从 FieldProfile 删除,不留死配置"
  source: "用户 2026-09-30「允许邮箱输入中文」(推翻同日「邮箱模式 中文是合法的吗」的处置)"
  affects: [ime-interaction-model]

- time: 2026-09-30T11:03:39
  kind: decision
  summary: "修饰键手势流化(Flow 改造 Stage1/2/4,已落地):新增纯 Kotlin 的 ModifierGestures 收编全部'时间'(敲击计数/敲击窗口/按住时长/解锁吸收窗),原先散在 ImeController 七八个字段 + ImeState.tapAt;敲击窗口用 taps.debounce(300) 表达 ⇒ 「双击按一个事件」成为一条声明式规则(settled 流只发一次),ImeService 收它补渲染,删掉手写 Handler.postDelayed + chipSettle 字段;删 TapTracker + TapTrackerTest;新增 ModifierGesturesTest(runTest 虚拟时间,双击只发一次已变成断言)。按键入口不 Flow 化(onKeyDown 必须同步返回消费判定);输出侧 Flow(ImeCommand)列为 Stage3 未做"
  source: "用户 2026-09-30「接下来Flow改造重构事件系统」"
  affects: [ime-interaction-model]

- time: 2026-09-30T11:47:47
  kind: decision
  summary: "Stage 3 落地(输出侧):新增 ui.RenderPump —— 一次按键事件里的多次 render() 合成一次视觉渲染(Channel CONFLATED + yield + 渲染前 drain)。合并只覆盖**视觉**部分,ImeService.updateUi 拆成 requestRender(组词区同步 + 合并渲染)与 updateUi(完整同步一趟,生命周期路径用)。**组词区写 InputConnection 必须同步**:延后会让 onUpdateSelection 看到上一份组词区而把组词丢掉,不能一起合并"
  source: "用户 2026-09-30「继续」(Stage 3)"
  affects: [ime-interaction-model]

- time: 2026-09-30T12:33:12
  kind: decision
  summary: "状态行呈现判定抽成纯函数 ui.StatusChips.present(state, tapWindowOpen, spec)(公理 A:状态行是 (状态,时间) 的纯函数);ImeService.renderChips 退化成'取输入+贴 View',mutual exclusion/暂定不画/统一样式/全半可见性等规则全部有 JVM 回归测试 StatusChipsTest(15 项)——这些规则此前一行测试都没有。同时把 shift:ShiftState + shiftHeld:Boolean 两个字段收成单一 ShiftMode{OFF,ONCE,CAPS,HELD},非法组合('ONCE 且按住中')不再可能"
  source: "用户 2026-09-30「开始」(第一性原理分析后推荐的单项改动)"
  affects: [ime-interaction-model]

- time: 2026-09-30T12:56:25
  kind: decision
  summary: "ImeState 的层状态收成单一和类型 LayerState{L1,OneShot,Locked,Hold}(取代 lock/oneShot/hold 三个可空字段):顶层不再可能同时存在'按住中'与'锁着/一次性'。关键认知:那两个状态并存**不是疏忽**,而是'长按取消 / 敲击保留'的实现方式(Alt 敲击在 keyDown 就落定)——所以并存的两份被收进 LayerState.Hold(layer, snap, pending),敲击逻辑经 tapBase/withTapBase 写 pending。另修 4 个既有 lint error(onEvaluateInputViewShown 改调 super;三处 android:tint 加 tools:ignore——本布局非 AppCompat 上下文,app:tint 会静默丢染色,是误报)"
  source: "用户 2026-09-30「修复」"
  affects: [ime-interaction-model]

- time: 2026-09-30T13:08:27
  kind: reversal
  summary: "onEvaluateInputViewShown 必须硬写 true,不能调 super(2026-09-30 实机事故):为消 lint 的 MissingSuperCall 改成 super.onEvaluateInputViewShown() 后,平台默认实现因 Titan 2 配了硬件键盘而返回 false,输入视图永不显示——输入法整个弹不出来(日志 ImeTracker onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE、mIsInputViewShown=false)。改为 @Suppress(MissingSuperCall) 显式抑制。教训:不为消 lint 而改行为,尤其硬件相关分支;断言'行为等价'之前要先在真机验证"
  source: "用户报障「输入法弹不出来了」+ logcat/dumpsys 定位"
  affects: [ime-interaction-model]

- time: 2026-09-30T13:32:55
  kind: reversal
  summary: "大写芯片回到字形表状态(2026-09-30 用户定案「把 A 改为 ab Ab AB 的模式,不显示下划线锁定了」):ab=未大写 / Ab=大写一次(含物理 Shift 按住中) / AB=Caps 锁定,英文态恒显(ab 也在),**一律不带下划线**——锁定与否由字形表达,与 alt/sym 的锁定下划线分成两套语言。层生效时仍藏起(互斥);敲击窗口内不画暂定态(含 ab:窗口里再敲一下就是 Ab,先画 ab 会闪)。推翻同日'一把 A + 锁定加下划线、与 alt/sym 同款'的定案;这实际是回到更早的 ab/Ab/AB 老方案"
  source: "用户 2026-09-30"
  affects: [ime-interaction-model]

- time: 2026-09-30T13:57:43
  kind: decision
  summary: "修饰键状态迁移整条梳理(2026-09-30):三个键统一成同一套骨架——keyDown 记快照进 LayerState.Hold.snap / shiftBeforeHold,keyUp 判是否快速独立敲击,是才落定新状态、否则恢复快照。**Alt 的敲击判定从 keyDown 移到 keyUp**(原先与 Sym/Shift 不一致),于是删掉两处只为补这个不对称而存在的机制:LayerState.Hold.pending 与 controller 的 holdTapN(以及长按分支里的重置连击)。迁移总表写进 ImeController 类注释(逐事件 × 逐键 + 敲击落定的分支表)。两处遗留不对称已在注释里标明待定:①层芯片解锁有 250ms 吸收窗、大写没有(Caps 双击解除后第三击会重新锁上);②锁定态下的连击先被'同层解锁'吃掉,走不到 n>=3 换层"
  source: "用户 2026-09-30「几种状态切换的状态迁移需要重新梳理」"
  affects: [ime-interaction-model]

- time: 2026-09-30T14:18:33
  kind: reversal
  summary: "删除'三击 = L1↔L2/L3 换层'(2026-09-30 用户定案「三连击无效,短时间只有单击和双击是有效事件」)。查明那条分支**够不到**:从 L1 连击三下,第二击就进 Locked 分支,第三击被'同层解锁'吃掉;换起点也一样 ⇒ 这个功能从未真正生效,却让人以为三击能换层(用户:「有些反直觉」)。现在只有两种事件:单击=一次性/解锁,双击=锁定;第三击只是'锁定态下的一次单击'。同时给大写补上 250ms 解锁吸收窗(与层芯片同一条规则),Caps 双击解除后手抖再按一下不会又锁上"
  source: "用户 2026-09-30「1 加上 2 有些反直觉」+「三连击无效 短时间只有单击和双击是有效事件」"
  affects: [ime-interaction-model]

- time: 2026-09-30T14:49:28
  kind: reversal
  summary: "删除状态行'敲击窗口内不画暂定态'整条规则(用户选'去掉这条规则')。报障:「在 alt 状态下长按 alt 会有 alt 消失 0.5s 左右」——根因:长按 keyDown 重开敲击窗口,按住期间 Hold 态不锁,被当成暂定态藏起来,300ms 后才补画。查清后判定这条规则本身就是错的工具:敲击判定统一到 keyUp 之后,按住期间画出来的东西和敲击落定结果长得一模一样(同一层、锁定样式取自 snap),藏它只会造成闪烁。连带删掉 ModifierGestures.settled(debounce 流)+ ImeService.startGestureCollector + tapWindowOpen/openWindow/windowRemainMs + 7 个相关测试。StatusChips.present 签名改为 (state, spec),状态行现在是状态的纯函数(不再吃时间)。代价:双击锁定时能看到下划线长出来那一下(那是锁定真正成立的时刻,是一次状态变化不是闪一下)。新增回归测试'长按已有层状态 芯片前后一模一样'"
  source: "真机报障 + 用户 2026-09-30 选'去掉这条规则(推荐)'"
  affects: [ime-interaction-model]

- time: 2026-09-30T15:55:20
  kind: decision
  summary: "双击判定窗口 300 → 400ms(用户 2026-09-30「双击时间窗口再长一点」,理由:300ms 时想双击却没锁上手感偏紧)。长按阈值 holdMs 仍是 300ms 不变(那是按键时长,与两击之间的间隔是两件事)。顺带把 ImeControllerTest 里 17 处写死的 sleep(350/450) 换成两个跟常量走的辅助函数 holdLongerThanThreshold()/exitTapWindow()——写死毫秒数的地方在窗口一改就会静默变成'还在窗口内',测试照过但断的是语义"
  source: "用户 2026-09-30「双击时间窗口再长一点」"
  affects: [ime-interaction-model]

- time: 2026-09-30T16:12:13
  kind: reversal
  summary: "锁定态下敲**异层**键:单击 Sym 不再把锁搬过去,改为得到一次性 Sym(原来的锁被顶掉)。根因:layerKeyTap 里 'cur is LayerState.Locked' 分支排在敲击次数判定**之前**,不是同层就一律 Locked(新层)——等于单击就能造出锁定,与'锁定 = 双击'矛盾(真机报障:alt 锁定时单击 sym,sym 芯片也是带下划线的锁定态)。改法:把判定的主轴换成敲击次数 n —— Locked(同层)→解锁 / n>=2→Locked / OneShot(同层)→取消 / 其余单击→OneShot。推翻 2026-09-28 起'锁定态下 Alt Sym 一次互切层'的行为与同名测试。**表示能力边界**:状态是单值,'锁保留 + 叠一次性'表示不了,所以单击异层键会丢掉原来的锁(与'Sym 单击顶掉 L2 的一次性'同一条逻辑)。锁定态下要换锁仍需双击那个键"
  source: "真机报障「alt锁定的时候单击sym为什么sym也是锁定的」+ 用户选'单击 = 一次性 Sym(推荐)'"
  affects: [ime-interaction-model]

- time: 2026-09-30T16:54:37
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "brain update-truth: 修饰键状态迁移图 + status 术语 + 三连击删除/借用语义"
  affects: [ime-interaction-model]

- time: 2026-09-30T16:55:08
  kind: decision
  summary: "层状态从'单值'改成'一个持续态 + 最多一层临时覆盖'(用户 2026-09-30「临时借用返回上一个锁定状态 没有锁定状态返回初始状态」)。LayerState: OneShot(layer, snap) 加了恢复目标 snap(与 Hold.snap 同一形状),snap 只可能是 L1 或 Locked,一律由 LayerState.borrow() 造(= over.base,剥掉全部临时层);lockedLayer 走 base 所以借用期间底下的锁仍读得到,状态行把借来的层画成**无下划线**、锁着的 Alt 暂时不显示(同一时间只显一项层状态),借完回锁才带下划线回来。代价:旧借用会被丢掉(OneShot(L2,L1) 后再单击 Sym ⇒ OneShot(L3,L1))——一次性层的有效期只有'下一个键',用户没在下一个键上用它就去按了别的修饰键,它就该作废;锁不会。判定顺序同时改了:n>=2 必须排第一(否则双击第二击被'取消借用'吃掉,实测 16 个测试挂),且锁定后要清这一键的连击计数(否则窗口内第三击又是 n>=2,'锁着再敲一下=解锁'只在窗口外成立)"
  source: "真机报障「alt锁定的时候单击sym为什么sym也是锁定的」+ 用户两轮定案"
  affects: [ime-interaction-model]

- time: 2026-09-30T16:55:08
  kind: decision
  summary: "'芯片'这个叫法整体退役,改用抽象的 status(用户 2026-09-30「清理代码中芯片的说法 这个说法来自于早期ui的设计 使用抽象的status代替」)。改的是**概念名不是外观**:这一层关心'输入法此刻处于什么状态',与画成方块还是圆无关。ui/StatusChips.kt ⇒ ui/StatusRow.kt(StatusChips.Chip ⇒ StatusRow.Item,present 签名 (state, spec)),ImeService 的 chipMode/chipAlt/... ⇒ statusMode/statusAlt/...,renderChips/paintChips/hideChip/renderChip ⇒ renderStatus/paintStatusItems/hideItem/paintItem,view id chip_row/chip_mode/chip_fullwidth/chip_shift/chip_alt/chip_sym ⇒ status_left/status_mode/...,colors.xml chip_l2/l3 ⇒ status_l2/l3,ImeTheme.chipL2/L3 ⇒ statusL2/L3,drawable chip_lock_underline ⇒ status_lock_underline,Language.chipLabel ⇒ statusLabel、showShiftChip ⇒ showShift,FieldProfile.altChipLabel ⇒ altLabel,StatusChipsTest ⇒ StatusRowTest,全部中文注释'芯片'⇒'状态项/层状态/大写'"
  affects: [ime-interaction-model]

- time: 2026-10-10T17:42:25
  kind: decision
  summary: "上滑分区的基准宽度改成运行时现取屏宽(FlyDetector.zoneBasis),候选行改通栏使两条起手路径的五等分重合(见 [[fly-zone-basis]])"
  source: "2026-10-10 用户「飞字区域要根据屏幕宽度计算 不要写死」"
  affects: [fly-zone-basis]
