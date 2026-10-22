---
id: l6-virtual-keyboard
title: "L6 虚拟键盘:条上方浮层 + tab 页 + 上下滑翻页"
category: decision
status: active
tags: [ime, l6, ui, keymap]
created: "2026-10-15T13:39:54"
updated: "2026-10-22T08:07:54"
---

<!-- compiled_truth -->
ok
L5 面板(原 L6 虚拟键盘;2026-10-19 合并定案)

2026-10-19 用户「L6 和 L5 合并 都作为 L5」「原来的 L5 变为虚拟键盘的一个 TAB 叫做场景」:
原 L6 的 tab 虚拟键盘就是 L5,原 L5 溢出行(L4 装不下的尾巴)变成它的第一个 tab「场景」。
面板仍是**条上方浮层**,但只有**两态** `OFF ↔ L5`(2026-10-16 的三态循环作废)。

### 功能定案

1. **形态 = 条上方浮层** —— 不拉全屏,键盘表面照常用。实现:布局里 `L5PanelView` 是
   `ImeRootView` 的第一个子节点;显隐只由 `state.l5` 非空决定。
2. **内容 = 通用容器 + tab/页**(`L5Ui`)。tabs 依次为
   **场景 / 标点 / 符号 / 数学 / 制表 / 希腊 / 剪贴板**
   (2026-10-20 用户「tab 的分类和内容需要调整」:原「技术」一盘没有语义,拆开;
   随后「可以再补充一些软键盘」把字符盘铺到约 330 颗:标点 19 / 符号 84 / 数学 94 /
   制表 76 / 希腊 55,每盘 12 列 × 2 行分页、2–4 页)。
   场景 = 原 L5 溢出行(动态内容);两个字符盘**先过滤(本框合法性 + L1-L3 去重)再按网格分页**;
   剪贴板每条一行、一页两条。
   **不设「控制」tab**(2026-10-19 用户「不需要控制tab」):Tab/Esc/Enter 等控制键
   由物理键或 L3 承担,面板只留字符盘。
   「加 tab / 加页 / 加键」是**内容**,「当前 tab/页、翻页夹取」是**状态机** —— 拆开写,
   加一页不动状态机。
3. **开启 = 仅空闲态**;`Sym+Space` 开,再按一次或 **Esc(Sym+Q = 出厂 L3 的 Q)** 关。
   **锁定 Sym 不算按下**(2026-10-19 用户「锁定 sym 不算按下」):双击锁 Sym 后空格照常上屏,
   不开关面板;单击借用 / 长按 Sym 仍触发,**锁定后再物理按住 Sym 也照常开关**。评审时发现只查
   `lockedLayer` 会把「按住」一起否掉(锁定时是 `Locked(L3)`,按住时是 `Hold(L3, Locked(L3))`,
   两者 `lockedLayer` 都是 L3),组合键在锁定后彻底不可用;现在守卫放行 `Hold` 顶层 —— 按住期间
   敲过别的键(`holdUsed`),松手不算单击,锁不解除。
   **关闭不写任何字符。**
4. **翻页 = tab 内上下翻**(方向键 ↑/↓ 与上下滑动),**不跨 tab**;到头不循环。
   左右方向键 ←/→ 切 tab(面板开着时空闲态无候选,←/→ 对候选无意义)。
5. **场景 tab = L4 装不下的尾巴**:`L4PanelView` 量出前缀切点 `cut` → service 转给
   `ImeController.setOverflowCut(cut)` → `l4Keys().drop(cut)` 变成场景键。

### 网格与对齐(2026-10-19 用户定案;2026-10-20 改细网格 + 按内容跨格)

用户原话依次是「L5键盘采用网格布局对齐 有最小网格 内容可跨网格增长」「L5按键有对齐问题 应该对齐到网格」
「L5的键位不需要一样的尺寸 对齐网格就可以」。落地成 **一套细网格 + 按内容跨格 + 字居中**:

- 所有 `GRID` tab 共用 `L5Ui.GRID_COLUMNS = 12` 列网格(2026-10-20 从 8 大格 → 16 细格 →
  **12 列**:用户「tab 中按键太小」,16 列那版单格只有 ~30dp);
  不再给场景 tab 单独改列数(此前 5 列会让场景与符号盘的键边界对不上)。
