---
slug: stack
title: Tech stack
role: tech-stack choices
updated: "2026-09-30T12:17:06"
---

# Tech stack

| 域 | 选择 | 理由 | 备选 / 现状 |
| --- | --- | --- | --- |
| 站点框架 | VitePress 1.6 | 和作者其他项目(`powerkit-web`)一致;Markdown + Vue,改文案成本低 | 纯手写 HTML |
| 样式 | 手写 CSS 覆盖 + VitePress 默认主题 | Apple 风格靠排版与留白,不需要换主题 | 见 [[site-style]] |
| 部署 | GitHub Pages(`main` 推送即部署) | 免费、无服务器、无需 CI 之外的运维 | Netlify / Cloudflare Pages |
| 下载来源 | 站点仓库的 GitHub Release + `api.github.com` 动态读取 | 匿名可下;发版即更新,无需改站点 | 手动往仓库传文件(已弃用) |
| 访问统计 | 自建 Umami(Cloudflare Workers + D1) | 免费、无 cookie、数据自持 | Plausible 付费 / GoatCounter |
| 下载量 | GitHub 资产自带 `download_count` | 零成本、零隐私代价 | 无 |

**CI**:站点仓库只有 `deploy.yml`(build → upload-pages-artifact → deploy-pages)。
**首次部署坑**:新建仓库必须先开 Pages(`build_type=workflow`),否则 deploy-pages 报
`Ensure GitHub Pages has been enabled`。

**仓库名与显示名不是一回事**:仓库 `adam-ikari/gaia`、`base: '/gaia/'` 是基础设施;页面上
一律写「盖亚输入法」——见 [[site-voice]]。
