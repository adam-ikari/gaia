---
id: tt2-hardware-constraints
title: "Titan 2 物理键盘硬约束"
category: project
status: active
tags: [titan2, hardware, gesture]
created: "2026-09-23T04:27:03"
updated: "2026-10-01T08:03:33"
---

<!-- compiled_truth -->
Titan 2（Android 15）的物理键盘既是输入设备也是一块电容触摸面，但**输入法拿不到它的触摸轨迹**，因此一切依赖"键盘滑动"的交互都必须换成系统事件或屏幕手势。

## 键盘事实

- 4 行 QWERTY，**首行 6 个特殊键**：Shift / Sym / Back / Recent / Fn / Alt（Back、Recent 是物理导航键，会吃掉 `KEYCODE_BACK`、`KEYCODE_APP_SWITCH`）。
- **没有独立数字行**，字母键印有上档符号，官方说明 Alt = "Hold to type the upper symbols on letter keys" → 符号层必须由 Alt 层给出。
- 系统自带行为：双击 Shift = Caps Lock、长按字母 = 单字大写、双击空格 = 插入句号+空格、长按 Sym = 唤起 Google 语音。
- 系统快捷键设置里：Sym 可被指派为 Sym / Ctrl / Tab / Alt / Home…；Fn 默认短按回 Home、长按唤起助手，**官方建议设成 Ctrl**。

## 关键限制（决定了本项目的可行设计）

1. **输入法收不到键盘表面的触摸/轨迹事件。** 可可作者 3.8.3.8 的结论：老 Titan 上"关闭显示虚拟键盘后 `onGenericMotionEvent` 能收到物理键盘触摸事件"，**TT2 上这条路彻底失效**，尝试多种方式无解；只有厂商固件/公开实现逻辑才能解决。→ 本项目**不做**"键盘飞字"，改用屏幕候选栏手势 + 软键盘区域手势实现上滑选字。
2. **候选翻页的物理通道只有方向键事件。** 需开启系统"翻屏助手（Scroll Assistant）"，双击键盘进入编辑模式后左右滑动，系统才把滑动转成 `DPAD_LEFT/RIGHT` 送给输入法。→ `DPAD_LEFT/RIGHT` 必须映射为候选翻页。
3. **Fn 键必须在系统里设成 Ctrl 才能被输入法捕获并参与选字**（可可设成 Tab 也管用，其余取值会被系统拦截或与其他键重复）。→ 默认"五键选字"用 Ctrl+字母实现。
4. **Sym 键的 keycode 未知**：官方说按 Sym 弹出特殊符号列表"requires IME support"，Reddit 反馈默认在 Android 里无任何作用。→ 必须用 app 内**按键探测页**在真机上抓真实 keycode 后再定映射，映射做成可配置。
5. Android 15 输入法窗口接口变化大（可可在 TT2 上默认开启"编辑框兼容模式"并要求用户不要关闭）。→ 我们的候选窗口也要留兼容开关。

## 时间线

- time: 2026-09-23T04:30:00
  kind: decision
  summary: 确认 TT2 输入法拿不到键盘轨迹事件，飞字改为屏幕候选栏/软键盘手势实现
  source: 可可拼音输入法 3.8.3.8 适配记录、可可使用帮助"Titan2无法飞字"章节
  affects: [ime-interaction-model]
- time: 2026-09-23T04:31:00
  kind: decision
  summary: 翻页物理通道确定为 DPAD_LEFT/RIGHT（翻屏助手双击编辑模式），Fn 需系统设为 Ctrl
  source: 可可 3.8.3.8 版本说明 + Unihertz Titan 2 键盘指南
  affects: [ime-interaction-model]


## Timeline

- time: 2026-09-23T04:27:03
  kind: decision
  summary: "Created this page: Titan 2 物理键盘硬约束"
  source: "Unihertz 官方文档 + 可可拼音输入法作者 3.8.3.8 适配记录 + Reddit 反馈"
  affects: [tt2-hardware-constraints]

- time: 2026-09-23T04:29:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [tt2-hardware-constraints]

- time: 2026-10-01T08:03:33
  kind: note
  summary: "模拟器可以精确还原 Titan 2 的屏幕网格:AVD 手写 config.ini(hw.lcd.width/height=1440、density=320 ⇒ 576dp 宽),从开机起就是这个网格。注意 AOSP Launcher3 在正方形屏上必崩(calculateHotseatBorderSpace 除以零),要 disable 掉;也不能事后 wm size 改(改完系统瘫、Activity 起不来)"
  source: "2026-10-01 CI 实测"
  affects: [tt2-hardware-constraints]
