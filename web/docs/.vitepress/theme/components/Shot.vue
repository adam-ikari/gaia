<template>
  <figure class="gaia-shot">
    <!--
      CI 采集的运行画面。**注脚必须留**:这张图拍自自动化测试跑的模拟器,
      屏幕按 Titan 2 的尺寸配(1440×1440 @320dpi),不是手机实拍。

      模拟器验不到真机密度、物理键盘、触摸时序(`brain: site-e2e-video`),
      所以图只证明「界面上有什么」,一律不许用来支撑手感类主张。
      出处:`.github/workflows/capture-media.yml` → `tools/aitest/ci-capture.sh`
      → `tools/aitest/shots.mjs` 的 `cropImeRow()`(裁屏幕底部那条 48dp 的输入法行)。

      所以 `crop-*.png` 是 **1440×96 的横条**:这个产品没有屏幕键盘,屏幕上输入法
      只画一行。横条按 `.gaia-band-narrow` 的 720px 摆就是真机上的大小(48dp);
      窄屏上它会缩成一条细线 —— 那是形状,不是内容读得清读不清的判据,
      具体说了什么由旁边那段话负责。
    -->
    <img :src="href" :alt="alt" loading="lazy" decoding="async" />
    <figcaption>{{ note || PROVENANCE }}</figcaption>
  </figure>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { withBase } from 'vitepress'

const PROVENANCE = '自动化测试在模拟器上拍的截图，屏幕按 Titan 2 的尺寸配置，不是手机实拍。'

const props = defineProps<{
  /** `web/docs/public/img/` 下的文件名,不用写路径 —— 前缀与站点 base 由这里统一管。 */
  src: string
  alt: string
  note?: string
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

.gaia-shot figcaption {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--vp-c-text-3);
}

.gaia-shot + .gaia-shot {
  margin-top: 14px;
}
</style>
