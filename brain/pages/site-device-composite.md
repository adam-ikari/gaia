---
id: site-device-composite
title: "站点截图合成:用 Titan 2 官网产品图做机身,截图叠进屏幕"
category: decision
status: active
tags: [site, mockup, titan2]
created: "2026-11-24T16:39:12"
updated: "2026-11-25T10:34:52"
---

<!-- compiled_truth -->
首页 9 张截图叠进 Titan 2 机身图。截图取 `out/media/shots/` 的 1440×1440 整屏图，转 720×720 WebP；机身 `titan2-shell.webp` 来自 Unihertz 官网产品图，裁 464×550，屏幕区挖透明。`Shot.vue` 保留 `src`/`alt` 接口，屏幕区四个百分比：left 4.74% / top 14.36% / width 90.52% / height 76.36%。机身最大宽 360px，同带两台并排、≤430px 叠放。

2026-11-25 图片从 PNG 改成 WebP（屏幕 326px 显示，出 720px 图），9 张截图加机身从 1.1MB 降到 148KB；`loading="lazy"` 不变。此前「只贴底部输入法行」的画法已被推翻，见 timeline。

验证：`npm run build`、7 档 sitecheck 全过；puppeteer 确认 9 张截图 naturalWidth=720、无请求失败，并看过 1280/576/375 三档。无真机验证。


## Timeline

- time: 2026-11-24T16:39:12
  kind: decision
  summary: "Created this page: 站点截图合成:用 Titan 2 官网产品图做机身,截图叠进屏幕"
  source: "2026-11-24 用户要求「更新网站 使用titan2的效果图加上截图合成」"
  affects: [site-device-composite]

- time: 2026-11-24T16:39:12
  kind: decision
  summary: "定下机身图来源、9 张截图全部合成、Vue 叠加方案"
  source: "2026-11-24 用户三问确认 + 本机实测"
  affects: [site-device-composite]

- time: 2026-11-24T16:39:12
  kind: decision
  summary: "9 张截图全部叠进 Titan 2 官网产品图;机身图屏幕区必须挖透明孔"
  source: "2026-11-24 本机实现与 puppeteer 验证"
  affects: [site-style, site-device-composite]

- time: 2026-11-25T08:19:42
  kind: evidence
  summary: "已发布:提交 88e86c4 推到 origin/master,dispatch deploy-site.yml run 45516835713 成功(含逐字节验线上),线上 titan2-shell.png 的 sha256 与本地一致"
  source: "git log、gh run watch、curl+sha256sum 2026-11-25"
  affects: [site-device-composite, site-repo-topology]

- time: 2026-11-25T09:22:08
  kind: decision
  summary: "改为整屏截图 + 机身缩到 360px + 两台并排"
  source: "2026-11-25 用户「真机图太大了 截图又只有底部 完整屏幕截图」"
  affects: [site-device-composite]

- time: 2026-11-25T09:22:08
  kind: reversal
  summary: "推翻「crop-* 输入法行贴屏幕底部」:改为整屏截图铺满屏幕区;机身最大宽度 620px→360px;两台机器并排"
  source: "2026-11-25 用户反馈「真机图太大了 截图又只有底部 完整屏幕截图」"
  affects: [site-device-composite, site-style]

- time: 2026-11-25T09:34:41
  kind: evidence
  summary: "整屏截图版已发布:提交 9c61599,dispatch run 45526201098 全绿,线上 screen-01-idle.png 与本地 sha256 一致,首页不再引用 crop-*"
  source: "git log、gh run watch、curl+sha256sum 2026-11-25"
  affects: [site-device-composite, site-repo-topology]

- time: 2026-11-25T10:34:52
  kind: decision
  summary: "图片改 WebP，按显示 2 倍出图"
  source: "2026-11-25 用户「优化图片加载速度」"
  affects: [site-device-composite]

- time: 2026-11-25T10:34:52
  kind: decision
  summary: "图片改 WebP：截图 1440→720px、机身 464×550 保原尺寸；1.1MB→148KB"
  source: "2026-11-25 用户要求优化图片加载速度"
  affects: [site-device-composite, site-style]