- `L5Key.span` 表示占几格,**按内容估宽**算:`ceil(半宽字符数 / 4)` 格(中文/全角按 2 个半宽算)。
  于是单字符恒占 1 格(约 40dp),`@163.com`(8 半宽)占 2 格、
  `@hotmail.com`(12 半宽)占 3 格 —— **不同长度真的不同宽**,但左缘都落在同一套格线上。
  用数值估算而非真实字宽:分页在模型侧、绘制在视图侧,两边必须用同一套 span(否则会
  「模型说一页两行、视图却排了三行」)。
- 换行与分页都按格算(`paginateGrid`),长键不会被页边界劈开,也不会因列数变化被裁字。
- **面板高度固定 `L5Ui.ROWS_PER_PAGE = 2` 行**(2026-10-20 用户「L5的高度固定两行 多的内容需要翻页」):
  GRID 每页最多 `12 列 × 2 行` 格(跨格键按格算),ROWS(剪贴板)每页最多 2 条,装不下的翻页;
  内容不足两行也占两行(视图 `L5PanelView.emptyRow` 补空行)⇒ **切 tab / 翻页时面板高度不跳**。
  出厂字符盘因此改为**先过滤再统一按网格分页**(先分页再过滤会在被滤掉的位置留下空格)。
- **键帽文字居中**在格心(此前 TextView 默认贴左上角,视觉上像没对齐);剪贴板整行键仍
  左对齐、垂直居中。

### 空 tab / 合法性过滤(2026-10-19 用户定案)

- **没有可显示键的 tab 整个隐藏**,不再画「(无溢出键)」占位页:没有溢出时没有场景 tab,
  剪贴板为空时没有剪贴板 tab。
- **所有 tab 都为空时不进入空面板**:数字类可能既没有场景、字符盘又全被数字白名单
  滤掉(去掉控制 tab 后没有恒保留的功能键 tab);此时 `cyclePanel()` 不切到 L5。
  面板开着时最后一个 tab 消失(如场景尾巴装回 L4)则 `closePanels()`,不留空 `L5Ui`
  (`L5PanelView` 会取 `ui.tab`,空 tabs 取不到)。
- `L5Ui` 的 `allowText` 按当前输入框过滤文本键;功能键(`KeyOut.Special`)不是字符,始终保留。
  密码框只留可打印 ASCII;数字/电话框只留数字与 `FieldProfile.digitChars` 放行字符。
  滤空的页/ tab 一并丢弃。

### L1-L4 去重(2026-10-19 定 L1-L3;2026-10-20 用户「去重 L1-L4 已经有的就不要显示了」)

用户原话「L5 不需要 L1-3 已经有的字符」+「去重 L1-L4 已经有的就不要显示了」。字符盘用两把尺:

- `ImeRegistry.producible(layout, language, fullWidth)` 算**当前语言 + 全角开关**下 L1-L3
  真正敲得出的取值;`L5Ui.factory(producible = ...)` 把出厂字符盘里同字的文本键直接滤掉。
  不能用 `KeyLayout.producible()` 的出厂并集:语言层表会随全角开关整体切换,否则会误判。
- **再加上 L4 行里已经摆着的那些**(`ImeController.panelProducible()` = 上面那把 ∪
  `l4Keys()` 的文本输出):中文全角态 L4 摆着 `； … —— 「 」 『 』 ※`,面板再摆一遍就是屏上
  两把同字的键。L4 那一行是**跟着输入框走的**(核心键 + 该类型的键位套),所以这把尺也跟着走。
  代价:中文态的标点盘被去重掏到只剩 4 颗(`— “ ‘ ·`)⇒ 出厂字符盘补了一批
  **L1-L4 都给不了的 CJK 标点**(`〔〕〖〗〘〙〝〞〜⋯々〆〇〈〉`),标点盘才不至于空着。
