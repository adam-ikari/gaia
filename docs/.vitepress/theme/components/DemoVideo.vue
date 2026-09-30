<script setup lang="ts">
/**
 * 演示视频。
 *
 * 文件由应用仓库的 E2E 工作流产出(模拟器 + screenrecord),落到
 * `docs/public/media/` 之后这里才有东西可播。文件不在时整块不渲染 ——
 * 空着一个黑框比不放更糟。
 */
const src = '/media/gaia-demo.mp4'
const poster = '/media/gaia-demo.jpg'
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
      从打开输入框到上屏的完整一段。录屏来自 CI 的自动测试，跑的就是发布包，没剪。
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

@media (prefers-reduced-motion: reduce) {
  .gaia-video video {
    /* 用户要求减少动效:不自动播放,交给手动点 */
    pointer-events: auto;
  }
}
</style>
