<template>
  <div class="gi-alt">
    <p class="gi-alt-lead">
      正式版在上面那个按钮。下面两个是<strong>另外装的两个应用</strong>，
      跟正式版<strong>并存、互不覆盖</strong>，数据和设置各自分开 ——
      所以你可以一边用着正式版，一边试新的，出问题随时切回去。
    </p>
    <p class="gi-alt-chips">
      <span class="gi-chip">盖亚输入法</span>
      <span class="gi-chip">盖亚输入法 β</span>
      <span class="gi-chip">盖亚输入法 dev</span>
    </p>

    <!--
      **`v-if` 而不是永远渲染**:渠道没上线时按钮**整个不出现**。

      为什么不在页面上去探测 `/media/gaia-beta.apk` 存不存在:站点是纯静态,
      没有「运行时」可探测;而一个指向 404 的按钮比没有按钮更糟 ——
      它让人以为「点不动是我网络的问题」。
      ⇒ 可用性由**构建期 flag** 决定(`deploy.yml` 取到了才给 1),
         见 `../../download.ts`。
    -->
    <p class="gi-alt-btns">
      <a
        v-if="HAS_BETA"
        class="gi-alt-btn gi-alt-btn-beta"
        :href="BETA_DOWNLOAD_URL"
        :download="BETA_FILENAME"
      >测试版 β</a>
      <a
        v-if="HAS_DEV"
        class="gi-alt-btn gi-alt-btn-dev"
        :href="DEV_DOWNLOAD_URL"
        :download="DEV_FILENAME"
      >开发版 dev</a>
    </p>

    <p v-if="HAS_DEV" class="gi-alt-note">
      开发版是 master 的最新构建，<strong>不做任何保证</strong>：不过混淆、
      没跑过端到端测试，可能有还没修的问题。
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * 三条渠道里**只有正式版**是主 CTA(首屏那个 `Downloads.vue` 按钮),
 * 测试版与开发版在这里。
 *
 * ## 为什么不在首屏放三个按钮
 *
 * 首屏只有一个视觉重心。三个并列的实心按钮会把「下载正式版」这个
 * 唯一的主行动稀释掉 —— 而测试版/开发版是**自己找**的入口,
 * 它们的出现不该让人以为「这才是该下的那个」。
 * ⇒ 放在第一个 band 之后、往下滚动才看到的位置,与它们的使用频率相符。
 *
 * ## URL 与文件名都从 `../../download` 取,不在这儿写死
 *
 * 两个具体后果,都是这个站踩过的:
 *
 * 1. **base 会漏。** 直接写 `href="/media/gaia-beta.apk"` 渲染出来是
 *    `/media/gaia-beta.apk`,而站点挂在 `/gaia` 下,真实路径是
 *    `/gaia/media/gaia-beta.apk` ⇒ **404**。`download.ts` 里的 `withBase`
 *    就是为这件事存在的。首屏那个按钮踩过一次,这里不再踩第二次。
 * 2. **`download` 不给文件名会丢身份。** 只写 `download` 的话,浏览器用
 *    URL 最后一段当文件名,用户存下来的是 `gaia-beta.apk` ——
 *    而「这是哪个渠道哪个 commit」是用户唯一能拿回来问的东西。
 *
 * ## `download` 属性不能省(2026-10-02 真机报障)
 *
 * 少了它,点击会被 VitePress 的 router 接管成前端路由:`treatAsHtml` 的
 * 已知扩展名表里**没有 apk**,扩展名不在表里就算「是网页」→ preventDefault
 * → 前端路由里没有这个页面 → **渲染 404 页**。直接访问同一 URL 却能下,
 * 因为那是真请求。`router.js` 留了 `download` 与 `target` 两个逃逸口,
 * 用前者 —— `target="_blank"` 也能绕,但手机上多开一个标签页。
 */
import {
  BETA_DOWNLOAD_URL,
  BETA_FILENAME,
  DEV_DOWNLOAD_URL,
  DEV_FILENAME,
  HAS_BETA,
  HAS_DEV,
} from '../../download'
</script>

<style scoped>
.gi-alt-lead {
  color: var(--vp-c-text-2);
}

.gi-alt-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0 0;
}

.gi-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-2);
  font-size: 13px;
  white-space: nowrap;
}

.gi-alt-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 20px 0 0;
}

/*
 * **刻意比首屏主 CTA 弱**:不是实心主色,是描边。
 * 理由见 script 注释 —— 它们的出现不该让人以为「这才是该下的那个」。
 * 但描边用品牌色,所以仍认得出一等公民,不是脚注。
 */
.gi-alt-btn {
  display: inline-flex;
  align-items: center;
  padding: 8px 18px;
  border: 1px solid var(--vp-c-brand-1);
  border-radius: 980px;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  color: var(--vp-c-brand-1);
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.gi-alt-btn:hover {
  background: var(--vp-c-brand-1);
  color: var(--vp-c-bg, #fff);
}

/* 暗色下描边按钮的文字色要换,否则深底 + 深字 = 看不见 */
.dark .gi-alt-btn {
  color: var(--vp-c-brand-1);
}

.dark .gi-alt-btn:hover {
  color: #fff;
}

/*
 * 开发版再叠一层**虚线**描边:它在语义上是另一个性质的东西
 * ——「不做任何保证」。三个并列的实心按钮会让人以为三者等价,
 * 虚线是不写字就能传达这件事的唯一手段。
 */
.gi-alt-btn-dev {
  border-style: dashed;
}

.gi-alt-note {
  margin-top: 12px;
  font-size: 14px;
  color: var(--vp-c-text-2);
}
</style>
