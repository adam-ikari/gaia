<template>
  <div class="gi-dl">
    <a class="gi-dl-btn" :href="DOWNLOAD_URL">下载安装包</a>
  </div>
</template>

<script setup lang="ts">
/**
 * 包挂在公开的站点仓库上：应用仓库是私有的，那边的下载链接需要登录态。
 * 只提供正式版。
 *
 * 为什么是静态 URL、为什么不取 API、为什么页面上只有这一个链接 ——
 * 见 brain `site-analytics`。
 */
const REPO = 'adam-ikari/gaia'
const ASSET = 'app-release.apk'
const DOWNLOAD_URL = `https://github.com/${REPO}/releases/latest/download/${ASSET}`
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