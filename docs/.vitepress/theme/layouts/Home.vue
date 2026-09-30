<script setup lang="ts">
/**
 * 首页布局。内容全部来自 `index.md`：hero 的字段走 frontmatter，正文走 markdown，
 * 分区背景走 `::: band <底色>` 自定义容器。这里只负责外壳与排版。
 */
import { useData } from 'vitepress'
import { Content } from 'vitepress'

const { frontmatter } = useData()

type Action = { text: string; link: string; primary?: boolean }
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
          :href="a.link"
        >
          {{ a.text }}
        </a>
      </div>
      <p v-if="frontmatter.note" class="gaia-hero-note">{{ frontmatter.note }}</p>
    </section>

    <Content />
  </div>
</template>
