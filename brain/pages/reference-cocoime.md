---
id: reference-cocoime
title: "可可拼音输入法功能清单（模仿对象）"
category: reference
status: active
tags: [ime, reference, cocoime]
created: "2026-09-23T04:27:03"
updated: "2026-09-23T04:29:42"
---

<!-- compiled_truth -->
「可可拼音输入法」（COCOZQ，`com.cocozq.inputmethod.pinyin`）是本项目的模仿对象：基于早期 Android `LatinIME`，服务黑莓 Priv/KEYone/KEY2、Unihertz Titan 系列、Titan 2、Minimal Phone 等全键盘机型。

## 交互与功能清单（按可可使用帮助整理）

- 输入方式五态：`中`（拼音）/ `Ab`（字母，双击 Shift 锁大写）/ `符`（Alt 单次、双击锁定，中文全角、英文半角）/ `sym`（扩展键盘，Sym 双击锁定，默认 wasd=方向键）/ `v` 模式（`vHello`+空格直接上屏字母）。
- **候选只有 5 位**；黑莓用最下排 5 键选字，Titan Pocket 用首行左右各 2 键 + 空格；KEYone/KEY2/Priv/Titan/Titan Pocket 支持**键盘上滑飞字**。
- **翻页**：候选框左右箭头、候选框左右滑动（3.1.0.3+）、黑莓 `$`/`Alt+$`、Titan 音量键、物理键盘左右滑。
- 快捷键：`Shift` 双击锁大写、`Shift+空格` 中英切换、`Shift+Sym` 开关滑动移动光标、`Shift+Del` = ESC（vim）、`Shift+Enter` 快捷发送（无障碍）、`Sym/Fn + c v x a z y` = 复制/粘贴/剪切/全选/撤销/重做。
- 扩展键盘（Sym 层）每键可自定义：单字符、≤30 字短语、或功能键（如 `e` → ESC），支持导入导出；这正是本项目 L3 编程层的原型。
- 词库：29W 自制词库（3.1.0.0+ 内置），用户词频记忆，长按候选可删除记忆词。
- 其他：双拼（智能ABC/可可/国标/微软/紫光）、opencc4j 繁体、打字音效、主题自定义、计算器、扫码快捷、屏幕大小切换。

## Titan 2 适配要点（3.8.3.8 起）

- 修 TT2 的 **Fn 键选字**：必须把 Fn 设为 Ctrl；只有 Tab 和 Ctrl 两种取值可用。
- 修 TT2 工具条/候选框**无法点击**的问题；默认开启"编辑框兼容模式"并要求不要关闭。
- **无法飞字**：拿不到物理键盘轨迹事件；翻页只能靠翻屏助手双击键盘后滑动产生的方向键事件。
- 结论：可可也没能在 TT2 上实现键盘滑动选字——本项目走屏幕手势路线是正确取舍。

## 对本项目的直接启示

老版 LatinIME 的坑（作者自述崩溃多、调试久）说明**不移植、自研轻量引擎**更划算；"每次动作两条通道"的健壮性来自其多年真机反馈的积累，应直接吸收进 [[ime-interaction-model]]。


## Timeline

- time: 2026-09-23T04:27:03
  kind: decision
  summary: "Created this page: 可可拼音输入法功能清单（模仿对象）"
  source: "cocozq.com 使用帮助 / Google Play 说明 / 版本记录"
  affects: [reference-cocoime]

- time: 2026-09-23T04:29:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [reference-cocoime]
