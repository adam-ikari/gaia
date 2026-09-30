---
id: project-licensing
title: "项目闭源与仓库私有(专有许可证)"
category: decision
status: active
tags: [license, proprietary, distribution]
created: "2026-09-30T09:56:55"
updated: "2026-09-30T09:57:59"
---

<!-- compiled_truth -->
盖亚输入法是**闭源专有软件**:专有许可证保留所有权利,GitHub 仓库
`adam-ikari/hwkbd_ime` 为 **private**(2026-09-30 用户再次确认要闭源;仓库可见性当时已是 private)。

**许可证载体**:仓库根 `LICENSE` = 专有条款 —— 未经书面许可禁止复制/再分发/修改/反编译/
反汇编/出租/商用,以及移除版权声明;按"现状"提供、不担保、不承担损失责任。README 末尾有一行
指向它。GitHub 会把这种非标准许可证识别成 "Other",这是预期的。

**应用内不强调闭源(2026-09-30 用户定)**:`设置 → 关于` **只留版本号、一句定位、jieba 署名**,
不加"闭源专有软件/版权所有"这类声明 —— 用户明确不要在软件里强调闭源。所以专有声明的落点
只有仓库 `LICENSE` 与 README 那一行。以后别"顺手"把闭源字样加回关于页或首启页。

**第三方组件必须逐个署名**(许可证里列了一张表):AndroidX、Kotlin 标准库与 coroutines 是
Apache-2.0;`fxsjy/jieba` 词频表与构建期用的 `pinyin-pro`(npm)都是 MIT。**MIT 要求保留原始
版权与许可声明**,所以应用内 `设置 → 关于` 的"词库来源:fxsjy/jieba (MIT)"这一句是**许可义务**,
不是可选项 —— 改关于页文案时别把它一起删掉(它与"要不要强调闭源"是两件事)。运行时不含
pinyin-pro 的代码(读音在构建期离线生成)。

**连带推论(影响后续决策,别重新踩)**:

- 引入任何依赖、模板、脚手架之前,先核对其许可是否允许"闭源再分发";不接受"要求本项目自身
  开源"的东西。抄别人代码同理。
- 签名密钥 `hwkbd.keystore` 提交在仓库里,当初的理由是"个人项目权衡:本地/CI 签名一致 > 私密性"。
  **仓库转私有后,密钥随私钥范围一起收紧,这个顾虑基本消失**(见 [[release-flow]] 的对应反转)。
- 介绍与下载站(VitePress,做站点这件事本身还没落地)不能直接用私有仓库的 GitHub Pages 对外
  发布 —— private 仓库的 Pages 需要 GitHub 付费计划。要对外发站点,只能另开一个**公开**仓库
  放站点、或自托管;下载链接仍指向本私有仓库的 Release(那种链接需要登录态,对外发要注意这一点)。


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
