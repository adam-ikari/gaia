<template>
  <figure class="gaia-shot">
    <!-- `crop-*.png` 是 1440×96 的横条:这个产品没有屏幕键盘,屏幕上输入法只画一行。 -->
    <img :src="href" :alt="alt" loading="lazy" decoding="async" />
  </figure>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { withBase } from 'vitepress'

const props = defineProps<{
  /** `web/docs/public/img/` 下的文件名,不用写路径 —— 前缀与站点 base 由这里统一管。 */
  src: string
  alt: string
}>()

const href = computed(() => withBase(`/img/${props.src}`))
</script>

<style scoped>
.gaia-shot {
  margin: 0;
}

.gaia-shot img {
  display: block;
  width: 100%;
  height: auto;
  border: 1px solid var(--gaia-line);
  /* 圆角按**最矮的那张图**定:输入法行是 1440×96,摆到 720px 带上只有 48px 高,
     18px 的圆角会啃掉半边。方形的设置页图用同一个值不难看。 */
  border-radius: 10px;
  background: var(--gaia-soft);
}

.gaia-shot + .gaia-shot {
  margin-top: 14px;
}
</style>
