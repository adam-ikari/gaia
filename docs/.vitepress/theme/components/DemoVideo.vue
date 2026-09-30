<script setup lang="ts">
/**
 * 演示视频。视频文件由应用仓库的 e2e 流水线产出后放进 docs/public/media/。
 * 文件不存在时（还没录、或换了路径）就整块不渲染，不留空框。
 */
const src = '/media/cover.mp4'
const poster = '/media/cover-poster.png'
</script>

<template>
  <div class="gaia-video">
    <video
      :src="src"
      :poster="poster"
      controls
      muted
      loop
      playsinline
      preload="metadata"
      width="360"
      height="780"
    />
    <p class="gaia-video-cap">
      真机形态下的完整流程：组词、五键选字、上滑飞字、L4 虚拟按键。录屏来自 CI 端到端测试，
      未经剪辑。
    </p>
  </div>
</template>

<style scoped>
.gaia-video {
  margin: 32px auto;
  text-align: center;
}

.gaia-video video {
  display: block;
  margin: 0 auto;
  width: 100%;
  max-width: 320px;
  border-radius: 32px;
  border: 1px solid var(--vp-c-divider);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.14);
  background: #000;
}

.gaia-video-cap {
  max-width: 320px;
  margin: 16px auto 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
  text-align: left;
}
</style>
