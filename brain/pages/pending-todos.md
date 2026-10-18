---
id: pending-todos
title: "待办清单(2026-10-12 收尾时点)"
category: project
status: active
tags: [todo, backlog]
created: "2026-10-12T16:55:18"
updated: "2026-10-18T02:15:24"
---

<!-- compiled_truth -->
## 待办(2026-10-18 更新,按价值排序)

### 0. 真机冒烟总清单(本机无真机、无 `/dev/kvm`,只能真机跑)

- **L6 / 剪贴板 / 三态开关**(同 2026-10-16 定稿内容,见 [[l6-virtual-keyboard]] / [[clipboard-panel]]):
  L6 开合/点键/翻页/切 tab/Esc 关;剪贴板点条目上屏(纯字母不进组词)、敏感遮罩、
  空态、复制后原地刷新;三态 OFF→L5→L6→OFF、双入口、默认 OFF、组词/换框/Esc 收面板。
- **librime 4ea39b33**(2026-10-17 升级后):候选正常、上屏正常、自动分隔符正常、
  词典排序手感(10-13 读音校准生效)。
- **2026-10-18 喂撇定案真机验证项**:
  1. 拼音中长按 L 插 `'`,连打两下只出现一个撇(入口去重);
  2. `xi'an` 类切分真机生效(应出西安,而不是引擎自读的解);
  3. 选词后跨撇消费:`ni'xian` 选「你」后剩余应为 `xian`,候选栏显示 `ni'xian` 带自动分隔符;
  4. 物理键盘 `'` 键(组词中)同样插分节符且不重复。
- **E2E 场景 `l6-keyboard` 已写好**(`tools/aitest/scenarios.mjs`),尚未跑过;剪贴板/三态/分节符尚无 E2E 场景。

### 1. ~~撇号坐标~~ **2026-10-18 完结**:喂撇定案,坐标全族为含撇直接下标

- 用户选路线「做分隔符的提交」:`RimeEngine.query` 原样喂撇(`xi'an` 出西安),
  `rawAfterConsuming` 下标语义、`displaySpelling` 直映、`ImeState.appendRaw` 去重,
  全链注释 + 单测翻成同一含撇口径,377 测试绿。当日上午「数非撇字符」口径随之作废。
- 设备验证项**并入上面第 0 条**(长按 L / 物理 `'` / 跨撇消费 / 接连撇去重)。
  口径全案见 [[rime-candidate-consumption]]。

### 2. `five-key-slots` E2E 断言脆弱:三次查询候选序随词频漂移

- **现象**:断言要求 Shift/Alt/Ctrl 三键上屏**互异的词**;引擎候选序在查询间重排。
- **证据**:select 埋点显示三键 zone 全部正确(0/4/3),Ctrl 选的就是它槽位当时显示的词 —— 功能无 bug,是断言假设了候选序不变。
- **修法方向**:把断言改成**不变量**——从 select 日志解析每键的 zone(Shift=0/Sym=1/空格=2/Ctrl=3/Alt=4)与 consumed 映射,不再比词面。

### 3. 手势单测把横滑死区钉在了错的常量上(`DEAD_DP` vs `DEAD_DP_SWIPE`)—— **2026-10-16 已修**

- 已改:`CandidateBarPageDragTest` / `GesturePhysicsTest` 的 `dead` 改用 `DEAD_DP_SWIPE`;
  `GesturePhysicsTest` 里"density 假设 2.0 ⇒ 32px"的陈旧注释改回 16px;两处补上密度假设说明。
- 保留这条只为记下**为什么会错**:那行写于 2026-10-02(当时只有 `DEAD_DP`),10-04 拆成两个常量时
  没跟改 ⇒ 10-04~10-09 测试钉 16px、生产用 8px(那 5 天正是「抖动被当翻页」,真机发现);
  10-09 改回 8dp 后又巧合相等。`PageSettleTest` **故意**只钉"横滑死区 ≥ 手抖"的绝对下限、
  不钉两常量的相对关系 ⇒ 再次分家仍会静默失真 —— 所以现在是靠"用对名字"而不是靠断言保证。

### 4. 手势那条路上的小尾巴 —— **2026-10-16 已修**

- 删掉 `pageDragProgress`(生产 0 调用,只是 `DragGesture.progress(pageDragTravel(...))` 的包装),
  两个测试改为直接写与生产同一个表达式;**顺带清两处过期注释**(`DEAD_DP_SWIPE` 首行、那 1/4 口径)。
- 新增 `pageDragDeadZone(externalDrive, pageDeadPx)` 纯函数 + `PageSettleTest` 补测(与 `pageCommitLine` 对称)。
- **更正一处与代码不符的说法**:死区多扣一次的症状不是"进度 NaN",而是 `threshold - dead` 变负
  ⇒ 进度被翻转、刚起手就满进度("一碰就翻");NaN 要求 `dead == threshold` 且 travel 也恰好相等。

### 5. 误触:三道防线已按 2026-10-16 定案收紧,**前瞻性否决仍未做**

