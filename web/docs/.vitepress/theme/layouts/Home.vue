<script setup lang="ts">
/**
 * 首页布局。内容全部来自 `index.md`：hero 的字段走 frontmatter，正文走 markdown，
 * 分区背景走 `::: band <底色>` 自定义容器。这里只负责外壳与排版。
 *
 * 下载按钮直接放在 hero 里(2026-10-23),不再用 frontmatter 的单个动作按钮 ——
 * 两条渠道的版本、文件名、校验值都在 `Downloads.vue` 里,hero 只是它的位置。
 */
import { useData } from 'vitepress'
import { Content } from 'vitepress'
import Downloads from '../components/Downloads.vue'

const { frontmatter } = useData()
</script>

<template>
  <div class="gaia-home">
    <section class="gaia-hero">
      <p v-if="frontmatter.eyebrow" class="gaia-eyebrow">{{ frontmatter.eyebrow }}</p>
      <h1 class="gaia-hero-title">{{ frontmatter.title }}</h1>
      <p v-if="frontmatter.tagline" class="gaia-hero-sub">{{ frontmatter.tagline }}</p>
      <div class="gaia-hero-downloads">
        <Downloads />
      </div>
      <p v-if="frontmatter.note" class="gaia-hero-note">{{ frontmatter.note }}</p>
    </section>

    <Content />
  </div>
</template>
