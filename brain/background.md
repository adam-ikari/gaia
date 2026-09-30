---
slug: background
title: Project background
role: project background
updated: "2026-09-30T12:17:06"
---

# Project background

**盖亚输入法**的公开站点。应用本体在私有仓库 `adam-ikari/hwkbd_ime`(闭源、不对外分发源码),
站点的职责只有两件:

1. **讲清楚这是什么、怎么用**(首页 / 功能详解 / 常见问题);
2. **让人下得到 APK**(下载页)。

站点是纯静态的 VitePress,部署在 GitHub Pages 上,没有任何服务端。

## 为什么是「私有源码 + 公开站点」两个仓库

应用仓库私有 → 它的 Release 下载链接需要登录态 → 访客拿不到包。所以 APK 作为构件挂在
**公开的站点仓库**(`adam-ikari/gaia`)的 Release 上,下载页动态读它的 `latest`。
代价是 APK 本身公开可下载 —— 这是"要有下载站"这件事本身要求的。

## 非目标

- 不做用户账号、评论、搜索站内内容;
- 不收集访客身份信息;
- 不在站点上放应用源码或反编译相关内容(见 [[site-voice]]:对外连"闭源"这个标签都不提,
  只写第三方许可与署名)。