- 滤空的页 / tab 一并丢弃(与输入框合法性过滤同一套空 tab 规则)。
- **场景 tab 不再重复筛**:它本来就是 `l4Keys()` 的尾巴,registry 已在 L4 前做过同一条去重。
  (L4 去重只作用在字符盘,场景 tab 自己就是 L4 的内容,不算「重复」。)
- **剪贴板 tab 不参与这把筛**:剪贴板是整段文本的快捷入口,不是单字符键盘;
  是否可粘贴仍只走输入框的 `isPanelTextAllowed` 兜底。
- 面板开着改「中文全角」开关(状态行)时,`ImeService` 调 `ImeController.refreshL5Content()`:
  重算场景与字符盘,按 tab id / 页号尽量保留当前位置(见 `L5Ui.withTabs`),不把用户弹回 tab 0。
  切语言 / 换输入框仍先收面板(那是新的空闲态)。

### 三条由此确立、别当 bug 改回去的规则

- **`L3(Sym) 空格空闲态被抢走`**:L3 层照可可扩展字符表定,原文写着「空格/回车/删除保持原功能」。
  2026-10-15 空闲态让位给浮层面板(组词中仍是空格)。`ImeControllerTest` 里那条测试已改名
  `锁定 L3 时空格照常上屏 不开面板`。**与可可的对齐就此打破,是定案不是遗漏。**
- **打开/关闭消费一次性 L3**:单击 Sym(一次性借用)+ Space 开面板后,借来的 L3 被这一下
  用掉(`consumeOneShot`)。不还的话开关完面板下一颗字母键会被当成 L3 符号敲出去。
  长按 Sym 同样触发(不是一次性层);**双击锁定不算按下** —— 锁是持续态,空格照常上屏;
  但锁定后再**物理按住** Sym 仍算按下(`Hold` 顶层),组合键照常开关、松手不解锁。
- **面板是符号盘**:出厂页全是标点/符号,**没有 a-z**;中文 `composes` 只认 a-z,
  所以面板上点符号 = 直接上屏、**面板留在原地**(好接着点下一颗),组词一开始才收面板。

### 上屏通路:两块面板共用一条

L4 中区 / L5 面板都走 `ImeController.commitL4Key`(键的输出是 `KeyOut` 的事,
不是「长在哪块面板」的事;第二条分支就是第二处会漂移的语义)。视图层 `onKey` 直连
`commitL4Tapped`。剪贴板整行键例外:走 `commitClipboardText`,见 [[clipboard-panel]]。

### 视图与窗口(易漏的集成点)

- **tab 条 = 按标签定宽 + 可横滑**(2026-10-22 用户「tab过长的时候需要左右滑动
  tab根据tab标签决定长度」):宽度 = 标签文字宽 + 左右各 16dp(内容宽),不再一律等分
  (等分在 tab 少时浪费一片、tab 多时挤字);放不下就横着滑 —— tab 条包在
  `HorizontalScrollView` 里,**键格区不在里面**,所以键格区的上下滑仍是翻页。
  切到被推出屏幕的 tab 时自动滚回可视区(只在已经滚出去时才动它)。
  判据:Titan 2(1440×1440)截屏量像素,±2px(抗锯齿边):
  ① 两个不同字数的 tab 宽度**不同**(= 文字宽 + 32dp),且文字不裁/不出省略号;
  ② tab 条内容宽 > 视口宽时横拖后 scrollX 变化、最右 tab 能滑进可视区;
  ③ 切到屏外的 tab 后它出现在可视区内;
  ④ 面板高度仍固定两行、键格区竖滑仍翻页。
  **负对照**:设备上的旧包就是等宽实现 —— 同一套测量下各 tab 宽度应当相同,
  也就是判据 ① 在旧包上会红。
- **`L5PanelView`**:tab 条 + 键格;GRID tab 用 12 列网格,长键按 `L5Key.span` 跨格,
  键高 40dp、键帽字 15sp、tab 标题 14sp(2026-10-20 用户「tab 中按键太小」),
  字居格心;剪贴板用 `ROWS` 整行。上下滑用与候选行同一套 `DragGesture` + `SpringChannel`
  物理;滑动接静默窗 `swipeGate`,**点键不看它**。
