---
id: site-voice
title: "站点用词与命名规则(盖亚输入法,不说 Gaia/闭源)"
category: decision
status: active
tags: [naming, voice, content]
created: "2026-09-30T12:15:33"
updated: "2026-10-04T07:14:30"
---

<!-- compiled_truth -->
站点的**产品名只有一个写法：盖亚输入法**。这一条是用户分几次纠正出来的,以后写文案按它来:

- **不出现 `Gaia`**(英文名)。hero、导航、标签页、侧边栏、页脚、正文里都不许有。
- **不出现「闭源软件 / 闭源发行」这类说法**。许可相关只写第三方许可与署名,以及一句
  "保留所有权利,未经许可不得复制/再分发/修改/反编译/商用"这种条款性表述 —— 条款要留,
  但不要给产品贴"闭源"这个标签。
- **不提包名**(`dev.hwkbd.ime` 之类)。只说"Android 8.0 及以上"。
- **不写反馈渠道**。没有公开反馈入口,就别留一个"反馈"章节占位。
- **不写"参考/模仿某某输入法"**。这套交互怎么来的、跟谁像,属于内部背景,不出现在对外站点上。

**一个必须分清的区别**:仓库名与 URL 里的 `gaia` 是基础设施标识,不是文案 —— 仓库是
`adam-ikari/gaia`、Pages 地址是 `https://adam-ikari.github.io/gaia/`、`config.mts` 的
`base` 必须与仓库名一致(`/gaia/`),这三处不改(改了等于换站)。除此之外,凡是**页面上人眼能读
到的字符串**,一律写"盖亚输入法"。以后要改显示名,只动 `config.mts`、各页正文、页脚这几处。

**隐私表述要跟着事实走**:站点接入统计之后,关于页不能再写"不含统计与追踪代码",必须改成
"通过自建的 Umami 采集访问量(无 cookie、不做跨站跟踪)"。写完统计相关的东西记得回头核这句。


## Timeline

- time: 2026-09-30T12:15:33
  kind: decision
  summary: "Created this page: 站点用词与命名规则(盖亚输入法,不说 Gaia/闭源)"
  source: "2026-09-30 用户多次纠正"
  affects: [site-voice]

- time: 2026-09-30T12:16:16
  kind: decision
  summary: "对外一律写「盖亚输入法」;不说 Gaia、不说闭源、不说包名、不说反馈渠道、不提模仿对象"
  source: "2026-09-30 用户逐条纠正"
  affects: [site-voice]

- time: 2026-10-04T07:14:30
  kind: note
  summary: "第三方署名要跟着换内核一起改:引擎换了、组件删了,页脚与设置页的署名不会自动跟着变,必须手动核"
  source: "2026-10-04 同上"
  affects: [site-voice, site-copy]
