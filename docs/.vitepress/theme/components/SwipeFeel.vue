<script setup lang="ts">
/**
 * 在键盘上滑的演示：手指在键盘表面上往上划，词跟着升起来。
 *
 * 区号由**手指落点的横向位置**决定，竖直位移只决定抬起多远算够 ——
 * 这跟真机一致。跟着手指走靠把指针位移直接写进 transform 的 Y 值：
 * 不缓动、不预测，加了就不跟手。划到头松手才上屏，没划到头弹回来。
 */
import { computed, onBeforeUnmount, ref } from 'vue'

/** 键盘五区，每区一个候选。区号 = 落点落在第几区，与候选栏的显示位一一对应。 */
const ZONES = ['拟好', '泥沼', '你', '逆好', '呢好']

/** 划多远算"够"，用来演示。真机上这个距离可以在设置里调。 */
const REACH = 74
const DEAD = REACH / 4

const dragging = ref(false)
const dragY = ref(0)
const flying = ref(false)
const committed = ref(false)
const startY = ref(0)
const startX = ref(0)
const zone = ref(2)

const lift = computed(() => (flying.value ? -150 : dragY.value))
/** 死区内的位移不启动拖动；过死区后进度从 0 重算 */
const progress = computed(() => {
  const d = -dragY.value
  if (d <= DEAD) return 0
  return Math.min(1, (d - DEAD) / (REACH - DEAD))
})
const past = computed(() => progress.value >= 1)

function zoneAt(clientX: number, el: HTMLElement) {
  const r = el.getBoundingClientRect()
  const f = (clientX - r.left) / r.width
  return Math.min(ZONES.length - 1, Math.max(0, Math.floor(f * ZONES.length)))
}

function onDown(e: PointerEvent) {
  if (flying.value) return
  const el = e.currentTarget as HTMLElement
  dragging.value = true
  committed.value = false
  startY.value = e.clientY
  startX.value = e.clientX
  zone.value = zoneAt(e.clientX, el)
  window.addEventListener('pointermove', onMove, { passive: false })
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

function onMove(e: PointerEvent) {
  if (!dragging.value) return
  e.preventDefault()
  dragY.value = e.clientY - startY.value
}

function onUp() {
  if (!dragging.value) return
  dragging.value = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)

  if (past.value) {
    flying.value = true
    committed.value = true
    window.setTimeout(() => {
      flying.value = false
      dragY.value = 0
      committed.value = false
    }, 620)
  } else {
    dragY.value = 0
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  window.removeEventListener('pointercancel', onUp)
})
</script>

<template>
  <div class="gsw">
    <div class="gsw-stage" :class="{ 'is-dragging': dragging, 'is-flying': flying }" @pointerdown="onDown">
      <!-- 跟手升起来的词：起点贴着键盘表面上沿 -->
      <div
        class="gsw-lift"
        :style="{
          transform: `translateY(${lift}px) scale(${1 + progress * 0.06})`,
          opacity: committed ? 0 : 1,
          textShadow: `0 ${6 + progress * 26}px ${12 + progress * 30}px rgba(0, 0, 0, ${0.18 + progress * 0.24})`,
        }"
      >
        {{ ZONES[zone] }}
      </div>

      <!-- 候选栏：五个词的显示位 -->
      <div class="gsw-cands">
        <span
          v-for="(c, i) in ZONES"
          :key="i"
          class="gsw-cand"
          :class="{ 'gsw-cand-on': i === zone }"
        >
          {{ c }}
        </span>
      </div>

      <!-- 键盘表面：手势真正发生的地方，纵向五等分 -->
      <div class="gsw-kbd">
        <div class="gsw-kbd-rows">
          <span v-for="i in 6" :key="'r1' + i" class="gsw-key">·</span>
        </div>
        <div class="gsw-kbd-rows">
          <span v-for="i in 5" :key="'r2' + i" class="gsw-key">·</span>
        </div>
        <div class="gsw-kbd-rows">
          <span v-for="i in 4" :key="'r3' + i" class="gsw-key gsw-key-space">空格</span>
        </div>
        <div class="gsw-zones" aria-hidden="true">
          <span v-for="(c, i) in ZONES" :key="'z' + i" class="gsw-zone" :class="{ 'is-on': i === zone }">
            {{ i + 1 }}
          </span>
        </div>
      </div>

      <div class="gsw-hint">
        <template v-if="committed">上屏了。</template>
        <template v-else-if="past">松手上屏。</template>
        <template v-else>在键盘上按住往上划。</template>
      </div>
    </div>

    <div class="gsw-threshold" role="presentation">
      <div class="gsw-threshold-fill" :style="{ width: `${progress * 100}%` }" />
    </div>
    <p class="gsw-note">
      手指落在哪一区，就选那个区的词；往上划多远，只决定什么时候上屏。词走多远全看手指走多远。
    </p>
  </div>
