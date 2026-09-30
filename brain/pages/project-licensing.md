---
id: project-licensing
title: "项目闭源与仓库私有(专有许可证)"
category: decision
status: active
tags: [license, proprietary, distribution]
created: "2026-09-30T09:56:55"
updated: "2026-09-30T11:11:13"
---

<!-- compiled_truth -->
盖亚输入法(Gaia)是**闭源专有软件**:专有许可证保留所有权利;源码仓库 `adam-ikari/hwkbd_ime` 为
**private**。

**许可证的三个落点,分工是用户定的**:

1. 仓库根 `LICENSE` —— 本软件的专有条款(禁止复制/再分发/修改/反编译/出租/商用、禁止移除
   版权声明、按现状提供不担保)。README 末尾有一行指向它。GitHub 会把它识别成 "Other",预期如此。
2. 仓库根 `THIRD_PARTY_NOTICES.md` —— 第三方组件与数据的署名 + 许可证全文/出处链接:
   AndroidX(core-ktx/appcompat/preference)、Kotlin 标准库与 coroutines 是 Apache-2.0
   (含 Kotlin 例外条款);`fxsjy/jieba` 词频表是 MIT(Copyright (c) 2013 Sun Junyi)、
   构建期用的 `pinyin-pro` 是 MIT(Copyright (c) 2022-present zh-lx)。这两条版权行是**从上游
   LICENSE 原文核过的**,别凭记忆改写。构建期工具(AGP/lint/Gradle 插件)不随 APK 分发,不列。
3. 应用内 `设置 → 许可 → 开源许可`(`licenses_body`)—— **只列第三方**:用了谁的代码/数据、归谁、
   什么许可证,再指回仓库的 THIRD_PARTY_NOTICES。

**应用内不强调本软件的闭源(2026-09-30 用户定,问过一次才落定)**:关于页只留版本号、一句定位、
jieba 署名,**不加"闭源专有软件/版权所有"字样**;专有条款不进应用界面,也不进站点的首页按钮区。
以后别"顺手"把闭源字样加回关于页、首启页或那个许可弹窗 —— 用户要的是履行第三方义务,不是自我声明。

**关于页的 jieba 署名是许可义务**,与"要不要强调闭源"无关,改文案时别删。

**对外发布走"私有源码 + 公开站点"这个既有模式**(2026-09-30 落地):公开仓库
`adam-ikari/gaia`(VitePress + GitHub Pages,<https://adam-ikari.github.io/gaia/>),只放站点内容
与**下载用的 APK**;源码一行都不进去。站点品牌名用英文 **Gaia**、中文叫"盖亚输入法"。
这是用户已有的惯例(`powerkit` 私有 + `powerkit-web` 公开、`battery-indicator-downloads`),
不是新发明的做法。**关键点**:私有仓库的 Release 下载链接需要登录态,所以包必须挂在**公开的站点
仓库**上,匿名才下得动 —— 2026-09-30 已实测匿名 `curl` 能取到 app-release.apk(302 到签名 CDN 链接)。
代价:APK 本身是公开可下载的(闭源 ≠ 不分发)。以后发版要记得同步,见 [[release-flow]] 的第二步。

**连带推论(别重新踩)**:

- 引入任何依赖、模板、脚手架之前,先核对其许可是否允许闭源再分发,并**同步补进
  THIRD_PARTY_NOTICES 与 `licenses_body`** —— 这两处是手工维护的,加依赖不会自动更新。
- 站点仓库也含第三方代码(vitepress 等),它跟应用是两个独立的许可域:站点按公开仓库正常处理即可,
  不要把应用的专有条款套到站点仓库上。


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
