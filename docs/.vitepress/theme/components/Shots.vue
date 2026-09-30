<script setup lang="ts">
/**
 * 真机截图组。素材由应用仓库的 E2E 工作流产(模拟器跑的,不是 Titan 2)——
 * 页面里的说明照实说,不冒充真机。
 *
 * 截图文件不在时整块不渲染。
 */
type Shot = { file: string; caption: string }

const SHOTS: Shot[] = [
  { file: '/media/shots/01-idle.png', caption: '打开输入框。屏幕上就这两行。' },
  { file: '/media/shots/02-composing.png', caption: '打了一半,候选实时跟着变。' },
  { file: '/media/shots/03-committed.png', caption: '空格上屏,首选一直在正中间。' },
  { file: '/media/shots/04-five-key.png', caption: '五个候选分给五根手指。' },
  { file: '/media/shots/06-url-field.png', caption: '切到网址框,这行键换成了网址开头。' },
  { file: '/media/shots/07-number-field.png', caption: '数字框里只留下数字能打的键。' },
  { file: '/media/shots/08-settings.png', caption: '设置页:词库、上下滑距离、翻页方向都在这儿。' },
  { file: '/media/shots/09-licenses.png', caption: '用了哪些开源组件,应用里写得很清楚。' },
]
</script>

<template>
  <div class="gshots">
    <figure v-for="s in SHOTS" :key="s.file" class="gshot">
      <img :src="s.file" :alt="s.caption" loading="lazy" decoding="async" width="360" height="780" />
      <figcaption>{{ s.caption }}</figcaption>
    </figure>
  </div>
</template>

<style scoped>
.gshots {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 20px 16px;
  max-width: 1080px;
  margin: 0 auto;
}

.gshot {
  margin: 0;
}

.gshot img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 22px;
  border: 1px solid var(--vp-c-divider);
  background: var(--vp-c-bg-soft);
}

.gshot figcaption {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--vp-c-text-2);
}
</style>
