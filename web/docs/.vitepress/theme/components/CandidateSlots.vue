<template>
  <figure class="gaia-slots">
    <!--
      五个槽的**归属**表。数字是「第几个候选」,底下是「哪把键选它」。

      依据:`ImeState.kt:336`(满 5 候选:4 2 1 3 5,槽 = Shift / Sym / 空格 / Ctrl / Alt,
      首选恒在中槽)与 `strings.xml` 的 `select_keys_summary`(同一句话,页面上写着暂不可改)。

      这里**不摆候选词**。摆一个「你好」进去就是编内容 —— 真的候选由引擎给,
      图能证明的只有位置与归属。
    -->
    <div class="sl-row">
      <span v-for="s in slots" :key="s.pos" class="sl-cell" :class="{ 'sl-center': s.center }">
        <b>{{ s.cand }}</b>
        <i>{{ s.key }}</i>
      </span>
    </div>
    <figcaption>从左到右五个槽。中间那格就是首选，空格上屏。</figcaption>
  </figure>
</template>

<script setup lang="ts">
const slots = [
  { pos: 1, cand: 4, key: 'Shift' },
  { pos: 2, cand: 2, key: 'Sym' },
  { pos: 3, cand: 1, key: '空格', center: true },
  { pos: 4, cand: 3, key: 'Ctrl' },
  { pos: 5, cand: 5, key: 'Alt' },
]
</script>

<style scoped>
.gaia-slots {
  margin: 0;
}

.sl-row {
  display: flex;
  gap: 8px;
}

.sl-cell {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 16px 2px;
  border: 1px solid var(--gaia-line);
  border-radius: 10px;
  background: var(--gaia-soft);
}

.sl-cell b {
  font-size: 22px;
  font-weight: 600;
  line-height: 1;
  color: var(--vp-c-text-1);
}

.sl-cell i {
  font-style: normal;
  font-size: 12px;
  color: var(--vp-c-text-2);
  white-space: nowrap;
}

/* 首选那一格是这一屏要说的事,给它一点重量 —— 但只改底色与描边,不加阴影。 */
.sl-center {
  border-color: var(--vp-c-brand-1);
  background: var(--gaia-brand-soft);
}

.sl-center i {
  color: var(--vp-c-brand-1);
  font-weight: 600;
}

figcaption {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
}
</style>
