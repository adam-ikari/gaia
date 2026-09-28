---
id: ime-strategy-registry
title: "策略注册表与 L4 上下文键位（语言/输入法/输入框三轴）"
category: decision
status: active
tags: [策略, 注册表, L4, 扩展性]
created: "2026-09-28T19:06:35"
updated: "2026-09-28T19:39:47"
---

<!-- compiled_truth -->
# 策略注册表与 L4 上下文键位

## 结论

- **语言与输入法是策略**:`Language`(id/chipLabel/methodId/showShiftChip/shiftTapsEnabled/l4Extras/onEnter/composes/commitText)与 `InputMethod`(query/onCommitted/removeUserWord/userCount)由 `ImeRegistry` 注册;`ImeState.languageId: String` 取代 `ImeMode.CN/EN` 枚举。加语言/加输入法只注册不改核心(状态机 `ImeController`、UI `ImeService` 都按 id 查策略),`ImeRegistryTest` 即回归证明。
- **状态行 L4 中区 = 语言补差键(排最左) + 输入类型选的键位套**(两个维度各自注册,互不侵入):
  - **顺序定案**:语言补差键(输入法注册的键,如拼音 `'`)**排最左**——它语义上属于"这个输入法随时要用",可见性优先;其后才是键位套(用户 2026-09-28 指定,真机已验);
  - `FieldKind.from(EditorInfo.inputType)` → TEXT / URL(`TYPE_TEXT_VARIATION_URI`) / NUMBER(数字类,含电话);
  - URL → 键位编辑器 `[L4 url]` 段,出厂预置 `http:// https:// .com /`;文件缺段回落出厂预置(老 keymap.txt 也拿得到);
  - NUMBER → **不换 L4,Alt 芯片文字变 0-9**(alt 锁定的数字变种:只改这颗芯片的显示,不改层映射、不碰软键盘);
  - 拼音语言补 `'` 分节符:组词中插入进缓冲且不清组词,`PinyinInputMethod.query` 剥掉非字母再查(`xi'an` 按 `xian` 查);空闲时按 `'` 就是普通上屏。
- **不做程序筛选**:曾经的 "L1-L3 敲得出就不显示"(`producible`)已删除——L1-L4 都是固定按键,配置什么显示什么。
- **L4 宽度**:不滚动、键一把不裁;超宽依次 ①压掉 36dp 键宽下限按文字自然宽重排 ②等比压缩到中区(可能截字)。中区实测约 264dp、键宽下限 36dp → 约 6 键上限,网址套只放 4 把。
- **签名重建**:UI 用 `ImeController.l4Signature()`(键位套id|语言id)判断是否重建 L4 键帽,避免每次按键重建整排视图。
- **验证载体**:实验场 `activity_playground.xml` 追加了 `et_url`(textUri)与 `et_number`(number)两个上下文输入框——加在按钮行**下方**以不挪 `et_playground`/按钮坐标,aitest 的点按与判卷不受影响;三种输入框真机截图逐一核对通过。

## 依据

- 2026-09-28 需求三连:L4 可变(网址要 http:// 等)、输入数字时 alt 状态显示为变种 0-9、拼音输入时 L4 增加分节符。
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
