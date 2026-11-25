---
id: apk-review-2026-11-25
title: "APK 源码静态评审 2026-11-25:P1 六条集中在日志泄露与生命周期"
category: reference
status: active
tags: [apk, review, security]
created: "2026-11-25T16:38:25"
updated: "2026-11-25T16:38:25"
---

<!-- compiled_truth -->
范围：`app/src/main/java` 自研 Kotlin 55 文件 / 14,292 行，排除 vendored librime、资源、测试、构建脚本。方法：五模块并行静态评审，主评审逐条核验行号与调用链；未跑真机。完整报告在 `docs/apk-review-2026-11-25.md`，报告页 slug `apk-review-2026-11-25`。

P1 六条：
- 长按单槽 `lp` 被第二键覆盖 → 吞键 / 语音不停止（`ime/ImeController.kt:329,386-404,456-461`）。
- 输入 raw、候选词、preview 明文写 logcat，与「输入内容从不采集」口径冲突（`ImeController.kt:822-829` 等、`CandidateBarView.kt:270-278`、`RimeEngine.kt:231-235`）。
- 输入上下文前后各 200 字写进日志并显示在实验场（`service/ImeService.kt:1511-1517`）。
- `RimeEngine.close()` 无调用，服务重建时 native session 泄漏（`rime/RimeEngine.kt:342-348`）。
- 键位文件非法段名让 `Layer.valueOf` 逃逸，`parseText` 崩溃（`keymap/KeyLayout.kt:488,528`）。
- `RenderPump.run()` 一次异常后永久停摆（`ui/RenderPump.kt:40-55`）。

P2 十一条、P3 九条见报告。行号对应 master HEAD `d15cd73`。证据等级：静态推导，非真机复现。


## Timeline

- time: 2026-11-25T16:38:25
  kind: decision
  summary: "Created this page: APK 源码静态评审 2026-11-25:P1 六条集中在日志泄露与生命周期"
  source: "2026-11-25 用户「评审apk代码」,全量自研 Kotlin 静态评审"
  affects: [apk-review-2026-11-25]

- time: 2026-11-25T16:38:25
  kind: decision
  summary: "评审范围、方法、P1 清单与产物位置"
  source: "docs/apk-review-2026-11-25.md, report page apk-review-2026-11-25"
  affects: [apk-review-2026-11-25]
