---
slug: stack
title: Tech stack
role: tech-stack choices
updated: "2026-09-23T04:27:03"
---

# Tech stack

## 领域与候选

| 层 | 候选 | 决定 | 理由 |
|---|---|---|---|
| 语言 | Kotlin / Java / Flutter+IME 插件 | **Kotlin 1.9.24** | 原生 `InputMethodService` 只有原生路线最可控；Flutter/Compose 在 IME 窗口上风险高 |
| 构建 | AGP 8.5.2 + Gradle 8.7 | **采用** | 本机 `~/.gradle` 已缓存该组合与 androidx 依赖，离线可构建 |
| SDK | compileSdk 34 / targetSdk 34 / minSdk 26 | **采用** | SDK 平台已装 31/34/35/36；Titan 2 是 Android 15，向下兼容 8.0 起 |
| UI | Compose / 自绘 View / XML | **自绘 View（无 Compose）** | 候选栏手势、键盘分区分层需要像素级控制；IME 进程要轻 |
| 拼音引擎 | 移植老版 LatinIME / RIME / 自研轻量引擎 | **自研轻量引擎** | 可可的经验是老 LatinIME 坑多；本项目只需前缀匹配 + 分词组词，自研可控 |
| 词库格式 | LatinIME 二进制 / RIME dict / 纯文本 TSV | **assets 里的 gzip TSV**（`拼音\t词\t词频`，按拼音排序） | 二分前缀查询，零依赖、可 diff、可被外部工具生成 |
| 用户词频 | SQLite / JSON 文件 | **JSON 文件**（`filesDir/user_dict.json`，停止输入时落盘） | 量级几千条，避免迁移/锁的复杂度 |
| 设置 | PreferenceFragment / 自写 Activity | **androidx.preference** | 缓存里已有 1.2.1，够用 |

## 决策

- 不引入网络权限：候选、词库、设置全部本地，输入法权限面最小。
- 单模块 `app`，内部按 `keymap / engine / ime / ui / settings / probe` 分包，物理键管线与拼音引擎不依赖 Android UI，便于 JVM 单测。

## 待定项

- Titan 2 的 `Sym` / `Fn` 真实 keycode 未知（官方文档称 Sym "requires IME support"，Reddit 称默认无 Android 行为）→ 靠 app 内**按键探测页**在真机上抓取后再定映射。
- 词库规模与授权来源，见 [[pinyin-dict-strategy]]。
