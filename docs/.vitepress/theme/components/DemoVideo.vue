<script setup lang="ts">
/**
 * 演示视频。
 *
 * 文件由应用仓库的 E2E 工作流产出,落到 `docs/public/media/` 之后才有东西可播。
 *
 * 这里**不检查文件在不在**:VitePress 是静态站点,构建时看不到 `public/` 下的运行时
 * 文件,任何"存在性判断"都得靠 fetch,而 fetch 就会带回加载态、失败态,以及
 * "文件在但播不了"这种最难看的状态。素材是否就位由采集端保证 —— 采集脚本会验
 * mp4 能不能解,验不过就不产出文件、并在 manifest 里写 `video:false`。
 * 见 brain `site-e2e-video`。
 */
import { ref } from 'vue'
const src = `${import.meta.env.BASE_URL}media/shots/gaia-demo.mp4`
const poster = `${import.meta.env.BASE_URL}media/shots/gaia-demo-poster.png`

/**
 * 自动播放要照顾一个例外:系统设了「减少动效」的用户。
 * 这段是录屏,内容自己在动,自动播对他们就是一段不受控的动画。
 *
 * 注意 `autoplay` 只能这样关掉 —— CSS 管不到 DOM 属性,而按用户偏好播/停的
 * 惯例是尊重 `prefers-reduced-motion`。
 */
const autoplay = ref(
  typeof window === 'undefined' ||
    !window.matchMedia('(prefers-reduced-motion: reduce)').matches
)
</script>

<template>
  <!--
    `controls` + `muted` 同时写:浏览器规定只有静音的 video 才允许自动播放,
    所以这段是自动播放的,controls 是给人一个"停下/看进度"的出口。
    `playsinline` 是 iOS Safari 不接管全屏的前提;Titan 2 是 Android,
    但这条加上没有成本,少了在 iPhone 上会直接进全屏播放器,跳站。
  -->
  <div class="gaia-video">
    <video
      :src="src"
      :poster="poster"
      :autoplay="autoplay"
      controls
      muted
      loop
      playsinline
      preload="metadata"
      width="1440"
      height="1440"
    />
    <p class="gaia-video-cap">
      从打开输入框到翻页的完整一段，没剪。
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
  /* 正方形,和截图同一套版式 */
  max-width: 360px;
  aspect-ratio: 1 / 1;
  border-radius: 28px;
  border: 1px solid var(--vp-c-divider);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.14);
  background: #000;
}

.gaia-video-cap {
  max-width: 360px;
  margin: 16px auto 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
  text-align: left;
}

/* 用户要求减少动效:这段视频是录屏,内容本身会动,不该再自己播。
   controls 留着,手动点播放不受影响。 */
/*
 * 减少动效的用户:不自动播。
 * 这里**不能只靠 CSS** —— `autoplay` 是 DOM 属性,CSS 管不着。
 * 所以真正的开关在 script 里(见 prefersReducedMotion),这条样式只去掉按压反馈。
 */
@media (prefers-reduced-motion: reduce) {
  .gaia-video video {
    pointer-events: auto;
  }
}
</style>
