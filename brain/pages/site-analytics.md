---
id: site-analytics
title: "站点统计:Cloudflare Workers 自建 Umami + GitHub 下载次数"
category: decision
status: active
tags: [analytics, umami, cloudflare]
created: "2026-09-30T12:15:33"
updated: "2026-09-30T12:16:16"
---

<!-- compiled_truth -->
站点统计走**自建 Umami**,部署在 Cloudflare Workers + D1(用户从 Umami/Plausible/GoatCounter/
只统计下载 四个选项里选的这个)。理由:成本 0、数据在自己手里、无 cookie 无跨站跟踪 —— 和
这个项目一贯的"不上传、不声明权限"取向一致。

**接入方式必须做成配置开关**,不能写死域名/站点 ID:`config.mts` 里放一个 `umami` 配置项
(website id + script url),为空就不输出 `<script>`。这样本地开发与"还没部署好"的中间状态
都不会产生 404 请求,也不用为了加统计改一次构建脚本。

**Cloudflare 侧要用户自己做**(没有凭据我做不了),需要三步:
1. 建一个 D1 数据库,导入 Umami 的 `schema.sql`;
2. 部署 Umami 的 Worker(官方 `cloudflare/analytics` 仓库里有现成模板),绑定该 D1;
3. 在 Umami 后台建站点,拿到 website ID,填进站点配置的 `umami` 里。

**下载次数不靠第三方**:GitHub Release 资产自带 `download_count`,`Downloads.vue` 调
`api.github.com/repos/adam-ikari/gaia/releases/latest` 时顺手就能拿到,直接显示在下载表里 ——
对下载站来说这才是核心数字,零成本、零隐私代价。UMAMI 的访问量是辅助指标。


## Timeline

- time: 2026-09-30T12:15:33
  kind: decision
  summary: "Created this page: 站点统计:Cloudflare Workers 自建 Umami + GitHub 下载次数"
  source: "2026-09-30 用户选定"
  affects: [site-analytics]

- time: 2026-09-30T12:16:16
  kind: decision
  summary: "Umami 自建在 Cloudflare Workers+D1(用户选定);下载次数直接用 GitHub API 的 download_count"
  source: "2026-09-30 用户在方案里选了 Workers+Umami"
  affects: [site-analytics]
