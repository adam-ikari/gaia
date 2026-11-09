<template>
  <figure class="gaia-zonestrip">
    <!--
      上滑选字的两个维度,分开画。

      **横向五等分**:落点决定选哪个词。候选行与键盘面用**同一个宽度**各分五份,
      所以两行是一格对一格 —— 依据 `service/ImeService.kt` 的 `warnZoneBasisDrift`:
      候选行内起手按 `candidateBar.width / 5`,键盘面起手按 `屏宽 / 5`
      (`FlyDetector.zoneOf`),2026-10-10 把候选行改成通栏才让两者重合。

      **纵向位移**:只决定「划到哪儿才算数」,不决定选哪个。

      这条不变量是 `a1ee77f` 删掉旧演示件的原因 —— 当时把**表面**画成候选栏、
      把**分区**画成纵向格子,两处都错。这里没有手指、没有可拖的东西、没有动画:
      静态、二维,只标注这两个维度。格子里的数字是候选序号(左→右 4 2 1 3 5),
      与 `CandidateSlots` 同一份来源(`ImeState.kt:336`)。
    -->
    <div class="zs-row">
      <span class="zs-label">候选行</span>
      <div class="zs-strip">
        <span v-for="c in order" :key="'c' + c" class="zs-cell" :class="{ 'zs-first': c === 1 }">{{ c }}</span>
      </div>
    </div>
    <div class="zs-gap">
      <span class="zs-label"></span>
      <span class="zs-up">↑ 上滑</span>
    </div>
    <div class="zs-row">
      <span class="zs-label">键盘面</span>
      <div class="zs-strip">
        <span v-for="i in 5" :key="'z' + i" class="zs-cell zs-zone"></span>
      </div>
    </div>
    <figcaption>
      两行都是<strong>横向</strong>五等分，一格对一格：手指落在第几格，选的就是那一个词。
      往上划多远只决定划到哪儿算数，不参与选哪个。
    </figcaption>
  </figure>
</template>

<script setup lang="ts">
const order = [4, 2, 1, 3, 5]
</script>

<style scoped>
.gaia-zonestrip {
  margin: 0;
}

.zs-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.zs-label {
  flex: 0 0 44px;
  font-size: 12px;
  line-height: 1.25;
  color: var(--vp-c-text-2);
  white-space: nowrap;
}

.zs-strip {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  border: 1px solid var(--gaia-line);
  border-radius: 10px;
  overflow: hidden;
}

.zs-cell {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  font-size: 17px;
  font-weight: 600;
  color: var(--vp-c-text-1);
}

.zs-cell + .zs-cell {
  border-left: 1px solid var(--gaia-line);
}

.zs-first {
  background: var(--gaia-brand-soft);
  color: var(--vp-c-brand-1);
}

.zs-zone {
  background: var(--gaia-soft);
}

.zs-gap {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0 6px calc(44px + 10px);
}

.zs-up {
  font-size: 12px;
  color: var(--vp-c-text-3);
}

figcaption {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
}
</style>
