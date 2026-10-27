---
id: project-licensing
title: "项目闭源与仓库私有(专有许可证)"
category: decision
status: archived
tags: [license, proprietary, distribution]
created: "2026-09-30T09:56:55"
updated: "2026-10-27T08:59:49"
---

<!-- compiled_truth -->
盖亚输入法(Gaia)以 **GPL-3.0-only** 提供(`Copyright (c) 2026 adam-ikari`),源码仓
`adam-ikari/hwkbd_ime` 转 **public**。这条**推翻** 2026-09-30 的「闭源专有 + 仓库私有」定案。

**推翻的理由不是理念,是仓库形状**:签名钥匙 `hwkbd.keystore` 与它的口令一直写死在
被跟踪的 `app/build.gradle.kts` 里。私有仓时那是「本地/CI 签名一致 > 私密性」的权衡;
公开仓时它就是公开的密码。与之配套的两条已落地:口令挪进 `local.properties`(已 ignore)
或 CI 的 `SIGNING_*`,钥匙本体挪到仓外一份(sha256 `4389fa65…`)。
**不换钥匙**:线上证书是 `26bb762c…18dcd`,换了老用户不能原地升级 —— 那是不可逆的用户损失。

**许可的落点(分工仍是用户定的,数量没变,内容变了)**:

1. 仓库根 `LICENSE` —— GNU GPL version 3 全文(取自 `/usr/share/common-licenses/GPL-3`,
   不手抄)。SPDX 选 **3.0-only**,不含「或更高版本」。
2. 仓库根 `THIRD_PARTY_NOTICES.md` —— 第三方组件与数据的署名 + 许可证全文/出处。
   那两条版权行(`jieba` MIT 2013 Sun Junyi、`pinyin-pro` MIT 2022-present zh-lx)是**从上游
   LICENSE 原文核过的**,别凭记忆改写。构建工具不随 APK 分发,不列。
3. 应用内 `设置 → 许可 → 开源许可`(`licenses_body`)—— 第三方清单照旧,**末尾新增本软件
   自己的许可传达**(GPL-3.0-only + 源码地址)。这不是「顺手加字」:GPL 第 5 条要求分发
   二进制时同时传达许可与源码获取方式,而应用就是大多数人唯一会读的那一份。
4. README「许可」一节 —— 指向 LICENSE 与 THIRD_PARTY_NOTICES。

**旧规矩的处置**:「应用内不强调闭源、别把闭源字样加回关于页」那条(2026-10-21)随专有许可
一起作废 —— 现在应用内必须写明 GPL。但**同一批话术里另一条仍然成立**:站点首页按钮区不谈
许可、不吹「不联网/不上传」(见 [[site-copy]])。开源与否都不改变「站点文案不许写无法兑现的
承诺」这条判据。

**连带推论(方向反了,但仍然是硬约束)**:

- 引入新依赖前先核许可证的**兼容性**,现在判据从「允许闭源再分发」变成「与 GPL-3.0-only 兼容」:
  GPL/LGPL/Apache-2.0/BSD/MIT 可,专有或 GPL-incompatible(如 AGPL 的模块边界、若干商业许可)不可。
- `THIRD_PARTY_NOTICES.md` 与 `licenses_body` 是手工维护的两处,加依赖不会自动更新 —— 这条没变。
- 再分发必须附带源码或获取方式,并保留全部版权声明与这份第三方署名。
- 站点仓 `adam-ikari/gaia` 仍只放构建产物;应用与站点是两个独立许可域,别把 GPL 套到站点产物上。


## Timeline

- time: 2026-09-30T09:56:55
  kind: decision
  summary: "Created this page: 项目闭源与仓库私有(专有许可证)"
  source: "2026-09-30 用户定案"
  affects: [project-licensing]

- time: 2026-09-30T09:57:11
  kind: decision
  summary: "闭源专有许可 + 私有仓库,并记下它对依赖选型、签名密钥与介绍站的连带影响"
  source: "2026-09-30 用户定案"
  affects: [project-licensing]

- time: 2026-09-30T09:57:59
  kind: decision
  summary: "补一条:应用内不强调闭源(用户定),专有声明只落在仓库 LICENSE/README"
  source: "2026-09-30 用户定:软件中不用强调闭源"
  affects: [project-licensing]

- time: 2026-09-30T10:09:43
  kind: decision
  summary: "应用内新增「设置 → 许可 → 开源许可」:只列第三方署名与许可证,不带本软件许可;并落 THIRD_PARTY_NOTICES.md"
  source: "2026-09-30 用户定:应用内只列第三方开源许可"
  affects: [project-licensing]

- time: 2026-09-30T11:11:13
  kind: decision
  summary: "Gaia 站点已落地:公开仓库 adam-ikari/gaia 承载 VitePress + APK 分发,源码仍私有"
  source: "2026-09-30 建 Gaia 站点"
  affects: [project-licensing]

- time: 2026-10-26T16:13:43
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-26 用户定案:仓库转公开,许可证 GPL-3.0-only"
  affects: [project-licensing]

- time: 2026-10-26T16:15:46
  kind: reversal
  summary: "2026-10-26 仓库转公开:GPL-3.0-only 推翻「闭源专有 + 私有仓」。触发条件是签名钥匙与口令写死在被跟踪文件里,公开后即成公开密码。新页见 [[open-source-licensing]]"
  source: brain archive-page
  affects: [project-licensing]

- time: 2026-10-27T08:59:49
  kind: reversal
  summary: "作废「站点仓 adam-ikari/gaia 仍只放构建产物;应用与站点是两个独立许可域」。2026-10-27 monorepo:站点源码在同一个仓的 web/ 下,随根 LICENSE 一起是 GPL-3.0-only —— 许可域合并成一个,不是「把 GPL 套到站点产物上」(产物 dist/ 根本不进 git,不进任何分发)。第三方素材照旧各署各的:Inter 字体 SIL OFL、rime 词库与 jieba/pinyin-pro 那份清单见 THIRD_PARTY_NOTICES.md,与代码许可互不覆盖"
  source: "2026-10-27 用户「删除网站仓库 本仓库使用monorepo模式」"
  affects: [project-licensing, open-source-licensing, site-repo-topology]
