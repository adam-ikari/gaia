<script setup lang="ts">
/**
 * 层的概念,用立体叠加讲:三层键盘叠在一起,拖动把某一层抽到前面。
 *
 * 纯 CSS 3D(transform-style: preserve-3d) + 一点 JS 负责拖动。
 * 为什么用立体而不是并排三行:并排三行读起来是"三个并列的东西",
 * 叠起来才看得出它们是同一套键帽的三种含义 —— 换的是层,不是键。
 *
 * 交互:横向拖动换层(跟手,位移直接跟指针),也可以点层名直接跳。
 * 松手不动之后会自动慢慢轮播,除非用户碰过(prefers-reduced-motion 直接不动)。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

type Layer = { name: string; hint: string; rows: string[][] }

const LAYERS: Layer[] = [
  {
    name: '字母',
    hint: '什么都不按',
    rows: [
      ['Q', 'W', 'E', 'R', 'T', 'Y'],
      ['A', 'S', 'D', 'F', 'G', 'H'],
      ['J', 'K', 'L', '空格'],
    ],
  },
  {
    name: '符号',
    hint: '按住 Alt',
    rows: [
      ['1', '2', '3', '4', '5', '6'],
      ['!', '@', '#', '$', '%', '^'],
      ['[', ']', '\\', '空格'],
    ],
  },
  {
    name: '编程',
    hint: '按住 Sym',
    rows: [
      ['{', '}', '(', ')', '<', '>'],
      ['=', ':', ';', '|', '~', '`'],
      ['→', '=>', '//', '空格'],
    ],
  },
]

const active = ref(0)
const dragging = ref(false)
const touched = ref(false)
const offset = ref(0) // 跟手的实时位移
let startX = 0
let base = 0
let idleTimer: number | undefined

const spread = computed(() => {
  // 拖动时把位移换算成"层位移",松手吸附到最近的一层
  const v = base - offset.value / 90
  return v
})

function onDown(e: PointerEvent) {
  dragging.value = true
  touched.value = true
  startX = e.clientX
  base = active.value
  window.addEventListener('pointermove', onMove, { passive: false })
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

function onMove(e: PointerEvent) {
  if (!dragging.value) return
  e.preventDefault()
  offset.value = e.clientX - startX
}

function onUp() {
  if (!dragging.value) return
  dragging.value = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)

  const v = base - offset.value / 90
  active.value = Math.min(LAYERS.length - 1, Math.max(0, Math.round(v)))
  offset.value = 0
  scheduleIdle()
}

function go(i: number) {
  touched.value = true
  active.value = Math.min(LAYERS.length - 1, Math.max(0, i))
  scheduleIdle()
}

/** 用户碰过之后,停几秒恢复自动轮播 —— 没人操作时它自己慢慢转。 */
function scheduleIdle() {
  if (touched.value && idleTimer) window.clearTimeout(idleTimer)
  idleTimer = window.setInterval(() => {
    if (dragging.value || touched.value) return
    active.value = (active.value + 1) % LAYERS.length
  }, 2600) as unknown as number
}

const reduced = ref(false)
onMounted(() => {
  reduced.value = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!reduced.value) scheduleIdle()
})
onBeforeUnmount(() => {
  if (idleTimer) window.clearInterval(idleTimer)
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)
})
</script>

<template>
  <div class="kstack">
    <div class="kstack-hint">拖动换层</div>

    <div
      class="kstack-stage"
      :class="{ 'is-dragging': dragging }"
      @pointerdown="onDown"
    >
      <div class="kstack-world" :style="{ '--spread': spread }">
        <div
          v-for="(l, i) in LAYERS"
          :key="l.name"
          class="kplane"
          :class="{ 'is-front': Math.abs(i - spread) < 0.5 }"
          :style="{ '--i': i }"
        >
          <div class="kplane-name">
            <button type="button" @pointerdown.stop @click="go(i)">{{ l.name }}</button>
          </div>
          <div class="kplane-board">
            <div v-for="(row, r) in l.rows" :key="r" class="kplane-row" :class="{ 'is-last': r === 2 }">
              <span v-for="k in row" :key="k" class="kplane-key" :class="{ 'is-space': k === '空格' }">{{ k }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="kstack-meta">
      <p class="kstack-when">{{ LAYERS[active].hint }}</p>
      <p class="kstack-now">现在是：{{ LAYERS[active].name }}层</p>
    </div>
  </div>
</template>

<style scoped>
.kstack {
  width: 100%;
  max-width: 460px;
  margin: 0 auto;
  user-select: none;
}

.kstack-hint {
  text-align: center;
  font-size: 12px;
  color: var(--vp-c-text-3);
  margin-bottom: 10px;
}

/* 舞台:负责透视与倾斜 */
.kstack-stage {
  perspective: 1100px;
  perspective-origin: 50% 42%;
  height: 300px;
  cursor: grab;
  touch-action: none; /* 别让浏览器把手势拿走 */
}

.kstack-stage.is-dragging {
  cursor: grabbing;
}

.kstack-world {
  position: relative;
  height: 100%;
  transform-style: preserve-3d;
  transform: rotateX(56deg) rotateZ(-24deg);
  transition: transform 0.45s cubic-bezier(0.22, 1, 0.36, 1);
}

.kstack-stage.is-dragging .kstack-world {
  transition: none; /* 拖的时候不能有缓动,否则不跟手 */
}

/*
 * 三层:第 i 层往前挪 i 格、往上抬一点 —— 于是近的那层压在上面,
 * 层的"叠"就看得出来。每层各自 fade,不是靠透明度做深度。
 */
.kplane {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 300px;
  margin-left: -150px;
  transform-style: preserve-3d;
  transform:
    translate3d(0, calc((var(--spread) - var(--i)) * -22px), calc((var(--spread) - var(--i)) * 62px));
  opacity: 0.5;
  transition: transform 0.45s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.35s ease;
}

.kstack-stage.is-dragging .kplane {
  transition: opacity 0.35s ease;
}

.kplane.is-front {
  opacity: 1;
}

.kplane-name {
  position: absolute;
  left: -46px;
  top: 50%;
  transform: translateY(-50%);
}

.kplane-name button {
  border: 0;
  background: none;
  padding: 6px 8px;
  font-size: 13px;
  color: var(--vp-c-text-2);
  cursor: pointer;
  border-radius: 999px;
}

.kplane.is-front .kplane-name button {
  color: var(--vp-c-brand-1);
  font-weight: 600;
}

.kplane-board {
  padding: 10px;
  border-radius: 16px;
  background: #17171a;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.45);
}

.kplane.is-front .kplane-board {
  border-color: var(--vp-c-brand-1);
}

.kplane-row {
  display: flex;
  gap: 5px;
  margin-bottom: 5px;
}

.kplane-row:last-child {
  margin-bottom: 0;
}

.kplane-key {
  flex: 1;
  text-align: center;
  font-size: 12px;
  padding: 7px 0;
  border-radius: 6px;
  background: #26262a;
  color: #f5f5f7;
}

.kplane-key.is-space {
  flex: 2.4;
  color: #8e8e93;
}

/* 当前层的说明 */
.kstack-meta {
  margin-top: 14px;
  text-align: center;
}

.kstack-when {
  margin: 0;
  font-size: 15px;
  color: var(--vp-c-text-1);
}

.kstack-now {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--vp-c-text-2);
}

@media (prefers-reduced-motion: reduce) {
  .kstack-world,
  .kplane {
    transition: none;
  }
}
</style>
