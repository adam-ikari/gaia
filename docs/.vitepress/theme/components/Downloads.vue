<template>
  <div class="gi-dl">
    <!--
      **`download` 属性不能省**(2026-10-02 真机报障:点按钮是 404 页,直接访问同一 URL 却能下载)。
      VitePress 拦截站内链接交给前端路由,`treatAsHtml` 的已知扩展名表里**没有 apk**
      —— 扩展名不在表里就算「是网页」,于是点击被 preventDefault() → 前端路由里没有
      对应页面 → **渲染 404 页**。直接访问是真请求,所以两者表现不一致。
      `router.js` 留了两个逃逸口:`download` 与 `target`;用前者(后者多开一个标签页)。
    -->
    <a
      class="gi-dl-btn"
      :class="{ 'gi-dl-btn-withdrawn': withdrawn }"
      :href="DOWNLOAD_URL"
      :download="DOWNLOAD_FILENAME"
    >{{ buttonLabel }}</a>

    <!--
      渠道名与状态。**三条渠道里正式版排在最前**,因为它是唯一被默认推荐的那个。

      `withdrawn` 时这个块**必须显示**,而且要说清为什么。
      不是为了合规 —— 用户下载的是**会装到手机上的字节**:
      把一个已知有缺陷的构建放在主 CTA 上却不说,比不给下载更糟。
      不给只是没得到;给了却得到坏的、还被告知这是好的。
    -->
    <p class="gi-dl-status">
      <span class="gi-tag">正式版</span>
      <span v-if="withdrawn" class="gi-tag gi-tag-bad">已撤回</span>
      <span v-else class="gi-tag gi-tag-ok">当前</span>
      <span v-if="versionLabel" class="gi-dl-ver">{{ versionLabel }}</span>
    </p>
    <p v-if="withdrawn && STABLE_NOTE" class="gi-dl-note">{{ STABLE_NOTE }}</p>
    <p v-if="withdrawn" class="gi-dl-note">
      下面的测试版与开发版含这个问题的修复，但同样没在真机上验证过。
    </p>
  </div>
</template>

<script setup lang="ts">
import { DOWNLOAD_URL, DOWNLOAD_FILENAME, STABLE_NOTE, STABLE_STATUS } from '../../download'

const withdrawn = STABLE_STATUS === 'withdrawn'

/**
 * 按钮文案要说清它是哪一版。
 *
 * 原来一律写「下载安装包」—— 那句话**不区分渠道**,于是把一个已撤回的构建
 * 和一个正常正式版说得一模一样。而用户判断「我拿到的是什么」只有这一个入口。
 */
const ver = (import.meta.env.VITE_APP_VERSION || '').trim().replace(/^v+/, '')
const buttonLabel = ver ? (withdrawn ? `下载 ${ver}(已撤回)` : `下载安装包 v${ver}`) : '下载安装包'

/** 版本号只用于显示:取不到就空着 —— 空着不误导,有值才可信。 */
const versionLabel = ver ? `v${ver}` : ''

/**
 * 包由**本站点自己发**,不是链 GitHub 的 release 下载链接。
 *
 * ## 为什么是同源路径
 *
 * 原来是 `releases/latest/download/app-release.apk`,那条链要跳两次
 * (github.com → release-assets.githubusercontent.com)。在 Titan 2 的浏览器上点它
 * 直接落到 Chrome 的「网页可能暂时无法连接…」错误页 —— 真机复现,同一台机器
 * curl 是 200。跨域两跳是手机浏览器最容易下载失败的形态。
 *
 * ## 状态从哪来
 *
 * `STABLE_STATUS` / `STABLE_NOTE` 是**构建期注入**的,理由见
 * `../../download.ts` 末尾:GitHub 上没有「这个版本是经流水线发的」这种标记,
 * 所以「这个包可不可信」**只有发布的人知道**,必须由发布的人写下来。
 */
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
</style>/*
 * 渠道标签与状态。
 *
 * 「正式版」三个渠道里排最前 —— 它是唯一被默认推荐的那个,所以它的名字要
 * 最先被看到;撤回标记紧跟其后,不能藏在版本号后面。
 */
.gi-dl-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 12px 0 0;
  font-size: 14px;
}

.gi-tag {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 980px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-2);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.7;
}

/* 撤回用警示色。选警示色而不是灰色:灰色读起来像「注脚」,
   而这一条必须读起来像「别装这个」。 */
.gi-tag-bad {
  background: rgba(255, 149, 0, 0.16);
  color: #b25000;
}

.dark .gi-tag-bad {
  background: rgba(255, 159, 10, 0.22);
  color: #ffb340;
}

.gi-tag-ok {
  background: rgba(52, 199, 89, 0.15);
  color: #1a7f37;
}

.dark .gi-tag-ok {
  background: rgba(46, 211, 102, 0.2);
  color: #4fd07a;
}

.gi-dl-ver {
  color: var(--vp-c-text-3);
  font-size: 13px;
}

.gi-dl-note {
  max-width: 30em;
  margin: 8px auto 0;
  color: var(--vp-c-text-2);
  font-size: 13px;
  line-height: 1.6;
  text-align: left;
}

/*
 * 撤回时按钮**降级**:不再用满宽实心主色。
 *
 * 它仍然是最大的可点区域(那是转化路径,不该消失),但视觉权重降下来 ——
 * 一个「已知有问题」的下载不该长得和「推荐下载」一模一样。
 */
.gi-dl-btn-withdrawn {
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-1);
  border: 1px solid var(--vp-c-divider);
}

.gi-dl-btn-withdrawn:hover {
  background: var(--vp-c-bg-alt);
}

.gi-dl-btn-withdrawn:active {
  background: var(--vp-c-bg-soft);
}

