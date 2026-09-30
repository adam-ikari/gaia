---
id: release-flow
title: "发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
category: project
status: active
tags: [release, ci, tooling]
created: "2026-09-30T09:50:05"
updated: "2026-09-30T09:50:22"
---

<!-- compiled_truth -->
发布 = 改版本号 → 打 `v*` tag → 推 tag,CI 负责出包和 release 页面。

**版本号唯一来源**:`app/build.gradle.kts` 的 `versionCode` / `versionName`,随功能提交一起改。
CI(yml 硬编码 `app-release.apk` / `app-debug.apk`)不读 tag 里的版本号,所以 tag 与 build.gradle 必须人工对齐。

**签名现状**:仓库 Secrets 里**没有**配 `SIGNING_KEYSTORE_BASE64` 等(2026-09-30 核对),
所以 CI 走的是仓库内提交的 `hwkbd.keystore`(CN=Gaia IME,SHA-256 `26bb762c…`),debug 与 release 同签 ——
好处是 `adb install -r` / 应用内升级都不会撞 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`;
代价是签名私钥公开(个人项目的自觉取舍)。若将来配了 CI secret,`ci` 签名配置会顶掉项目密钥,
届时证书指纹会变,和已装的旧包不兼容。

**已知 flake(不是代码问题)**:runner 上 `maven.aliyun.com` 偶发 502,会让
`:app:lintVitalAnalyzeRelease` 解析 `com.android.tools.analytics-library:tracker` /
`jakarta.activation-api` 失败,整条 release job 挂在 `assembleRelease` 这一步(debug 不跑 lint,
所以 CI workflow 看不出来,只有 release workflow 才暴露)。502 属于硬错误、不会顺延到下一个仓库,
因此不会自愈。**处理:`gh run rerun <run-id> --failed` 重跑即可**(2026-09-30 发 v0.4.0 首发就撞上,
重跑一次通过)。

**版本节奏**:v0.3.0 只改了 build.gradle、没有发 tag,连同 23 个提交一起并进 v0.4.0 发布;
发布历史(v0.1.0 / v0.2.0 / v0.4.0)以 GH Release 为准,tag 不连续是正常的。


## Timeline

- time: 2026-09-30T09:50:05
  kind: decision
  summary: "Created this page: 发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
  source: "2026-09-30 发布 v0.4.0 实操"
  affects: [release-flow]

- time: 2026-09-30T09:50:22
  kind: decision
  summary: "记录发布链路、签名现状与 runner 上 aliyun 502 导致的 lintVital 失败(重跑可解)"
  source: "2026-09-30 发布 v0.4.0"
  affects: [release-flow]
