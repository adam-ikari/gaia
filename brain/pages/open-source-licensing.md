---
id: open-source-licensing
title: "项目开源与许可证(GPL-3.0-only)"
category: decision
status: active
tags: [license, open-source, gpl, distribution]
created: "2026-10-26T16:15:46"
updated: "2026-10-26T16:15:46"
---

<!-- compiled_truth -->
盖亚输入法(Gaia)以 **GPL-3.0-only** 提供(`Copyright (c) 2026 adam-ikari`),源码仓
`adam-ikari/hwkbd_ime` 转 **public**。本页**推翻** [[project-licensing]](2026-09-30 定案的
「闭源专有 + 仓库私有」,已归档)。

**推翻的理由不是理念,是仓库形状**:签名钥匙 `hwkbd.keystore` 与它的口令一直写死在被跟踪的
`app/build.gradle.kts` 里。私有仓时那是「本地/CI 签名一致 > 私密性」的权衡;公开仓时它就是
公开的密码。配套两条已落地:口令挪进 `local.properties`(已 ignore)或 CI 的 `SIGNING_*`,
钥匙本体挪到仓外一份(sha256 `4389fa65…`)。**不换钥匙** —— 线上证书是 `26bb762c…18dcd`,
换了老用户不能原地升级,那是不可逆的用户损失。

**许可的四个落点(分工没变,内容变了)**:

1. 仓库根 `LICENSE` —— GNU GPL version 3 全文,取自 `/usr/share/common-licenses/GPL-3`,
   **不手抄**(抄错一个从句就是另一份许可)。SPDX 选 3.0-only,不含「或更高版本」。
2. 仓库根 `THIRD_PARTY_NOTICES.md` —— 第三方组件与数据的署名 + 许可证全文/出处。
   `jieba`(MIT, 2013 Sun Junyi)与 `pinyin-pro`(MIT, 2022-present zh-lx)那两条版权行是
   **从上游 LICENSE 原文核过的**,别凭记忆改写。构建工具不随 APK 分发,不列。
3. 应用内 `设置 → 许可 → 开源许可`(`strings.xml` 的 `licenses_body`)—— 第三方清单照旧,
   **末尾新增本软件自己的许可传达**(GPL-3.0-only + 源码地址)。这不是「顺手加字」:GPL 第 5 条
   要求分发二进制时同时传达许可与源码获取方式,而应用就是大多数人唯一会读的那一份。
4. README「许可」一节 —— 指向 LICENSE 与 THIRD_PARTY_NOTICES。

**旧规矩的处置**:「应用内不强调闭源、别把闭源字样加回关于页」(2026-10-21)随专有许可一起作废
—— 现在应用内必须写明 GPL。但同一批话术里另一条仍然成立:**站点首页按钮区不谈许可、不吹
「不联网/不上传」**(见 [[site-copy]])。开源与否都不改变「站点文案不许写无法兑现的承诺」这条判据。

**连带推论(方向反了,仍是硬约束)**:

- 引入新依赖前核许可证的**兼容性**,判据从「允许闭源再分发」变成「与 GPL-3.0-only 兼容」:
  GPL/LGPL/Apache-2.0/BSD/MIT 可;专有、GPL-incompatible(含 AGPL 的模块边界问题)不可。
- `THIRD_PARTY_NOTICES.md` 与 `licenses_body` 是手工维护的两处,加依赖不会自动更新 —— 这条没变。
- 再分发必须附带源码或获取方式,并保留全部版权声明与这份第三方署名。
- 站点仓 `adam-ikari/gaia` 仍只放构建产物;应用与站点是两个独立许可域,别把 GPL 套到站点产物上。
- **公开历史里不许再有凭据**:本次清洗的范围是钥匙 blob + 口令字符串(40 个历史版本) + 作者邮箱
  (mailmap)。判据与教训见 [[release-flow]]。


## Timeline

- time: 2026-10-26T16:15:46
  kind: decision
  summary: "Created this page: 项目开源与许可证(GPL-3.0-only)"
  source: "2026-10-26 用户定案"
  affects: [open-source-licensing]

- time: 2026-10-26T16:15:46
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-26 用户定案:仓库转公开,许可证 GPL-3.0-only"
  affects: [open-source-licensing]