- **面板改窗口高度 ⇒ 必须 `notifyInsetsChanged()`**(不报会顶裁);开 / 关面板会改高度。
  **高度固定两行后**(2026-10-20)切 tab / 翻页不再改高度,但 `setContent()` 仍返回「是否重建」、
  服务层照旧在重建后补报一次(报少了顶裁,报多了只是重算)。`onComputeInsets` 与
  `ImeRootView.uiTop` 都取 L5 面板顶。
- `btn_l5` **常驻**,**展开/关闭只换颜色、图标形状始终是键盘 `ic_keyboard`**(2026-10-19 用户
  「虚拟键盘图标展开和关闭仅仅改变颜色即可」):OFF 染状态行前景色、L5 染主题 accent;
  旧的收起/展开箭头 drawable(`ic_collapse` / `ic_expand`)已删。
- **面板与键帽半透明磨砂**(2026-10-20 用户「颜色应该是半透明的磨砂效果」):`L5PanelView` 把
  条底色/键帽/选中 tab 强调色各套一个 alpha(`PANEL_ALPHA 0xCC` / `KEY_ALPHA 0xD9` /
  `ACCENT_ALPHA 0xE6`,RGB 不变);真正的「磨砂」是**窗口背景模糊**(background blur)——
  `ImeService.syncL5Blur` 在**面板可见**时调 `Window.setBackgroundBlurRadius(24dp)`
  (API 31+ 且 `isCrossWindowBlurEnabled` 才生效),关面板/进组词即归零。
  **不能用 `FLAG_BLUR_BEHIND` + `setBlurBehindRadius`**(2026-10-20 用户「不是要全屏模糊」):
  按 Android 文档,blur-behind 糊的是**窗口背后的整个屏幕**,与窗口多大无关 —— 面板一开
  App 整屏都糊;background blur 只糊**窗口边界以内**的内容(空闲态窗口 = 键盘 + 面板,
  键盘面不透明,肉眼只在半透明面板处看得到模糊)。
  半透明与模糊缺一不可:面板底不透明则模糊透不出来;设备不支持跨窗口模糊时静默退回「只有半透明底」。
  **真机已确认 background blur 真的生效**(2026-10-20):拿 `adjustNothing` 的实验场当背景
  (App 内容不被顶上去,面板背后真有日志文字),面板开/关两图逐像素比对 —— 面板之外**像素完全
  相同**(没有全屏糊),面板背后的文字高频能量比值 **0.03**;若没有模糊,0xCC 黑只把对比度压到
  0.2 倍(比值应 ≈0.2),0.03 说明文字是**被抹开**而不是被压暗 ⇒ 模糊生效。
  **可复用的判定法**:跨窗口模糊肉眼分不出来(背后是纯色时完全看不出),就用「面板开/关两图的
  同一区域高频能量比值」判定 —— ≈0.2 是只压暗,远小于 0.2 才是真模糊。

### 验证状态

