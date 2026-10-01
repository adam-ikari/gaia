<template>
  <!--
    下载按钮。**刻意没有 `<script>`** —— 这一页要做的只有一件事:给一个能按的下载入口。

    为什么曾经要用 API:早期版本从 `api.github.com` 取 latest release,再用返回的
    `browser_download_url` 拼链接。问题是未认证 REST 配额 60 次/小时、**按出口 IP 计**,
    同一 IP 后面的访客互相抢,403 是常态。而取不到时代码就不渲染按钮 ——
    整站唯一真正的转化动作在限流时整块消失,只留一句「无法连接 GitHub,请稍后刷新重试」。

    为什么不显示版本号 / 文件大小 / 下载次数:
    那些是**给我看的**,不是给访客看的。访客在下载按钮前只问三件事 ——
    能不能下(要一个能按的东西)、会不会下错版本(`latest` 由 GitHub 自己解析,不会错)、
    占多少流量(6.2MB,页面写明即可,不值得为它重新引入 API)。
    数字摆在按钮旁边只会让人多读一眼,还会因为限流时忽然消失而显得页面坏了。

    链接为什么能用:该路径由 GitHub 站点直接服务(302 到 release-assets),
    **不走 REST 配额**。文件名固定,`latest` 由 GitHub 解析 ⇒ 永远指向最新包。
    前提:目标 release 不是 draft / prerelease,否则 `latest` 不指向它。

    没有 JS ⇒ 没有加载态、没有闪烁、爬虫和无 JS 环境也能拿到链接。
  -->
  <div class="gi-dl">
    <a class="gi-dl-btn" :href="DOWNLOAD_URL">
      下载安装包
    </a>
    <p class="gi-dl-note">约 6 MB · Android 8.0 及以上</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 包挂在**公开的站点仓库**上:应用仓库 `adam-ikari/hwkbd_ime` 是私有的,
 * 那边的下载链接需要登录态,访客拿不到。
 *
 * 站点只提供正式版 —— 调试包不列在这里。
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
  /* 整站唯一真正的转化动作，撑满一行:文件名长、按错成本高，没有理由做小 */
  width: 100%;
  min-height: 52px;
  padding: 12px 20px;
  border-radius: 14px;
  background: var(--vp-c-brand-1);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
  text-decoration: none;
  /* 触摸设备上不要 300ms 延迟和系统高亮块 */
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

.gi-dl-note {
  margin: 12px 0 0;
  font-size: 14px;
  color: var(--vp-c-text-2);
  text-align: center;
}
</style>