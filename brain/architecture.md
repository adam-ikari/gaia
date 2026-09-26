---
slug: architecture
title: System architecture
role: system architecture
updated: "2026-09-23T04:29:42"
---

# System architecture

单模块 Android 工程 `app`，**物理键管线与拼音引擎不依赖 Android UI**，可跑 JVM 单测；UI 只有两个消费者：候选栏/软键盘（IME 窗口内）与设置/探测页（普通 Activity）。

```mermaid
graph TD
    subgraph OS[Android 系统 / 应用]
        APP[目标应用 editor]
        SYS[InputMethodManagerService<br/>翻屏助手方向键 / Fn=Ctrl]
    end

    subgraph IME[hwkbd_ime 输入法进程]
        SVC[ImeService<br/>InputMethodService<br/>onKeyDown / onKeyUp / onUpdateSelection]
        CTRL[ImeController 状态机<br/>模式 / 三层 / 锁定 / 组词 / 候选分页]
        KEYMAP[KeyLayout 三层键位表<br/>L1 字母 · L2 符号数字 · L3 编程<br/>JSON 可自定义]
        ENGINE[PinyinEngine<br/>前缀匹配 + 分词组词 + 用户词频]
        DICT[(assets dict.txt.gz<br/>gzip TSV 词库)]
        USERD[(filesDir user_dict.json)]
        CBAR[CandidateBarView<br/>点选 · 上滑选字 · 左右滑翻页]
        SOFT[SoftKeyboardView<br/>5 区上滑选字 · 三层层切换]
        IC[InputConnection<br/>setComposingText / commitText / sendKeyEvent]
    end

    APP -- 硬件按键 --> SYS
    SYS -- 未被应用消费的 KeyEvent --> SVC
    SVC -- handleKey --> CTRL
    CTRL -- 查层映射 --> KEYMAP
    CTRL -- 组词查询 --> ENGINE
    DICT --> ENGINE
    USERD --> ENGINE
    ENGINE --> CTRL
    CTRL -- 更新候选/组词 --> CBAR
    CTRL -- 更新键盘层 --> SOFT
    CTRL -- 上屏/发送按键 --> IC
    IC --> APP
    CBAR -- 手势回调 --> CTRL
    SOFT -- 手势回调 --> CTRL
```

## 分包职责

| 包 | 职责 | 关键类型 |
|---|---|---|
| `keymap` | 三层键位表、锁定/双击判定、键→动作 | `KeyLayout`、`KeyAction`、`TapTracker` |
| `engine` | 拼音解码、词库加载、用户词频 | `PinyinEngine`、`PinyinDict`、`UserDict` |
| `ime` | 纯逻辑状态机（无 Android UI 依赖） | `ImeController`、`ImeState`、`Selection` |
| `service` | Android 接线：KeyEvent、InputConnection、窗口 | `ImeService` |
| `ui` | 候选栏、软键盘、状态条（自绘 View） | `CandidateBarView`、`SoftKeyboardView` |
| `settings` | 偏好、层映射编辑、词库导入导出 | `SettingsActivity` |
| `probe` | 真机按键探测（抓 Sym/Fn 真实 keycode） | `KeyProbeActivity` |

## 关键设计约束

- `ImeController` 只通过回调（`commit`、`sendKey`、`render`）与 Android 层通信 → 单测覆盖三层切换、锁定、分页不需要设备。
- 按键**只在有组词或命中本输入法映射时才被消费**；空闲态原样放行，保证 Termux/vim 拿到原始按键。
- 词库一次性载入内存，输入路径上不做 IO。
