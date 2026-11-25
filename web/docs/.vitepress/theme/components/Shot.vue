<template>
  <!--
    截图不再单独摆一行，而是叠进 Titan 2 的机身图里。

    机身图是 Unihertz 官网产品图的裁切：屏幕那一块在图片里挖成透明孔，
    真正的画面由下面的 <img> 垫在机身图下面。换截图只换 src，不用重做图片。

    屏幕区域在机身图里的位置按像素量出来再写成百分比：
    机身图 464×550，屏幕 (22,79) 起、420×420 —— 即 left 4.74% / top 14.36% /
    width 90.52% / height 76.36%。改机身图必须同时改这四个数。
  -->
  <figure class="gaia-shot">
    <div class="gaia-shot-stage">
      <div class="gaia-shot-screen" :class="{ 'is-row': isRow }">
        <img :src="href" :alt="alt" loading="lazy" decoding="async" />
      </div>
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
/** `crop-*` 是 1440×96 的输入法行，贴在屏幕底部；其余是 1440×1440 整屏。 */
const isRow = computed(() => props.src.startsWith('crop-'))
</script>

<style scoped>
.gaia-shot {
  margin: 0;
}

.gaia-shot-stage {
  position: relative;
  width: 100%;
  max-width: 620px;
  margin: 0 auto;
  aspect-ratio: 464 / 550;
}

.gaia-shot-shell {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  filter: drop-shadow(0 12px 26px rgba(0, 0, 0, 0.16));
}

.gaia-shot-screen {
  position: absolute;
  left: 4.74%;
  top: 14.36%;
  width: 90.52%;
  height: 76.36%;
  overflow: hidden;
  background: #000;
}

.gaia-shot-screen img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 输入法只画屏幕最底下那一行；上面保持黑底，不在图里凭空造 UI。 */
.gaia-shot-screen.is-row img {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  height: auto;
  object-fit: fill;
}

.gaia-shot + .gaia-shot {
  margin-top: 14px;
}
</style>