`L5PanelTest` 与全量 `:app:testDevUnitTest` 全绿(388 条);`:app:assembleDev` 成功。2026-10-19 真机
(TTANCN2100002663)冒烟通过:按钮开合、切 tab、上下滑翻页、点键上屏且面板留原地、
**场景 tab 长域名完整不裁(4 颗/行)**、键帽文字居中、
**面板已无控制 tab**;手机号框只剩场景(合法电话区号),密码框只剩技术(ASCII);
**L1-L3 去重:全角态标点页只剩 `； … — 「 」 “ ” ·` 等 L3 敲不出的字,切半角后
`， 。 ！ ？ ： （ ） 《 》` 等回到面板,且面板开着切换时原地刷新**;**数字框没有可显示键时
点键盘图标不打开空面板、不崩**;**键盘图标开/关只变颜色(键盘→accent),形状不变**;
剪贴板空态、面板 Esc 关(物理 Sym+Q)、组词开始收面板。
2026-10-20 真机(TTANCN2100002663)复验**固定两行高**:邮箱框场景、标点页、技术页切换时面板顶与
tab 条位置逐帧一致;不满行的页(如技术第 2 页仅 `Ω` 一颗)补空第二行,高度仍与满行页相同;上下滑翻页正常。
同日用户定「键位不需要一样的尺寸 对齐网格就可以」后改为 **16 列细网格 + 按内容估宽跨格**,真机复验:
邮箱场景 10 颗域名左缘对齐同一套格线、宽度随内容分(2 格 `@163.com` / 3 格 `@hotmail.com`),
标点页 16 列满行窄键(单字符约 38dp,不再是 1/8 屏)、技术页 16 希腊字 + 补空第二行。
2026-10-20 真机(TTANCN2100002663)复验**tab 拆细 + 12 列大键**:邮箱框弹出面板,tab 条为
**场景 / 标点 / 符号 / 希腊**(剪贴板空时隐藏);场景 tab 9 颗邮箱域名键按 2–3 格跨格、齐同一套格线;
标点 22 颗(两行 12+10);符号 44 颗分两页,上滑翻页正常(第 2 页是货币与箭头);希腊 17 颗(12+5);
键高 40dp、字 15sp,比 16 列那版明显好点。
`Sym+Space` 与「锁定 Sym + 空格」未用 adb 复现(`adb input keyevent 63` 会拉系统输入法选择器,
不能代表物理 Sym 键),由 JVM 覆盖(`L5PanelTest` 两条 + `ImeControllerTest` 一条);
剪贴板非空条目点按、场景 tab 开着时换框/换层的原地刷新仍未真机覆盖。详见 [[pending-todos]]。

2026-10-20 真机(TTANCN2100002663)复验**磨砂不再全屏**:同一输入框、面板关/开两张截图逐像素比对,
差异只落在面板自身区域(y 1061–1400),面板以外的 App 内容完全一致 → blur-behind 的整屏模糊已消除
(日志 `l5blur: true ... (background,窗口内)`)。同日又用 `adjustNothing` 的实验场当背景做了数值判定,
**background blur 确实生效**(背后文字高频能量比值 0.03,无模糊时应 ≈0.2),判定法见上。
注意:普通可 `adjustResize` 的 App 里,面板出现时 App 内容会被顶到面板上方,面板背后是窗口底色 ——
磨砂纹理肉眼几乎看不出,这是布局事实,不是 bug;只有 edge-to-edge / `adjustNothing` 的 App 才真能看到
「透出背后内容」。


## Timeline

- time: 2026-10-15T13:39:54
  kind: decision
  summary: "Created this page: L6 虚拟键盘:条上方浮层 + tab 页 + 上下滑翻页"
  source: created via brain create-page
  affects: [l6-virtual-keyboard]

- time: 2026-10-15T13:40:12
  kind: decision
  summary: "L6 虚拟键盘:L4/L5/L6 三块面板共用 commitL4Key 上屏通路;状态机在 L6Ui,视图在 L6PanelView"
  source: brain update-truth
  affects: [l6-virtual-keyboard]

