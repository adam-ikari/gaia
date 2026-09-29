---
id: ime-strategy-registry
title: "策略注册表与 L4 上下文键位（语言/输入法/输入框三轴）"
category: decision
status: active
tags: [策略, 注册表, L4, 扩展性]
created: "2026-09-28T19:06:35"
updated: "2026-09-30T02:34:10"
---

<!-- compiled_truth -->
# 策略注册表与 L4 上下文键位

## 结论

- **语言与输入法是策略**:`Language`(id/chipLabel/methodId/showShiftChip/shiftTapsEnabled/l4Extras/mapText/onEnter/composes/commitText)与 `InputMethod`(query/onCommitted/removeUserWord/userCount)由 `ImeRegistry` 注册;`ImeState.languageId: String` 取代 `ImeMode.CN/EN` 枚举。加语言/加输入法只注册不改核心(状态机 `ImeController`、UI `ImeService` 都按 id 查策略),`ImeRegistryTest` 即回归证明。
- **状态行 L4 中区 = 语言补差键(排最左) + 字段核心键的合法子集 + 输入类型选的键位套**(三个来源合成,再过一道 producible 筛):
  - **顺序定案**:语言补差键(输入法注册的键)**排最左**——它语义上属于"这个输入法随时要用",可见性优先;其后是核心键,再后是键位套(用户 2026-09-28 指定,真机已验)。当前拼音**不注册** L4 补差键(分节符改走 v 键,见下),机制留给未来的语言;
  - `FieldKind.from(EditorInfo.inputType)` → TEXT / URL(`TYPE_TEXT_VARIATION_URI`) / EMAIL / 数字类六型(number / signed / decimal / signeddecimal / phone / datetime);
  - URL → `[L4 url]` 段,出厂预置 `http:// https:// .com`(**`/` 已去掉**:L2 键帽 Alt+o 已能出,L4 不重复,2026-09-28 用户定案);文件缺段回落出厂预置(老 keymap.txt 也拿得到);
  - EMAIL → `[L4 email]` 段,出厂按优先级排:短后缀 `.com .net .org .cn .edu` 在前、完整域名 `@gmail.com` 等在后(装不下的尾巴自然落 L5);
  - 数字类 → `[L4 number|signed|decimal|signeddecimal|phone|datetime]` 段,核心键 `； $ \` 在数字类全非法,由 `l4CoreKeys = emptyList()` 滤掉。
- **L4 显示前必过 producible 筛**(2026-09-29 用户定案,推翻 2026-09-28"不做程序筛选"——`KeyLayout.producible()` 复活,但位置从视图挪到注册表 `ImeRegistry.l4Keys`,合成之后就筛):取本机 L1/L2/L3 三层输出的并集,L4 键的输出命中它就不显示。**筛必须在运行时**:编译期把内容写死会失去不同手机的适配能力(键位表随机型不同,某层没有某键时 L4 要自动少一把、多一把)——用户明确否掉"编译时过滤器 + 固定内容"方案。
- **数字态(digitsOnly)**:聚焦即锁 L2 数字层(免按 Alt);Alt 芯片 = `0-9` + 该类型合法单字符;物理白名单 = `0-9` + `FieldProfile.digitChars`(这些字符 L2 上都有键帽,直接敲)+ **L4 当前显示的键输出**(多字符组合一并放行)。
  - **L4 放短语组合**(2026-09-29 定案,`+86` 这类):合法单字符 `- . / : + * #` 全在 L2,按上面那把尺会被整段筛空,而"L4 不能空"是**要求**不是筛出来的结果——物理键一次只出一个单字符,多字符组合天然 L1-L3 按不出,所以数字套必须放组合。**出厂只有电话类型有键**(`+86 +1 +81 +44` 国际区号,用户 2026-09-29 原话"只有电话输入时候需要+86等按键 其他的没有"),其余五个数字类型出厂为空(合法字符全在 L2、没有组合可放,不是被筛空),可在 `[L4 datetime]` 等段自写。
