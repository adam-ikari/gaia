---
id: ime-strategy-registry
title: "策略注册表与 L4 上下文键位（语言/输入法/输入框三轴）"
category: decision
status: active
tags: [策略, 注册表, L4, 扩展性]
created: "2026-09-28T19:06:35"
updated: "2026-09-29T15:03:57"
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
