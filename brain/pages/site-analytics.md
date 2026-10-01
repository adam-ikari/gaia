---
id: site-analytics
title: "站点统计:Cloudflare Workers 自建 Umami + GitHub 下载次数"
category: decision
status: active
tags: [analytics, umami, cloudflare]
created: "2026-09-30T12:15:33"
updated: "2026-10-01T12:06:05"
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

## 下载次数:给我看的,不摆到页面上(2026-10-01 定的口径)

`download_count` 是**经营数据,不是访客的决策依据**。它已经从页面上拿掉了 ——
要我自己的数字,`gh release view adam-ikari/gaia` 或 Release 页面本身就有,
不必让每个访客替我盯着。摆在按钮旁边只会让人多读一眼,还会在取不到时
忽然消失、让页面看起来像坏了。

Umami 的访问量是辅助指标,同理由用户自己看后台。

**页面上的下载入口是纯静态的,不发任何客户端请求**:
`https://github.com/adam-ikari/gaia/releases/latest/download/app-release.apk`
由 GitHub 站点直接服务(302 到 release-assets),**不走 REST 配额**。
未认证 REST 配额 60 次/小时、**按出口 IP 计**,同 IP 后面的访客互相抢,403 是常态。
前提:目标 release 不是 draft / prerelease,否则 `latest` 不指向它。

包挂在**公开的站点仓库** `adam-ikari/gaia` 上(应用仓库 `adam-ikari/hwkbd_ime` 是私有的,
那边的下载链接需要登录态,访客拿不到)。站点只提供正式版。

**一条可复用的判据**:页面上该不该显示一个数字,先问"访客拿它做什么决策"。
答不上来就归到"给我看的",归到我这边的信息不进访客的页面。


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

- time: 2026-10-01T11:48:40
  kind: reversal
  summary: "下载数据源的口径变了:download_count 不再是拿不到就没有的必需品。下载**链接**改走 github.com/<repo>/releases/latest/download/<文件名>(不走 REST 配额),GitHub API 降级为只取版本号/大小/下载次数的锦上添花。原来的'取不到就整块不渲染'让限流(60 次/小时按 IP 共享,实测常态)时唯一的转化动作整块消失 —— 装饰信息缺失不该让功能失效。见 [[site-mobile]]"
  source: "2026-10-01 下载修复"
  affects: [site-analytics, site-mobile]

- time: 2026-10-01T11:49:00
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-analytics]

- time: 2026-10-01T12:06:05
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-analytics]
