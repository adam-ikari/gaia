<template>
  <!--
    截图叠进 Titan 2 的机身图里，整张铺满屏幕区。

    机身图是 Unihertz 官网产品图的裁切：屏幕那一块在图片里挖成透明孔，
    真正的画面由下面的 <img> 垫在机身图下面。换截图只换 src，不用重做图片。

    屏幕区域在机身图里的位置按像素量出来再写成百分比：
    机身图 464×550，屏幕 (22,79) 起、420×420 —— 即 left 4.74% / top 14.36% /
    width 90.52% / height 76.36%。改机身图必须同时改这四个数。
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
const shellHref = computed(() => withBase('/img/titan2-shell.png'))
</script>

<style scoped>
.gaia-shot {
  margin: 0;
  min-width: 0;
}

.gaia-shot-stage {
  position: relative;
  width: 100%;
  /* 一台机器在页面里不该和正文抢宽度：之前的 620px 让整屏图比标题还显眼。 */
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