- time: 2026-10-16T03:19:42
  kind: reversal
  summary: "2026-10-16 三态化(reversal):L6 的开关从「sym+space 二态」改成**三态循环** OFF→L5→L6→OFF。用户原话「把 L5 的图标的工作改为 L5 L6 和关闭状态的切换 sym+space 的功能和切换图标的功能一致 默认状态不显示 L5 和 L6」。要点:① PanelMode(OFF/L5/L6) 进 ImeState,ImeController.cyclePanel() 是唯一推进入口, sym+space 与状态行右区 btn_l5 走同一通路;② L5 行/按钮的显隐与图标从视图层变量(l5RowShown)收进 state.panelMode,btn_l5 **常驻**(不再「有溢出才显示」),图标随态(展开箭头 ic_expand / 键盘 ic_keyboard / 收起箭头 ic_collapse),非 OFF 态用主题 accent;③ 组词开端/换框/切语言/窗口收起/Esc 全走 ImeState.closePanels();④ 旧「sym+space 二态开关 L6」的定案作废。附带:连续敲 Sym 三次会撞双击窗(单击借 L3 / 双击锁 L3 / 再敲取消),所以三态循环的单测用 cyclePanel() 直测,不连敲三次 symSpace。"
  source: "2026-10-16 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T19:48:49
  kind: decision
  summary: "2026-10-19 合并后重写:L6 虚拟键盘与原 L5 溢出行合并为一块两态 L5 面板,场景为第一个 tab"
  source: "2026-10-19 用户合并定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T19:48:58
  kind: reversal
  summary: "2026-10-19 合并(reversal):L6 虚拟键盘与原 L5 溢出行合并为一块 L5 面板。用户原话「L6和L5合并 都作为L5」「原来的L5变为虚拟键盘的一个TAB 叫做场景」。要点:① 面板两态 OFF↔L5(2026-10-16 三态作废);② 原 L6 tab 键盘即 L5,原 L5 溢出行成为第一个 tab「场景」(L4 装不下的尾巴,8列×16颗/页);③ 其余 tab 标点/技术/控制/剪贴板;④ 视图 L6PanelView→L5PanelView,状态 L6Ui→L5Ui、state.l6→state.l5、l6Open→l5Open;⑤ 旧 L5 独立行与分割线删除,btn_l5 常驻,OFF 展开/L5 收起;⑥ 场景内容由 L4 onOverflowChange 的 cut 喂 setOverflowCut。"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard clipboard-panel pending-todos]

- time: 2026-10-19T20:47:34
  kind: decision
  summary: "2026-10-19 真机冒烟(TTANCN2100002663):L5 面板按钮开/关、场景/标点/控制/剪贴板切 tab、标点与控制上下滑翻页、点键上屏面板留原地、场景 tab 显示 L4 邮箱域名溢出、剪贴板空态、Esc 键关、组词开始收面板均通过。冒烟发现 8 列会把 @hotmail.com 裁成 @hotmail,场景 tab 改为 5 列 10 颗/页(SCENE_COLUMNS=5)后完整显示;符号盘仍 8 列。Sym+Space 双入口未用 adb 复现(adb input keyevent 63 会拉系统输入法选择器),由 L5PanelTest 覆盖。"
  source: "2026-10-19 真机冒烟"
  affects: [l6-virtual-keyboard pending-todos]

- time: 2026-10-19T20:54:51
  kind: decision
  summary: "2026-10-19 真机冒烟后校正:场景 tab 5 列/10 颗,切 tab 高度变化补 notifyInsetsChanged,验证状态更新"
  source: "2026-10-19 真机冒烟收尾"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T22:43:32
  kind: decision
  summary: "2026-10-19 网格定案:所有 GRID tab 共 8 列基础网格,长键按 span 跨 2-3 格,键帽文字居中;场景不再单独 5 列"
  source: "2026-10-19 用户「网格布局 + 内容跨网格 + 对齐到网格」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:00:53
  kind: decision
  summary: "2026-10-19 用户「不需要控制tab」:出厂 tabs 改为 场景/标点/技术/剪贴板;控制键走物理键或 L3"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:19:14
  kind: decision
  summary: "2026-10-19 用户「L5 不需要 L1-3 已经有的字符」:字符盘按 ImeRegistry.producible 去重,并随全角开关原地刷新"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:21:43
  kind: reversal
  summary: "2026-10-19 用户「不需要控制tab」(reversal):撤掉此前「标点/技术/控制/剪贴板」里的控制 tab;Tab/Esc/Enter/Del/Home/End/PgUp/PgDn 不再做成面板键,改由物理键或 L3 承担。面板 tabs 现为 场景/标点/技术/剪贴板。"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:33:46
  kind: decision
  summary: "2026-10-19 补空面板守卫:所有 tab 都为空时不进入 L5,面板开着时最后一个 tab 消失则收起"
  source: "2026-10-19 数字类冒烟 + L5PanelTest"
  affects: [l6-virtual-keyboard]