</template>

<style scoped>
.gsw {
  width: 100%;
}

.gsw-stage {
  position: relative;
  padding: 20px 18px 16px;
  border-radius: 26px;
  border: 1px solid var(--vp-c-divider);
  background: #0b0b0d;
  overflow: hidden;
  touch-action: none; /* 关键：让浏览器别把手势拿走 */
  cursor: grab;
  user-select: none;
}

.gsw-stage.is-dragging {
  cursor: grabbing;
}

.gsw-cands {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 7px;
  margin-top: 152px; /* 给升起来的词留出空间 */
}

.gsw-cand {
  padding: 10px 0;
  text-align: center;
  font-size: 14px;
  line-height: 1.3;
  color: #f5f5f7;
  background: #17171a;
  border-radius: 10px;
  transition: background-color 0.18s ease;
}

.gsw-cand-on {
  background: #1f3a68;
}

/* 跟手升起来的词：位置完全由 JS 的 transform 决定 */
.gsw-lift {
  position: absolute;
  left: 50%;
  top: 44px;
  margin-left: -34px;
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  font-size: 21px;
  font-weight: 600;
  color: #fff;
  background: var(--vp-c-brand-1);
  border-radius: 18px;
  pointer-events: none;
  will-change: transform;
  transition: transform 0.26s cubic-bezier(0.34, 1.3, 0.64, 1), opacity 0.3s ease;
}

.gsw-stage.is-dragging .gsw-lift,
.gsw-stage.is-flying .gsw-lift {
  transition: opacity 0.3s ease; /* 拖的时候绝不能有缓动，否则不跟手 */
}

.gsw-stage.is-flying .gsw-lift {
  transition: transform 0.42s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.42s ease;
}

/* ------------------------------------------------------------ 键盘表面 */

.gsw-kbd {
  position: relative;
  margin-top: 16px;
  padding: 12px 10px 10px;
  border-radius: 16px;
  background: #17171a;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.gsw-kbd-rows {
  display: flex;
  gap: 5px;
  margin-bottom: 5px;
}

.gsw-kbd-rows:last-of-type {
  margin-bottom: 0;
}

.gsw-key {
  flex: 1;
  padding: 9px 0;
  text-align: center;
  font-size: 12px;
  color: #8e8e93;
  background: #26262a;
  border-radius: 6px;
}

.gsw-key-space {
  flex: 3;
}

/* 纵向五区：区号 = 落点落在第几区 */
.gsw-zones {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-rows: repeat(5, 1fr);
  pointer-events: none;
}

.gsw-zone {
  display: grid;
  place-items: center;
  font-size: 11px;
  color: #48484a;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  transition: color 0.15s ease, background-color 0.15s ease;
}

.gsw-zone:first-child {
  border-top: 0;
}

.gsw-zone.is-on {
  color: var(--vp-c-brand-1);
  background: rgba(0, 113, 227, 0.1);
}

/* ------------------------------------------------------------ 其它 */

.gsw-hint {
  margin-top: 14px;
  font-size: 13px;
  color: #8e8e93;
  min-height: 1.4em;
}

.gsw-threshold {
  margin-top: 14px;
  height: 3px;
  border-radius: 2px;
  background: var(--vp-c-divider);
  overflow: hidden;
}

.gsw-threshold-fill {
  height: 100%;
  background: var(--vp-c-brand-1);
  transition: width 0.05s linear;
}

.gsw-note {
  margin: 14px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--vp-c-text-2);
}
</style>