- **L5(状态栏上方的虚拟按键行)只装 L4 装不下的溢出尾巴**(2026-09-29 用户定案:**"现在还不做用户短语功能"**,曾一度加的 `[L5]` 用户短语段已整体撤回);**左侧对齐、键宽用自然宽**(不是 L4 那套等宽槽/居中,尾行是 L4 的续页,靠左才读成同一条序列)。
- **拼音分节符走 v 键**(`Language.mapText` 文本改写钩子,默认恒等;`ChineseLanguage.mapText` 把 `v`→`'`,**无条件**:组词中进缓冲不清组词、空闲时按 v 直接上屏 `'`):
  - **L4 不再放分隔符键**(`ChineseLanguage.l4Extras = emptyList()`)——组词中 L4 会被候选行整行顶掉,靠 L4 按分节符在拼音态是死路,v 键全程可达;
  - 查询侧 `PinyinInputMethod.query` 剥掉非字母再查(`xi'an` 按 `xian` 查),分隔符只是显示/切分;
  - **代价**:ü 词(女/绿,词库 v 记 ü)不能按 v 直出,靠 n/l 前缀候选列出(`nv`/`lv` 条目仍被前缀命中);曾考虑"n/l 后 v 仍当 ü"守卫——否决:会造出"n/l 结尾后无法插分隔符"的死路(L4 键已移除);
  - 曾做的"候选行内嵌 `'` 键 + L3 `r`: ©→'"方案已整体回滚废弃(v 键取代)。
- **L4 固定核心键 = `；` `$` `\`**(编辑器删不掉,`l4Combined` 强制合并回最前):`;` `$` 是 L1/L2/L3 三层敲不出的单字符;`\` 是点名固定的(2026-09-28:"L4上需要固定显示\,因为前三层没有")。Tab/Esc/方向键 L3 全有,不重复占用中区;**不放组合按键**(`-> != ==` 这类多字符串键已按用户要求移除)。核心键同样过筛——出厂三层都按不出这三个;自改键位表后若某层能出,自动不显示。
- **L4 宽度**:不滚动、键一把不裁;等宽槽版式(间距恒 4dp、左右边距对称 6dp、槽宽 `W = min(填充值, 72dp 封顶)`,填充值→两端对齐、封顶→整组居中,中线恒过键心或中缝);分页按"前缀最长标签宽"判装得下,装不下的尾巴切给 L5。
- **签名重建**:UI 用 `ImeController.l4Signature()`(键位套id|语言id)判断是否重建 L4/L5 键帽,避免每次按键重建整排视图。
- **输入条背景纯黑**(2026-09-29 用户定案):`ImeTheme.DEFAULT.barBg` 与 `@color/status_bg`/`candidate_bg` 全改 `#000000`(其余 6 套主题色板不动,主题选择器仍有效)。数字态 Alt 芯片(`0-9` 那些)成了左区第一颗可见芯片,要把它的 `marginStart` 抹成 0,否则到屏幕左缘比 `拼`/`En` 多 2dp。
- **验证载体**:实验场 `activity_playground.xml` 追加了 `et_url`(textUri)与 `et_number`(number)两个上下文输入框——加在按钮行**下方**以不挪 `et_playground`/按钮坐标,aitest 的点按与判卷不受影响;五种输入框真机截图逐一核对通过。

## 依据

