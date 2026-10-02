<script setup lang="ts">
/**
 * 首页布局。内容全部来自 `index.md`：hero 的字段走 frontmatter，正文走 markdown，
 * 分区背景走 `::: band <底色>` 自定义容器。这里只负责外壳与排版。
 */
import { useData, withBase } from 'vitepress'
import { Content } from 'vitepress'
import { DOWNLOAD_FILENAME } from '../../download'

const { frontmatter } = useData()

type Action = { text: string; link: string; primary?: boolean; download?: boolean }

/**
 * `download` 属性对应 frontmatter 里的 `download: true`,**下载链接必须带它**。
 *
 * 少了会怎样(2026-10-02 真机报障「点按钮 404、直接访问同一个 URL 却能下」):
 * VitePress 拦截站内链接交给前端路由,判定函数 `treatAsHtml` 的已知扩展名表里没有
 * `apk`,扩展名不在表里就算「是网页」→ 点击被 preventDefault() → 前端路由里没有
 * 对应页面 → 渲染 404 页。直接访问走的是真请求,所以两者表现不一致。
 *
 * `router.js` 留了两个逃逸口:`download` 与 `target`。用前者;`target="_blank"`
 * 也能绕开,但会新开一个标签页再开始下载。
 *
 * 同一源 + `download` 浏览器才尊重,跨源会被忽略 —— 所以包必须由本站点自己发。
 */


/**
 * 站内链接要过 `withBase`,否则 `base: '/gaia/'` 不会作用到 frontmatter 里的
 * `actions[].link` —— 产物里会是 `/media/gaia.apk`,浏览器解析到域名根目录,
 * **404**(2026-10-02 差点就这么发出去)。
 *
 * `withBase` 对 `http…` 开头的绝对地址原样返回,所以以后想链外部地址也能用同一个字段。
 */

</script>

<template>
  <div class="gaia-home">
    <section class="gaia-hero">
      <p v-if="frontmatter.eyebrow" class="gaia-eyebrow">{{ frontmatter.eyebrow }}</p>
      <h1 class="gaia-hero-title">{{ frontmatter.title }}</h1>
      <p v-if="frontmatter.tagline" class="gaia-hero-sub">{{ frontmatter.tagline }}</p>
      <div v-if="frontmatter.actions?.length" class="gaia-hero-actions">
        <a
          v-for="a in frontmatter.actions as Action[]"
          :key="a.text"
          class="gaia-btn"
          :class="{ 'gaia-btn-primary': a.primary }"
          :href="withBase(a.link)"
          :download="a.download ? DOWNLOAD_FILENAME : undefined"
        >
          {{ a.text }}
        </a>
      </div>
      <p v-if="frontmatter.note" class="gaia-hero-note">{{ frontmatter.note }}</p>
    </section>

    <Content />
  </div>
</template>
