---
id: release-flow
title: "发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
category: project
status: active
tags: [release, ci, tooling]
created: "2026-09-30T09:50:05"
updated: "2026-09-30T11:10:51"
---

<!-- compiled_truth -->
发布 = 改版本号 → 打 `v*` tag → 推 tag,CI 负责出包和 release 页面。**现在是两步**(应用仓库出包 + 传到公开站点仓库)。

**第一步(照旧)**:版本号唯一来源是 `app/build.gradle.kts` 的 `versionCode` / `versionName`,随功能
提交一起改;推 `v*` tag 触发 `.github/workflows/release.yml` → 单测 → `assembleRelease` +
`assembleDebug` → 建 GH Release。CI 硬编码 `app-release.apk` / `app-debug.apk` 两个文件名。

**第二步(2026-09-30 起,别漏)**:把两个包传到公开站点仓库 `adam-ikari/gaia` 的**同名** Release ——
```bash
gh release create v0.4.1 \
  app/build/outputs/apk/release/app-release.apk#app-release.apk \
  app/build/outputs/apk/debug/app-debug.apk#app-debug.apk \
  --repo adam-ikari/gaia
```
站点下载页 `Downloads.vue` 读的是**这个仓库**的 `releases/latest`,不传的话站点永远停在旧版本。
**目前这步是手工的**:`hwkbd_ime` 没有 `WEB_RELEASE_TOKEN` secret(2026-09-30 核对为空)。想自动化
就照 `powerkit` 的做法,在主仓库 CI 加 `publish-site` 任务 + 一个只授权 `gaia` 写 Contents 的
fine-grained PAT。详见 [[project-licensing]]。

**签名现状**:仓库 Secrets 里**没有**配 `SIGNING_KEYSTORE_BASE64` 等,所以 CI 走的是仓库内
提交的 `hwkbd.keystore`(CN=Gaia IME,SHA-256 `26bb762c…`),debug 与 release 同签 ——
`adb install -r` / 应用内升级都不会撞 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。代价是签名私钥随
仓库一起(仓库已转私有,见 [[project-licensing]])。若将来配了 CI secret,`ci` 签名配置会顶掉项目
密钥,届时证书指纹会变,和已装的旧包不兼容。

**已知 flake(不是代码问题)**:runner 上 `maven.aliyun.com` 偶发 502,会让
`:app:lintVitalAnalyzeRelease` 解析 `com.android.tools.analytics-library:tracker` /
`jakarta.activation-api` 失败,整条 release job 挂在 `assembleRelease` 那一步(debug 不跑 lint,
所以 CI workflow 看不出来)。502 是硬错误、不顺延到下一个仓库,不会自愈。
**处理:`gh run rerun <run-id> --failed`**(v0.4.0 首发撞过一次,重跑通过)。

**版本节奏**:v0.3.0 只改了 build.gradle、没发 tag,连同 23 个提交并进 v0.4.0 发了;发布历史
(v0.1.0 / v0.2.0 / v0.4.0 / v0.4.1)以 GH Release 为准,tag 不连续是正常的。


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

- time: 2026-09-30T09:57:18
  kind: reversal
  summary: "仓库转私有后,'签名私钥公开'这个当初权衡的代价不再成立(改由 [[project-licensing]] 承载)"
  source: "2026-09-30 用户定案项目闭源/私有"
  affects: [project-licensing]

- time: 2026-09-30T11:10:51
  kind: decision
  summary: "发布变成两步:应用仓库出包 + 传到公开站点仓库 gaia 的同名 Release(匿名可下)"
  source: "2026-09-30 建 Gaia 站点时定"
  affects: [release-flow]