- **现象(用户 2026-10-16)**:死区 +「按键后 300ms 静默窗」组合起来仍有不少误触,飞字与翻页都会被"打字的手"意外触发。
- **2026-10-16 已做(口径见 [[sensitivity-tiers]])**:见上一版记录;待做只剩"前瞻性否决(触点锚定)"。
- **修法方向**:log Δt 对齐数据 → 纯状态机否决(触点锚定:按键瞬间 anchor 指尖位置,
  anchor 位移超阈值的窗口内不响应滑动类手势)。


## Timeline

- time: 2026-10-12T16:55:18
  kind: decision
  summary: "Created this page: 待办清单(2026-10-12 收尾时点)"
  source: "2026-10-12 收尾"
  affects: [pending-todos]

- time: 2026-10-12T16:57:55
  kind: decision
  summary: "建立待办清单:撇号坐标矛盾(探针≠生产)、five-key-slots 断言脆弱改 zone 不变量、调出复位的语言口径待确认"
  source: "2026-10-12 收尾"
  affects: [pending-todos]

- time: 2026-10-13T16:52:13
  kind: decision
  summary: "2026-10-13 追加第 0 项:librime 0d00afba 设备冒烟待补(USB 掉线+焦点异常卡住,构建单测已过)"
  source: "2026-10-13 收尾"
  affects: [pending-todos]

- time: 2026-10-14T12:39:45
  kind: decision
  summary: "2026-10-14:追加第 1 项长按 L2 符号上屏(未开工,触发路径待澄清);分隔符翻页跟随+边界补撇已完成"
  source: "2026-10-14 收尾"
  affects: [pending-todos]

- time: 2026-10-15T13:41:16
  kind: decision
  summary: "L6 虚拟键盘定案(同一次对话问定):形态=条上方浮层;内容=通用容器+出厂页;开启=仅空闲态,再按或 Esc 关;翻页=tab 内上下翻。视图还有一层设备冒烟欠账待真机还,见本页第 0 项"
  source: "2026-10-15 L6 虚拟键盘"
  affects: [pending-todos, l6-virtual-keyboard]

- time: 2026-10-15T13:52:16
  kind: decision
  summary: "2026-10-15 长按 L2 符号 + 拼音中长按 L 插分节符已完成(四态真机 + E2E 16/16 + 9 条单测),从待办移入已完成"
  source: "2026-10-15 长按 L2 符号定案"
  affects: [pending-todos]

- time: 2026-10-15T13:52:16
  kind: reversal
  summary: "撇号坐标项优先级上调:' 从 2026-10-15 起有稳定入口(长按 L 插分节符,不可关闭),撇号坐标矛盾从'几乎不可达'升级为可达且会被真用到的路径"
  source: "2026-10-15 长按 L2 符号定案"
  affects: [pending-todos]

- time: 2026-10-16T08:18:38
  kind: decision
  summary: "2026-10-16:长按字母键从「只出 L2」扩成**三选一**(关闭/L2/L3,见 [[ime-interaction-model]]),含一个真机取证过的修正(短按不再闪层状态)+ 长按状态机收口(LongPress 四迁移);单测 331 全绿。状态机整理本身也定了口径:**sealed + 纯函数,不引库**([[state-machine-approach]]);输入机与候选栏手势机按用户决定暂不动。待办仍是撇号坐标 + five-key-slots 两条。"
  source: "2026-10-16 收尾"
  affects: [pending-todos]

- time: 2026-10-16T08:23:01
  kind: evidence
  summary: "2026-10-16 长按三选一(L2/L3)+ 短按不闪修正的回归:E2E 16/16(2 个 AI 判卷场景因未配 AITEST_API_KEY 依例 pending,非失败),无崩溃;单测 331 全绿;真机三态取证(短按不闪 / L2 长按出 ’ 且按住出 alt / L3 长按 T 出《、L 触发 move_end)。"
  source: "2026-10-16 E2E(tools/aitest)+ 真机 TTANCN2100002663"
  affects: [pending-todos]

- time: 2026-10-16T03:20:06
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-16 收尾"

- time: 2026-10-16T11:36:39
  kind: note
  summary: "2026-10-16 清扫死代码时发现(未处理,记一笔):pageDragProgress 是 internal 纯函数但**生产里没有调用者** —— CandidateBarView 的跟手翻页提交判定直接用 DragGesture.progress(pageDragTravel(...), threshold, dead) 又写了一遍,于是 CandidateBarPageDragTest 钉住的是那份**没被生产用到**的实现(改生产那条路不会让这些测试变红)。要么让生产改调它(去重),要么把测试对准生产那条路。同次清扫已把它和 pageDragSlide 的无用形参删掉(编译器点名 never used)。"
  source: "2026-10-16 死代码清扫"
  affects: [pending-todos]

