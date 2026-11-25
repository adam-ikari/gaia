---
id: site-device-composite
title: "站点截图合成:用 Titan 2 官网产品图做机身,截图叠进屏幕"
category: decision
status: active
tags: [site, mockup, titan2]
created: "2026-11-24T16:39:12"
updated: "2026-11-24T16:39:12"
---

<!-- compiled_truth -->
首页全部 9 张截图不再单独摆横条，统一叠进 Titan 2 机身图里。用户明确选了三件事（2026-11-24 三问）：

1. 机身图抓 Unihertz 官网产品图，不用自绘外壳；
2. 9 张截图全部合成，不是只做首屏；
3. 产出用 Vue 组件叠加，不是离线生成 PNG。

## 落法

- 机身素材：`web/docs/public/img/titan2-shell.png`。源是 Unihertz Titan 2 产品页的 CDN 图（`titan-2-...-2923626.jpg`），裁到 464×550（偏移 +268+140），背景从四角 flood-fill 成透明，**屏幕区 (22,79)-(442,499) 挖成透明孔**。源 URL 与裁切参数写进 PNG 的文本元数据。
- `Shot.vue` 保留 `src`/`alt` 接口，`index.md` 不用改。屏幕区用四个百分比定位：left 4.74% / top 14.36% / width 90.52% / height 76.36%；机身图换了这四个数必须一起改。
- `crop-*` 是 1440×96 输入法行，贴在屏幕底部、上面保持黑底（不凭空造 App UI）；`08/09` 是 1440×1440 整屏，铺满屏幕区。可用 `fit` prop 覆盖自动判断。
- 用户没要求改 hero，hero 保持原样。

## 踩过的坑

机身图屏幕区第一版填了**不透明黑**，直接盖掉下面的截图，页面看着像屏幕全黑。孔必须是透明的，黑底由 `.gaia-shot-screen` 的 CSS 背景提供。背景抠图用 flood-fill 阈值 18 会在深底分区留下白色锯齿边，提到 55 才干净。

## 验证

`npm run build` 通过；`tools/sitecheck/mobile-check.mjs` 7 档视口 innerWidth 全等于设备宽、无横向滚动。puppeteer 逐分区截图看过浅底/深底、行截图/整屏截图、576×576 Titan 2 视口。没有真机验证。


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
