---
id: ime-strategy-registry
title: "策略注册表与 L4 上下文键位（语言/输入法/输入框三轴）"
category: decision
status: active
tags: [策略, 注册表, L4, 扩展性]
created: "2026-09-28T19:06:35"
updated: "2026-09-29T01:31:06"
---

<!-- compiled_truth -->
# 策略注册表与 L4 上下文键位

## 结论

- **语言与输入法是策略**:`Language`(id/chipLabel/methodId/showShiftChip/shiftTapsEnabled/l4Extras/mapText/onEnter/composes/commitText)与 `InputMethod`(query/onCommitted/removeUserWord/userCount)由 `ImeRegistry` 注册;`ImeState.languageId: String` 取代 `ImeMode.CN/EN` 枚举。加语言/加输入法只注册不改核心(状态机 `ImeController`、UI `ImeService` 都按 id 查策略),`ImeRegistryTest` 即回归证明。
- **状态行 L4 中区 = 语言补差键(排最左) + 输入类型选的键位套**(两个维度各自注册,互不侵入):
  - **顺序定案**:语言补差键(输入法注册的键)**排最左**——它语义上属于"这个输入法随时要用",可见性优先;其后才是键位套(用户 2026-09-28 指定,真机已验)。当前拼音**不注册** L4 补差键(分节符改走 v 键,见下),机制留给未来的语言;
  - `FieldKind.from(EditorInfo.inputType)` → TEXT / URL(`TYPE_TEXT_VARIATION_URI`) / NUMBER(数字类,含电话);
  - URL → 键位编辑器 `[L4 url]` 段,出厂预置 `http:// https:// .com`(**`/` 已去掉**:L2 键帽 Alt+o 已能出,L4 不重复,2026-09-28 用户定案);文件缺段回落出厂预置(老 keymap.txt 也拿得到);
  - NUMBER → **不换 L4,Alt 芯片文字变 0-9**(alt 锁定的数字变种:只改这颗芯片的显示,不改层映射、不碰软键盘);
- **拼音分节符走 v 键**(`Language.mapText` 文本改写钩子,默认恒等;`ChineseLanguage.mapText` 把 `v`→`'`,**无条件**:组词中进缓冲不清组词、空闲时按 v 直接上屏 `'`):
  - **L4 不再放分隔符键**(`ChineseLanguage.l4Extras = emptyList()`)——组词中 L4 会被候选行整行顶掉,靠 L4 按分节符在拼音态是死路,v 键全程可达;
  - 查询侧 `PinyinInputMethod.query` 剥掉非字母再查(`xi'an` 按 `xian` 查),分隔符只是显示/切分;
  - **代价**:ü 词(女/绿,词库 v 记 ü)不能按 v 直出,靠 n/l 前缀候选列出(`nv`/`lv` 条目仍被前缀命中);曾考虑"n/l 后 v 仍当 ü"守卫——否决:会造出"n/l 结尾后无法插分隔符"的死路(L4 键已移除);
  - 曾做的"候选行内嵌 `'` 键 + L3 `r`: ©→'"方案已整体回滚废弃(v 键取代)。
- **L4 固定核心键 = `；` `$` `\`**(编辑器删不掉,`l4Combined` 强制合并回最前):`;` `$` 是 L1/L2/L3 三层敲不出的单字符;`\` 是点名固定的(2026-09-28:"L4上需要固定显示\,因为前三层没有")。Tab/Esc/方向键 L3 全有,不重复占用中区;**不放组合按键**(`-> != ==` 这类多字符串键已按用户要求移除)。
- **不做程序筛选**:曾经的 "L1-L3 敲得出就不显示"(`producible`)已删除——L1-L4 都是固定按键,配置什么显示什么。
- **L4 宽度**:不滚动、键一把不裁;超宽依次 ①压掉 36dp 键宽下限按文字自然宽重排 ②等比压缩到中区(可能截字)。中区实测约 264dp、键宽下限 36dp → 约 6 键上限。
- **签名重建**:UI 用 `ImeController.l4Signature()`(键位套id|语言id)判断是否重建 L4 键帽,避免每次按键重建整排视图。
- **验证载体**:实验场 `activity_playground.xml` 追加了 `et_url`(textUri)与 `et_number`(number)两个上下文输入框——加在按钮行**下方**以不挪 `et_playground`/按钮坐标,aitest 的点按与判卷不受影响;三种输入框真机截图逐一核对通过。

## 依据

- 2026-09-28 需求三连:L4 可变(网址要 http:// 等)、输入数字时 alt 状态显示为变种 0-9、拼音输入时 L4 增加分节符。
- 2026-09-28 分节符方案改口(取代候选行内嵌键方案):"拼音输入时v键作为分隔符,拼音输入L4不再显示分隔符";"L4上需要固定显示\,因为前三层没有";"不要在网址输入模式时在L4显示/这个按键是L2的"。
- 用户原话定案:"根据输入框的输入类型决定";"不需要任何软键盘 这是alt锁定的一个变种 仅仅限定物理键盘输入数字而已";"不需要程序里筛选 L1-L4都是固定的按键";"使用策略模式和注册机制 方便扩展语言和输入法";"这类由输入法注册的L4虚拟按键应该显示在最左侧"。


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