- time: 2026-10-16T13:06:44
  kind: reversal
  summary: "2026-10-16 调查修正当天那条 pageDragProgress 结论(当时写「生产里用 DragGesture.progress 又写了一遍」,读起来像两份实现,不准确):核对生产两处站点(setPageDrag 过阈值当帧翻、endPageDrag 松手提交判定),用的都是 `DragGesture.progress(travel, pageCommitPx(), pageDragDeadPx())`;helper 只是 `DragGesture.progress(pageDragTravel(x,baseX,dir), commitPx, dead)` 的一层包装 —— **公式只有一份**,它包装的两个原语生产都在用,所以「去重」的价值很小。helper 的来历:23edb79 里生产**曾经**用它画位移,2026-09-30「跟手=位移,绘制改用 slide()」+ 2026-10-02「dead 必填」两次改动后,它退化成只给单测用的 x 空间入口(仓里同类先例:PinyinInputMethod.forTest,明确标注「生产代码不用这个」)。**真正值钱的发现(同一轮排查):** CandidateBarPageDragTest / GesturePhysicsTest 把横滑死区写成 `DragGesture.DEAD_DP * 2f`,而横滑路径生产用的是**另一个常量** `DEAD_DP_SWIPE`(见 CandidateBarView.pageDeadPx)。这行写于 2026-10-02(e06d837),**早于** 2026-10-04(9d57cb4)把死区拆成横/纵两个(那时 SWIPE=4dp)⇒ 10-04~10-09 这 5 天里测试钉的是 16px 死区、生产用的是 8px,而那个窗口正是「手指抖动被当成翻页」(bda2c7d,真机发现、不是测试发现)。10-09 改回 8dp 后两个数又巧合相等 ⇒ 测试现在只是「碰巧对」;PageSettleTest 又**故意**只钉「横滑死区 ≥ 生理性手抖」这个绝对下限、不钉两个常量的相对关系 ⇒ 再次分叉是被允许的,然后测试会再次静默失真。修法方向:测试改用 DEAD_DP_SWIPE(顺带写清密度假设);改掉 DEAD_DP_SWIPE 首行那句过期的「取 DEAD_DP 的一半,即 4dp」;pageDragProgress 的 KDoc 里「死区 = 阈值的 1/4」也是 2026-10-02 前的旧口径。另有一处未覆盖:`pageDragDeadPx()`(外部驱动 → 0,失败模式是进度 NaN)没有单测,而同类「输入里带状态」的判断 pageCommitLine 有 —— 可对称地抽成纯函数补测。"
  source: "2026-10-16 调查(pageDragProgress / 死区常量)"
  affects: [pending-todos]

- time: 2026-10-16T14:02:15
  kind: decision
  summary: "记录调查发现的真问题:手势单测把横滑死区钉在 DEAD_DP(生产横滑用 DEAD_DP_SWIPE);另记 pageDragProgress 生产 0 调用 + 两处过期注释 + pageDragDeadPx 未覆盖"
  source: "2026-10-16 调查(pageDragProgress / 死区常量)"
  affects: [pending-todos]

- time: 2026-10-16T14:44:50
  kind: decision
  summary: "记入待办 5:手势提交之后到来的按键事件没人管(误触)—— gate 只朝过去看且只在起手问一次;飞字不可撤只能延后注入,翻页可撤暂定页;建议用触点锚定否决而非纯计时窗;并记下横滑 8px 与飞字 160px 的 6.4 倍不对称"
  source: "2026-10-16 讨论(误触/否决窗)"
  affects: [pending-todos]

- time: 2026-10-16T15:53:44
  kind: decision
  summary: "待办 5 更新:三道防线已按 2026-10-16 定案收紧(键盘面死区 12dp / 横滑档位 ×3 / 静默窗补上\"按住键期间\"),前瞻性否决(触点锚定)仍未做;并把 6.4 倍不对称重估为 3.5 倍"
  source: "2026-10-16 用户定案 + 368 单测全绿(c6ece76)"
  affects: [pending-todos]

- time: 2026-10-18T01:30:16
  kind: decision
  summary: "2026-10-17 librime 更新收尾:新增第 6 项(librime 4ea39b33 设备冒烟欠账),第 0 项保持;撇号坐标(第 1 项)与误触前瞻否决(第 5 项)未动;另记 CI 账单红线(基础设施,非代码)"
  source: "2026-10-17 整理状态"
  affects: [pending-todos]

- time: 2026-10-18T01:42:34
  kind: decision
  summary: "待办 1(撇号坐标)代码侧完成:rawAfterConsuming 改数非撇字符(displaySpelling 同族),四处注释统一,单测 13 条+全量 370 全绿。真机取证(长按 L 插撇 + 跨撇选词)并入 #0/#6 冒烟一起还。新发现遗留设计问题记入 rime-candidate-consumption:手工撇对引擎无效,是否喂撇待用户定案"
  source: "2026-10-17 梳理拼音消费"
  affects: [pending-todos, rime-candidate-consumption]

- time: 2026-10-18T02:15:24
  kind: decision
  summary: "2026-10-18:待办 #1(撇号坐标)完结——喂撇定案落地并提交;手工分节符真机冒烟并入 #0 清单"
  source: "喂撇全链落地 commit(当日);探针锚点;377 测试绿"
  affects: [pending-todos]
