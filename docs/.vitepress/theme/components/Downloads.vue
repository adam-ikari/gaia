<template>
  <div class="gi-dl">
    <!-- **`download` 属性不能省**(2026-10-02 真机报障:点按钮是 404 页,直接访问同一个 URL 却能下载)。
         VitePress 拦截站内链接交给前端路由,判定函数 `treatAsHtml` 的已知扩展名表里**没有 apk**
         —— 扩展名不在表里就算「是网页」,于是点击被 preventDefault()、交给前端路由,那里没有
         对应页面 → 渲染 404 页。而直接访问是真请求,所以两者表现不一致。
         `router.js` 留了两个逃逸口:`download` 与 `target`;用前者 —— `target="_blank"` 也能绕开,
         但会新开一个标签页再开始下载,手机上多一步。 -->
    <a class="gi-dl-btn" :href="DOWNLOAD_URL" download>下载安装包</a>
  </div>
</template>

<script setup lang="ts">
import { withBase } from 'vitepress'

/**
 * 包由**本站点自己发**,不是链 GitHub 的 release 下载链接。
 * 只提供正式版;应用仓库是私有的,那边的 release 匿名下不到。
 *
 * ## 为什么是同源路径
 *
 * 原来是 `https://github.com/adam-ikari/gaia/releases/latest/download/app-release.apk`,
 * 那条链要跳两次(github.com → release-assets.githubusercontent.com)。在 Titan 2 的
 * 浏览器上点它直接落到 Chrome 的「网页可能暂时无法连接,或者它已永久性地移动到
 * 新网址」错误页 —— 真机复现,同一台机器 curl 是 200。跨域两跳是手机浏览器最容易
 * 下载失败的形态。
 *
 * 现在这个文件由 Pages 自己发(deploy.yml 从本仓库 release 取来放进产物):
 * 同源、一次 200、零跳转。代价是没有版本号文件名。
 *
 * 用 `/media/gaia.apk` + `withBase` 而不是写死 `/gaia/media/gaia.apk`:base 已在
 * config.mts 里,写两处就多一个能忘的地方。**不过 `withBase` 不能省** —— 不加的话
 * 产物里就是 `/media/gaia.apk`,浏览器解析到域名根目录,404。
 *
 * 为什么页面上只有这一个链接、为什么不取 API 显示版本号 —— 见 brain `site-analytics`。
 */
const DOWNLOAD_URL = withBase('/media/gaia.apk')
</script>

<style scoped>
.gi-dl {
  margin: 0 auto;
  max-width: 560px;
}

.gi-dl-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  /* 满行:整站唯一真正的转化动作 */
  width: 100%;
  min-height: 52px;
  padding: 12px 20px;
  border-radius: 14px;
  background: var(--vp-c-brand-1);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
  text-decoration: none;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  transition: background-color 0.2s ease, transform 0.2s ease;
}

.gi-dl-btn:hover {
  background: #0077ed;
}

.gi-dl-btn:active {
  background: #0069d6;
  transform: scale(0.99);
}
</style>