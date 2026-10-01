<script setup lang="ts">
/**
 * 演示视频。
 *
 * 文件由应用仓库的 E2E 工作流产出(模拟器 + screenrecord),落到
 * `docs/public/media/` 之后这里才有东西可播。文件不在时整块不渲染 ——
 * 空着一个黑框比不放更糟。
 */
const src = `${import.meta.env.BASE_URL}media/shots/gaia-demo.mp4`
const poster = `${import.meta.env.BASE_URL}media/shots/gaia-demo-poster.png`
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
      从打开输入框到翻页的完整一段。自动测试跑下来的，没剪。
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
