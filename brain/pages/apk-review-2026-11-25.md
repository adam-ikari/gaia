---
id: apk-review-2026-11-25
title: "APK 源码静态评审 2026-11-25:P1 六条集中在日志泄露与生命周期"
category: reference
status: active
tags: [apk, review, security]
created: "2026-11-25T16:38:25"
updated: "2026-11-26T12:01:25"
---

<!-- compiled_truth -->
评审 27 条已于 2026-11-25 全部修复，提交 `80d6754`。`./gradlew testDebugUnitTest` 415 条通过，`processReleaseManifest` 通过；未跑真机与 E2E。

关键约束：敏感日志按 `BuildConfig.DEBUG` 分流，debug 原格式不动，E2E 判据（`env-reset.sh` / `harness.mjs` / `scenarios.mjs`）保持可用；`PlaygroundActivity` 主 manifest 为 `exported=false`，`app/src/debug/AndroidManifest.xml` 覆写为 `true`，因为 E2E 用 `am start` 在非 root 真机拉起它。librime 部署留后台、`createSession` 移到 IME 主线程；语音未就绪直接 `FAILED` 且不设 `pendingStart`；剪贴板用 `knownSensitive` 区分平台是否给出敏感标记。

修复记录写在 `docs/apk-review-2026-11-25.md`末尾。原报告的行号对应修复前 HEAD `d15cd73`，引用时不要混用。


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

- time: 2026-11-25T18:19:14
  kind: decision
  summary: "27 条已全部修复,commit 80d6754"
  source: "2026-11-25 用户「开始修复」+ ./gradlew testDebugUnitTest 415 通过"
  affects: [apk-review-2026-11-25]

- time: 2026-11-25T18:19:14
  kind: evidence
  summary: "27 条全部修复:commit 80d6754,415 条单测通过,debug/release manifest 合并结果核对"
  source: "gradle test、git commit、manifest 合并产物"
  affects: [apk-review-2026-11-25]

- time: 2026-11-26T12:01:25
  kind: evidence
  summary: "修复版 beta 已在 Titan 2 真机安装:sha256 与本地一致,versionName 0.7.12-beta.ffd3769,设置页正常渲染,logcat 无 FATAL;完整 E2E 未跑(beta 的 Playground 不导出、远端设备焦点被外部切换)"
  source: "2026-11-26 a2mcp 真机验证"
  affects: [apk-review-2026-11-25]
