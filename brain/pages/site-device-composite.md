---
id: site-device-composite
title: "站点截图合成:用 Titan 2 官网产品图做机身,截图叠进屏幕"
category: decision
status: active
tags: [site, mockup, titan2]
created: "2026-11-24T16:39:12"
updated: "2026-11-25T09:22:08"
---

<!-- compiled_truth -->
首页 9 张截图叠进 Titan 2 机身图，机身图取自 Unihertz 官网产品图。2026-11-25 按用户反馈改成：截图用 `out/media/shots/` 的 1440×1440 **整屏图**，不再只贴底部输入法行；机身最大宽度从 620px 缩到 360px；一张带里放两台机器时用 `gaia-shot-pair` 并排，≤430px 叠放。

## 当前落法

- `public/img/titan2-shell.png`：官网正面产品图裁成 464×550（偏移 +268+140），背景与屏幕区 (22,79)-(442,499) 挖成透明孔，源 URL 与裁切参数在 PNG 文本元数据里。
- `Shot.vue` 保留 `src`/`alt` 接口，`index.md` 不用改组件名。屏幕区四个百分比：left 4.74% / top 14.36% / width 90.52% / height 76.36%，换机身图必须一起改。
- `screen-*.png` 是 `out/media/shots/` 整屏图的拷贝；早先的 `crop-*.png`（1440×96 底部行）已删。
- `.gaia-shot-pair` 在 home.css 里分栏、在 responsive.css 的 ≤430px 断点里叠放；并排时要压掉 `Shot.vue` 给连续两张图加的 14px margin，否则两台机器错位。

## 为什么改

第一版把 1440×96 的输入法行贴在屏幕底部、上面留黑，用户反馈「真机图太大了，截图又只有底部」。整屏图虽然 UI 缩小后字也小，但画面完整；机身缩到 360px 后不再压过正文。

## 验证

`npm run build` 通过；7 档视口 sitecheck 全过；puppeteer 看过 1280 / 576 / 375 三档，含并排与叠放两种形态。无真机验证。


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
