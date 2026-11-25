<template>
  <!--
    截图叠进 Titan 2 机身图（Unihertz 官网产品图裁切，屏幕区透明）。
    屏幕占机身图的 left 4.74% / top 14.36% / width 90.52% / height 76.36%，
    换机身图时这四个数要跟着改。
  -->
  <figure class="gaia-shot">
    <div class="gaia-shot-stage">
      <img class="gaia-shot-screen" :src="href" :alt="alt" loading="lazy" decoding="async" />
      <img class="gaia-shot-shell" :src="shellHref" alt="" aria-hidden="true" />
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { withBase } from 'vitepress'

const props = defineProps<{
  /** `web/docs/public/img/` 下的文件名，不用写路径 —— 前缀与站点 base 由这里统一管。 */
  src: string
  alt: string
}>()

const href = computed(() => withBase(`/img/${props.src}`))
const shellHref = computed(() => withBase('/img/titan2-shell.webp'))
</script>

<style scoped>
.gaia-shot {
  margin: 0;
  min-width: 0;
}

.gaia-shot-stage {
  position: relative;
  width: 100%;
  max-width: 360px;
  margin: 0 auto;
  aspect-ratio: 464 / 550;
}

.gaia-shot-shell {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  filter: drop-shadow(0 10px 20px rgba(0, 0, 0, 0.14));
}

.gaia-shot-screen {
  position: absolute;
  left: 4.74%;
  top: 14.36%;
  width: 90.52%;
  height: 76.36%;
  display: block;
  object-fit: cover;
}

.gaia-shot + .gaia-shot {
  margin-top: 14px;
}
</style>