- 2026-09-28 需求三连:L4 可变(网址要 http:// 等)、输入数字时 alt 状态显示为变种 0-9、拼音输入时 L4 增加分节符。
- 2026-09-28 分节符方案改口(取代候选行内嵌键方案):"拼音输入时v键作为分隔符,拼音输入L4不再显示分隔符";"L4上需要固定显示\,因为前三层没有";"不要在网址输入模式时在L4显示/这个按键是L2的"。
- 2026-09-28 用户原话定案:"根据输入框的输入类型决定";"不需要任何软键盘 这是alt锁定的一个变种 仅仅限定物理键盘输入数字而已";"使用策略模式和注册机制 方便扩展语言和输入法";"这类由输入法注册的L4虚拟按键应该显示在最左侧"。其中"不需要程序里筛选 L1-L4都是固定的按键"**已于 2026-09-29 被推翻**(见上"必过 producible 筛")。
- 2026-09-29 整条重理 L4/L5 显示规则,用户原话:"L4不要显示L1-L3已有的按键";"白名单的内容也是L1-L3按不出的键或者短语组合";"数字态默认键位下 L4 为空是不对的 这是一个结果不是一个要求";"编译时过滤器不考虑了 这样失去了不同手机的适配能力";L5 内容选"溢出尾巴 + 用户短语";数字态 L4 出厂键"+86 等"。


## Timeline

- time: 2026-09-28T19:06:35
  kind: decision
  summary: "Created this page: 策略注册表与 L4 上下文键位（语言/输入法/输入框三轴）"
  source: "2026-09-28 用户需求+实现定案"
  affects: [ime-strategy-registry]

- time: 2026-09-28T19:06:50
  kind: decision
  summary: "语言/输入法走 ImeRegistry 策略注册(ImeState.languageId 取代 ImeMode);L4 由输入类型选键位套、语言补差键,不筛键、宽度自适应"
  source: brain update-truth
  affects: [ime-strategy-registry]

- time: 2026-09-28T19:07:24
  kind: decision
  summary: "笔误修正:互不侵合→互不侵入"
  source: brain update-truth
  affects: [ime-strategy-registry]

- time: 2026-09-28T19:39:47
  kind: decision
  summary: "顺序定案:语言补差键(输入法注册的键)排最左,其后才是输入类型选的键位套;真机三种输入框截图核对通过"
  source: brain update-truth
  affects: [ime-strategy-registry]

- time: 2026-09-28T20:58:48
  kind: decision
  summary: "拼音分节符改走 v 键:Language.mapText 钩子 v→'、L4 移除分隔符键(候选行内嵌键+L3 ©→' 方案回滚废弃);L4 固定核心键补 \\;网址套去 /(L2 键帽)"
  source: "2026-09-28 用户定案+真机验证"
  affects: [ime-strategy-registry]

- time: 2026-09-28T20:58:48
  kind: evidence
  summary: "89 条单测全绿;真机四项:组词中 v 进缓冲显 ni' 且候选不变、空闲 L4=； \\$ \\ 无 '、空闲 v 上屏 '、网址框 L4=http:// https:// .com"
  source: "adb 真机验证"
  affects: [ime-strategy-registry]

- time: 2026-09-29T01:30:38
  kind: decision
  summary: "L4 核心键按输入框取合法子集(同一把尺'合法的留、非法的去'):网址框去反斜杠留 ； 、邮件框只留 $ (RFC 5322 atext 合法);API=FieldProfile.l4CoreKeys: List<L4Key>,KeyLayout.L4_CORE_URL/L4_CORE_EMAIL 从 L4_CORE 派生"
  source: "用户决策 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T01:31:06
  kind: decision
  summary: "接上条(上条被 shell 截断):网址框核心键去反斜杠、留全角分号和美元符(RFC 3986 sub-delim 合法);邮件框只留美元符(RFC 5322 atext 合法);API=FieldProfile.l4CoreKeys: List<L4Key>,KeyLayout.L4_CORE_URL 与 L4_CORE_EMAIL 从 L4_CORE 派生"
  source: "用户决策 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T13:20:20
  kind: decision
  summary: "数字态 L4 改为**短语组合**键(2026-09-29 用户定案:+86 这类):各数字类型的合法单字符(- . / : + * #)在 L2 上全有键,按'L1-L3 已有的不进 L4'会被整段筛空,数字态 L4 就空了——而'L4 不能空'是要求不是结果,所以数字套改成多字符组合(物理键一次只出一个单字符 ⇒ 组合天然按不出)。出厂: signeddecimal=-.、phone=+86 +1 +81 +44、datetime=-/;number/signed/decimal 出厂为空(合法字符全在 L2,无组合可放,不是被筛空的结果),可在 [L4 number] 等段自写。物理白名单 = 0-9 + 该类型合法单字符(digitChars,L2 键帽直接敲)+ L4 当前显示的键输出(多字符组合一并放行)——单字符规则拦掉的组合键由 L4 那一支放行。"
  source: "用户 2026-09-29:白名单的内容也是L1-L3按不出的键或者短语组合 / 数字态默认键位下 L4 为空是不对的 这是一个结果不是一个要求 / 数字态 L4 出厂键:+86 等"
  affects: [ime-strategy-registry, ime-interaction-model]

- time: 2026-09-29T13:21:19
  kind: decision
  summary: "2026-09-29 整条重理 L4/L5 显示规则:合成→producible 运行时筛→版式;数字态 L4 改短语组合;L5 两类内容"
  source: "用户 2026-09-29 五条定案"
  affects: [ime-strategy-registry]

- time: 2026-09-29T13:34:03
  kind: decision
  summary: "键位表首次运行落盘(2026-09-29):KeyLayoutStore.load 发现 files/keymap.txt 不存在就把出厂键位表写一份,之后一律以文件为准。理由:设置页的键位编辑器(LayerMapActivity)已下线,[L4 xxx] 键位套与 [L5] 用户短语只剩这一个改法,文件不在手就没人改得了(用户短语会永远为空)。真机已验:装完首启即生成 keymap.txt,追加 [L5] 段后切输入框即生效(onStartInput 每次重新 load)。"
  source: "实现取舍 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T15:03:57
  kind: decision
  summary: "2026-09-29 数字套只有电话有组合键;L5 只装溢出尾巴(短语功能撤回);输入条纯黑 + 0-9 芯片左边距"
  source: "用户 2026-09-29 多条定案"
  affects: [ime-strategy-registry]

- time: 2026-09-29T22:51:27
  kind: decision
  summary: "状态行三区改版:左右两区**定死 [dimen/bar_side_box]=120dp**(不再用 0.4 权重),中区 L4 唯一带 weight=1;右区三键 48dp→40dp([dimen/btn_size])。根因(用户报障「邮箱输入模式 L4 和右侧按钮重叠」):0.4 权重在 Titan 2(598dp 宽)只分到 123dp,却要装 3×48dp=144dp 的按钮,LinearLayout 在 gravity=end 下把 childLeft 算成负数、三个键整体**左溢 21dp**;btn_l5 只在有溢出时显示,一显示就压住 L4 末键(实测键帽右缘 1087px、该键触摸框从 1052px 起,压 36px)。**调权重调小只会外溢更多,正解是让盒子装得下内容**。改后 L4=314dp(+6dp)、两键之间正好是 l4 的 marginEnd 6dp"
  source: "用户报障+真机像素测量 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T22:51:37
  kind: reversal
  summary: "L5 行版式**推翻**当天的「左对齐+自然宽」:改为**与 L4 中区共用同一套等宽槽居中版式**(删掉 L4PanelView.layoutPage 里 tailMode 的提前 return)。左右内边距改**相等**([dimen/l5_side_inset]),行中线因此正好落在屏幕中点= L4 中区中线;等宽槽 ⇒ **要么相邻两键边缘接在中点(偶数把)、要么正中一把键中心对在中点(奇数把)**。用户原话「L5 的布局要做到中央 要么是按钮的边缘对齐中点 要么是按钮的中心对齐中点」。附带好处:L5 尾巴也按填充值压缩,不会跑出右缘"
  source: "用户定案 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T22:51:37
  kind: decision
  summary: "L5 行改成**玻璃片**:行高 42dp→48dp、键帽由贴行底改上下居中(键 36dp 上下各 6dp,**到状态行键帽净距 12dp**;背景透明后 42dp 贴底那版两行糊在一起,用户报障「分割不太自然」);左右外边距 [dimen/l5_card_margin]=10dp 不贴屏幕边。背景 = 半透明填充(主题条色 alpha 0xD0)+ **只有上边缘一道渐变高光**(占面板高 28%,不画四周描边/下缘压暗——加多了像画了个框)。同时试了窗口级高斯模糊 Window.setBackgroundBlurRadius(32px, API31+),**是否真生效未能证实**:平台只给不透明窗口做背景模糊而输入法窗口必须透明,很可能被静默忽略;那次像素测量量到的是自己画的边缘、测不成。**所以半透明填充不能省,它是模糊失效时唯一的可读性兜底**"
  source: "用户定案+真机迭代 2026-09-29"
  affects: [ime-strategy-registry]

- time: 2026-09-29T23:22:53
  kind: reversal
  summary: "L5 背景**彻底放弃玻璃**(用户定案「只去掉高光,不做分隔线」),回归状态行同款实心色;三版玻璃全部作废并删掉:①完全透明+窗口级高斯模糊 ②圆角玻璃卡片(四边渐变高光+下缘压暗) ③通栏玻璃片+上边缘渐变高光。**教训:Liquid Glass 的高光必须跟着一个形状**(圆角卡片浮在内容上)才成立;L5 是一整条通栏带子、又是键盘的**一部分**,形状一去掉那道高光就成了没有光源对应的横线,怎么调都假。另外跨窗口背景模糊本来也拿不到(平台只给不透明窗口做,而 IME 窗口必须透明),实心之后更没有作用面。**保留的**:通栏无圆角无侧边距、行高 48dp、键帽上下居中(到状态行键帽净距 12dp)、与 L4 共用等宽槽居中版式;两排键帽靠 12dp 间距自己分开,不加高光也不加分隔线"
  source: "用户定案 2026-09-29(连试三版均判不自然)"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:21:22
  kind: decision
  summary: "硬规矩:L2/L3 固定键跟着**键盘丝印**,只换字形不挪位。硬件键盘各层字符就印在键上,用户按的是眼睛看到的那个 —— 拼音态只把那个符号换成全角形态(Alt+B 印 . 敲 。、Alt+P 印 : 敲 ：),不许换成别的字。第一版把《》【》、· 按'读着顺'摊到 16+15 个符号位,L2 十六把里十二把错位、L3 十五把全错位,用户连报两次「彻底乱套了」「L3 英文状态下是 \\ 拼音全角状态不就是、吗」。API=Language.layerMap(layer, fullWidth): Map<Int,KeyOut>?"
  source: "2026-09-29 用户两次报障后定案"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:22:29
  kind: decision
  summary: "ASCII 根本没有对应键的中文标点 — 《》【】、引号、省略号、破折号、顿号 — 在 L2/L3 三层都塞不下,改放 L4。L4 的定义本就是「L1-L3 敲不出的键」,状态行一行装不下的尾巴有 L5 接着,横滑翻页、不截断。同时 L4 核心键改由语言换:新增 Language.l4Replace,类型 Map<String, L4Key>,键是核心键的标签,值为本语言实际显示的那一把。拼音态把核心分号换成全角、英文态换半角,美元符换成 ￥。换的是整把键,标签与输出一起换,且必须在去重之前换 —— 核心键会同时出现在 FieldProfile.l4CoreKeys 与 KeyLayout.l4Combined 里,换晚了同一把会以两个身份各留一把,屏上并排两把同标签的键。"
  source: "2026-09-29 丝印规则的直接后果"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:23:00
  kind: decision
  summary: "数字类 digitsOnly 必须绕过语言层表。那一档的合法字符集是照着出厂 ASCII L2 的槽位定的 —— 10 个数字位,外加减号、点、加号、星号、井号、斜杠、冒号各占一键 —— 状态行 Alt 芯片上标的 0-9 加斜杠冒号也是照它标的。中文表若套上去,日期框输不出斜杠、电话框输不出加号,芯片跟着说谎。数字框本来就没有「语言」可言,走键位表出厂值。"
  source: "2026-09-29 实现时发现的硬约束"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:23:32
  kind: decision
  summary: "拼音态新增状态行「全/半」开关按钮,放在「拼」右侧。硬件键盘上没有这个物理键,状态行是唯一入口 —— 左区其余芯片一律不可触摸、切换走物理键,这颗是例外。40dp 见方,写的是设置页同一个偏好 PREF_FULLWIDTH,一处改两处都变,不存在两个真相。只在拼音态显示:英文态没有全角概念、数字类绕过语言表,两种情况下它都是按了没反应的死开关。宽度预算:拼音态 showShiftChip 为 false,大写芯片恒隐,它顶的是那颗的位置,合计 116dp,装得进 bar_side_box 的 120dp,不必动 bar_side_box —— 动它等于同时改左右两区,会挪动 L4 的中线。文字标的是当前状态,不是按了会变成什么。"
  source: "2026-09-29 用户定案"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:23:40
  kind: evidence
  summary: "134 条单测全绿。真机逐键核对:依次 Alt 加 左括号 右括号 冒号 点 逗号 问号 叹号 减号,打出全角 左括号 右括号 冒号 句号 逗号 问号 叹号 全角减号,与丝印逐位对应。「全/半」按钮点一下芯片变「半」、Alt 加 T B P 打出半角左括号 点 冒号,再点回来变「全」。L4 行显示 书名号对 书名号对 方头括号对 引号 左引号 省略号 破折号,装不下的键自动进 L5,键盘图标随之出现。开关翻动时 L4 键序不变,证明那把筛与物理键用的是同一份表。顺带修掉一个真 bug:核心键键帽画全角分号却输出半角,两把同字不同码的键看着重复其实不是一把。"
  source: "Titan 2 真机验证"
  affects: [ime-strategy-registry]

- time: 2026-09-30T01:41:01
  kind: reversal
  summary: "推翻同日早些时候那条「L2/L3 跟着丝印、只换字形不挪位」:那是**把全角理解窄了** —— 全角 = 中文标点,不是半角 ASCII 加宽。照丝印做,全角态 L2/L3 只敲得出 括号 冒号 句号 逗号 问号 叹号 七个,书名号、方头括号、左右引号、省略号、破折号、顿号这些 ASCII 压根没有对应字符的,全被挤到状态行 L4 —— 实测 11 把(8 把挤 L4、3 把进 L5),用户报障「拼音模式 L4 为什么这么多固定键,很多都是 L2-L3 切换到全角能输出的」。**丝印冲突由那颗「全/半」开关解决,不必靠牺牲 L4 干净**:半角态 = 键位表出厂值 = 键盘上印着的那个字符(按眼所见),全角态 = 中文标点按三区分组。现在 31 个中文标点全在物理键上,普通文本框 L4 只剩 1 把(换过的那把货币键)。教训:硬件键盘上「哪把键」和「敲出什么」是两件事,前者受丝印约束、后者受语言约束,把两者混为一谈就会既错位又丢键。"
  source: "2026-09-29 用户报障后推翻同日早先那条"
  affects: [ime-strategy-registry]

- time: 2026-09-30T02:12:34
  kind: reversal
  summary: "定案(推翻本日更早的两版):全角态的 L2 与 L3 一律只是把丝印上那个字符换成它的全角形态,一位不挪。半角态等于键位表出厂值,也就是键盘上印着的那个字符。两态由状态行「拼」右侧那颗「全/半」开关切,同一个偏好。走过的三版与各自的教训:一版按读着顺摊开中文标点,十六把里十二把错位,报障「彻底乱套了」;一版全角态铺三十一个中文标点,丝印对不上,报障「L2 的 P 键不是冒号吗」,而且状态行被塞十一把,报障「为什么这么多固定键」;第三版才定案。教训:哪把键受丝印约束、敲出什么受语言约束,混为一谈就既错位又丢键。"
  source: "2026-09-29 用户五轮报障后的最终定案"
  affects: [ime-strategy-registry]

- time: 2026-09-30T02:12:45
  kind: decision
  summary: "配套四条。一,L3 整套布局原本是照搬可可输入法的,连版权符号都带过来;那既不是 Titan 键帽上的东西、中文也几乎不用,用户定案把 R 位换成币种符号。二,币种位两态都是美元符 —— 币种不随标点要不要全角变;全角形态的圆在状态行那把核心键上(换成它),因为物理键给的是美元符、L1-L3 两态都敲不出圆,状态行是它唯一入口。三,书名号方头括号左右引号省略号破折号顿号这些 ASCII 压根没有对应字符、变不出来,归状态行 L4 与 L5(用户原话「其他 L1-L3 按不出的符号可以放到 L4/L5 了」)。四,「L1-L3 敲不出就不上 L4」那把筛必须逐层取当前语言加当前开关下真正敲得出的,不能拿键位表出厂视图直接筛 —— 否则全角态会误判美元符敲得出,把全角态唯一的美元符入口筛掉。"
  source: "2026-09-29 用户定案"
  affects: [ime-strategy-registry]

- time: 2026-09-30T02:12:54
  kind: evidence
  summary: "138 条单测全绿。真机逐键核对:依次 Alt 加 冒号位 点位 左括号位 问号位 逗号位 叹号位,打出全角 冒号 句号 左括号 问号 逗号 叹号,与丝印逐位对应。状态行 L4 显示 书名号对 方头括号对 左双引号 左单引号 省略号 破折号,装不下的 顿号 分号 圆 三把在 L5,点键盘图标展开可见。全半开关点一下芯片变「半」、Alt 加 TBP 打出半角,再点回来变「全」。"
  source: "Titan 2 真机验证"
  affects: [ime-strategy-registry]

- time: 2026-09-30T02:24:44
  kind: decision
  summary: "L3 的成对符号换中文那一个,不是 Unicode 全角区块(用户原话「中文状态 <> 对应的应该是《》,[] 对应【】同理」):小于号大于号 → 书名号对,方括号对 → 方头括号对,方括号对 → tortoise 壳括号对,花括号对保留机械全角(中文没有对应的定名括号,要 tortoise 壳也行,那是更外层的一对、习惯上配直角引号)。其余波浪号、脱字符、百分号、反引号、反斜杠、与号、竖线、等号按机械全角加 FEE0。连带效果:书名号对方头括号对回到物理键上,状态行就不必再摆它们。"
  source: "2026-09-29 用户定案"
  affects: [ime-strategy-registry]

- time: 2026-09-30T02:34:10
  kind: decision
  summary: "L4 与 L5 的内容必须跟中文全角开关走 —— 半角态的状态行上摆一把全角符号是自相矛盾(用户原话「半角的时候不应该有全角符号显示在 L4-L5 上」)。全角态装省略号、破折号、顿号,加冷门的直角双直角引号、 tortoise 壳括号、注释符;半角态装同一批的半角写法:省略号是三个点、破折号是两个连字符,其余(顿号、CJK 括号)半角没有对应写法就不摆。核心分号也跟着走:全角态全角、半角态半角,两态都在状态行(丝印上本来就没有分号)。API 顺势收敛:原先三个钩子(换核心键、退场核心键、追加键)合成 Language.coreKeys(传入出厂核心键、返回本语言该显示的)+ Language.l4Extras(fullWidth),两个都吃开关 —— 键帽画什么、敲出什么、还是不上屏是同一个决定,拆成三处参数迟早写出「换了标签没换输出」那种自相矛盾(真出过:键帽画全角分号却输出半角)。"
  source: "2026-09-29 用户定案"
  affects: [ime-strategy-registry]