- time: 2026-10-19T23:49:29
  kind: decision
  summary: "2026-10-19 用户「虚拟键盘图标展开和关闭仅仅改变颜色即可」:btn_l5 图标形状恒为键盘,仅切换 tint;删除 ic_collapse/ic_expand"
  source: "2026-10-19 用户定案"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T00:02:32
  kind: decision
  summary: "2026-10-20 修「L5按键网格没对齐」(用户报障):权重槽外框不能带水平 margin —— margin 加在外框上时,每行要扣的 margin 总数随该行键数/跨格组合变化,换算出的 1 格宽逐行不同,列错位;尤其不满行补空槽时最后一排的键会被撑宽。改为外框无水平 margin(只按 span 占权重),3dp 键距画在内层 TextView 的 marginEnd 上;span 格外框宽正好等于相邻 span 个单格键+键距。真机逐页验证:标点/技术/场景含不满行的页,最后一排单键与满行键同宽、同列。"
  source: "2026-10-20 用户报障 + 真机 TTANCN2100002663"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T00:28:50
  kind: decision
  summary: "2026-10-20 用户「L5的高度固定两行 多的内容需要翻页」:面板高度定死 L5Ui.ROWS_PER_PAGE=2 行。① 所有 GRID tab 每页最多 8x2 格,ROWS(剪贴板)每页最多 2 条,装不下的翻页;② 出厂字符盘改为**先过滤(本框合法性 + L1-L3 去重)再统一按 paginateGrid 分页**——先分页再过滤会在被滤掉的位置留空格;③ 视图 L5PanelView 用 emptyRow 垫空行,内容不足两行也占两行,切 tab/翻页高度不跳;④ 因此切 tab/翻页不再改窗口高度,notifyInsetsChanged 仍保留在重建后补报。真机 TTANCN2100002663 复验:场景(8 枚跨 2 格域名)/标点第1、3页/技术第1、2页切换,面板顶与 tab 条逐帧一致;标点第3页 7 颗只占一行、技术第2页仅 Ω 一颗,均补空第二行且高度相同。测试改为「每页最多两行网格」。"
  source: "2026-10-20 用户定案 + 真机 TTANCN2100002663"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T00:49:39
  kind: decision
  summary: "2026-10-20 改细网格:GRID_COLUMNS 8→16,span 按内容估宽(ceil(半宽/4)),单字符占 1 格约 38dp;文档同步"
  source: brain update-truth
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T01:35:59
  kind: decision
  summary: "2026-10-20 面板半透明磨砂:条底/键帽/选中 tab 套 alpha,面板可见时开窗口 backdrop blur(24dp)"
  source: "2026-10-20 用户「颜色应该是半透明的磨砂效果」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T06:50:23
  kind: evidence
  summary: "2026-10-20 adb 真机复验(cd6ecf8 之后):图标开/关只换色(键盘形白↔accent)、场景键与标点上屏且面板留原地、标点页上下滑翻页 1/2↔2/2、面板底 blur 生效(l5blur: true radius=24.0dp)、关面板后 L4 行恢复。踩坑记录:adb 注入 KEYCODE_SYM(63) 会被 MTK A85ShortcutFunction 拦去弹「选择输入法」,注入 KEYCODE_ESCAPE 会直接收掉整个输入法(合成事件 source=0,不走 IME),所以 Sym+Space / 锁定 Sym 空格 / Esc 关面板只能物理键验;另:邮箱框首次聚焦时 Bitwarden 自动填充弹窗会抢焦点,点场景键后可能顺带把面板收起,是环境干扰不是面板逻辑。"
  source: "2026-10-20 Titan 2(TTANCN2100002663)adb 冒烟"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T07:15:43
  kind: decision
  summary: "锁定 Sym 不算按下:锁着时空格照常上屏,不开关 L5 面板(单击借用/长按仍触发);同步改名的单测与规则原文"
  source: "2026-10-20 用户「确保锁定sym时按space不会出发展开或者折叠L5」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T07:16:03
  kind: decision
  summary: "验证状态补记:锁定 Sym + 空格由 JVM 覆盖,物理键仍未真机验证"
  source: "2026-10-20 代码评审"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T07:16:50
  kind: decision
  summary: "锁定 Sym 不算按下:handleNormalKey 的 Sym+Space 分支加 state.lockedLayer != Layer.L3 守卫。此前 layer==L3 同时覆盖单击借用/按住/双击锁定三种,双击锁 Sym 后每次空格都开关面板,空格打不出来;ImeControllerTest 旧用例还把该行为钉成期望。同步:ImeControllerTest 改为「锁定 L3 时空格照常上屏 不开面板」,L5PanelTest 新增两条(锁定态不开面板 / 面板开着时锁定态空格只上屏不折叠);两条独立 Sym+Space 用例之间需 exitTapWindow(400ms),否则第二击是双击=锁定,旧代码靠「锁定也算按下」掩盖了这一点。全量 testDevUnitTest 绿。"
  source: "2026-10-20 代码评审与修复"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T09:17:57
  kind: decision
  summary: "磨砂改用 background blur(Window.setBackgroundBlurRadius):只糊窗口边界内;FLAG_BLUR_BEHIND 糊的是整个屏幕,作废"
  source: "2026-10-20 用户「不是要全屏模糊」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T09:18:03
  kind: reversal
  summary: "磨砂实现反转:cd6ecf8 用 FLAG_BLUR_BEHIND + setBlurBehindRadius 做 backdrop blur,用户开面板看到整屏糊掉(「不是要全屏模糊」)。按 Android 文档 blur-behind 糊的就是窗口背后的整个屏幕,与窗口尺寸无关,所以改用 background blur:面板可见时 Window.setBackgroundBlurRadius(24dp),只糊窗口边界以内;面板半透明 alpha 不变。真机回归待验(改完时宿主 MCP 隧道 530,装不上新包)。"
  source: "2026-10-20 用户反馈"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T10:30:25
  kind: decision
  summary: "真机复验:磨砂不再全屏(面板开/关截图仅面板区域有差异);普通 adjustResize 的 App 面板背后是窗口底色,纹理几乎看不到"
  source: "2026-10-20 Titan 2 截图比对"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T11:09:16
  kind: decision
  summary: "锁定态守卫补 Hold 例外:锁着时物理按住 Sym + 空格仍开关面板,松手不解除锁定(只查 lockedLayer 会把按住一起否掉)"
  source: "2026-10-20 代码评审"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T12:03:39
  kind: decision
  summary: "真机确认 background blur 生效:adjustNothing 实验场当背景,面板开/关两图比对,背后文字高频能量比值 0.03(无模糊应≈0.2);判定法可复用"
  source: "2026-10-20 Titan 2 数值判定"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T13:06:49
  kind: decision
  summary: "L5 面板:tab 按语义拆细(场景/标点/符号/希腊/剪贴板,原「技术」拆成符号+希腊并补货币箭头);网格 16→12 列,键高 36→40dp,键帽字 13→15sp,tab 标题 14sp"
  source: "2026-10-20 用户「tab的分类和内容需要调整 tab中按键太小」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T15:37:45
  kind: decision
  summary: "真机复验 tab 拆细 + 12 列大键:tab=场景/标点/符号/希腊,符号两页可上滑翻页,键高 40dp 字 15sp"
  source: 2026-10-20 Titan 2
  affects: [l6-virtual-keyboard]

- time: 2026-10-20T17:37:00
  kind: decision
  summary: "面板去重扩到 L4:字符盘滤掉 L4 行里已有的字符(中文全角态 L4 的 ； … 「 」 等),并补一批 L1-L4 都给不了的 CJK 标点,免得标点盘被掏空"
  source: "2026-10-20 用户「去重L1-L4已经有的就不要显示了」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-21T01:36:01
  kind: decision
  summary: "字符盘铺开到约 330 颗:新增「数学」「制表」两盘,符号/希腊补齐,共五盘(标点19/符号84/数学94/制表76/希腊55),补的字符全过了 L1-L4 去重"
  source: "2026-10-20 用户「可以再补充一些软键盘」"
  affects: [l6-virtual-keyboard]

- time: 2026-10-22T08:07:54
  kind: decision
  summary: "tab 条按标签定宽 + 可横滑(内容宽,放不下横滑,键格区不受影响,选中项自动滚回可视区);附四条判据与负对照"
  source: "2026-10-22 用户「tab过长的时候需要左右滑动 tab根据tab标签决定长度」"
  affects: [l6-virtual-keyboard]
