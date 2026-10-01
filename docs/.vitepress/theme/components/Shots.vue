<script setup lang="ts">
/**
 * 截图组。
 *
 * **列表不写死在这里**,读采集脚本产出的 `manifest.json`
 * (应用仓库 `tools/aitest/shots.mjs` 一次跑出来,截图与文案同源)。
 * 之前这里是手写的一张表,结果采集脚本一改步骤名(04-five-key → 04-page-swipe),
 * 页面就跟着对不上,而且没人发现 —— 图断了两张,文案还写着已经没在演示的东西。
 *
 * 素材由应用仓库的 E2E 工作流产,**跑的是模拟器,不是 Titan 2**:
 * 分辨率、键盘、insets 都跟真机不同。页面里的说明照实说,不冒充真机。
 *
 * 素材没就位时整块不渲染 —— 空着一排灰框比不放更糟。
 */
import { onMounted, ref } from 'vue'

type Shot = { file: string; caption: string; warn?: string }
const shots = ref<Shot[]>([])
const loaded = ref(false)
// base 要在 script 里取:Vue 模板表达式里不允许直接写 import.meta(编译期报
// "import.meta may appear only with 'sourceType: module'")
const base = import.meta.env.BASE_URL

onMounted(async () => {
  try {
    const r = await fetch(`${base}media/shots/manifest.json`)
    if (!r.ok) return
    shots.value = (await r.json()).shots ?? []
  } catch {
    /* 没素材就当没有,不留半个空框 */
  } finally {
    loaded.value = true
  }
})
</script>

<template>
  <div v-if="loaded && shots.length" class="gshots">
    <figure v-for="s in shots" :key="s.file" class="gshot">
      <!-- width/height 是给浏览器占位的比例,写错会先按错的框排版再跳一下。
           Titan 2 是正方形 1440×1440,所以这里也必须是正方形。 -->
      <img :src="`${base}media/shots/${s.file}`" :alt="s.caption" loading="lazy" decoding="async" width="1440" height="1440" />
      <figcaption>{{ s.caption }}</figcaption>
    </figure>
  </div>
</template>

<style scoped>
.gshots {
  display: grid;
  /* 正方形比长条高,格子给宽一点,一行才排得下四五张 */
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
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
  /* 正方形屏的圆角比长条屏大一些,免得四角显得方 */
  border-radius: 26px;
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
