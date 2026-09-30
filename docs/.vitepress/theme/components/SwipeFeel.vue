<script setup lang="ts">
/**
 * 上滑手感演示：词跟着手指走。
 *
 * 这是整个站点唯一"要动手"的模块 —— 手感说不清楚，只能自己拖。做法是直接用
 * transform 的 Y 值跟指针，所以是**逐像素跟手**，不做缓动、不做预测（加了就不跟手了）。
 * 滑到位松手才上屏；没滑到位就跟手弹回去。
 *
 * Pointer Events 一套代码同时管鼠标和手指；`touch-action: none` 防止浏览器抢走手势。
 * 拖拽期间给 window 挂 move/up 监听，指针滑出元素外也还能继续跟。
 */
import { computed, onBeforeUnmount, ref } from 'vue'

// 滑多远算"够"，用来演示。真机上这个距离可以在设置里调。
const REACH = 74
/** 松手后弹回原位的时长 */
const SPRING = 260

const dragging = ref(false)
const dragY = ref(0)
const flying = ref(false)
const committed = ref(false)
const startY = ref(0)
const zone = ref('')

const lift = computed(() => (flying.value ? -140 : dragY.value))
/** 拖得越远，词越"亮"、影子越淡，做出被拿起来的感觉 */
const progress = computed(() => Math.min(1, Math.max(0, -dragY.value) / REACH))
const past = computed(() => progress.value >= 1)

const ZONES = ['改', '称', '以', '后', '成']

function onDown(e: PointerEvent) {
  if (flying.value) return
  dragging.value = true
  committed.value = false
  startY.value = e.clientY
  window.addEventListener('pointermove', onMove, { passive: false })
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

function onMove(e: PointerEvent) {
  if (!dragging.value) return
  e.preventDefault()
  dragY.value = e.clientY - startY.value
  // 越往上，能选的候选越靠后（对应真机上的 5 区）
  const idx = Math.min(ZONES.length - 1, Math.max(0, Math.floor(progress.value * ZONES.length)))
  zone.value = ZONES[idx]
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
    // 弹回去
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
      <!-- 候选栏 -->
      <div class="gsw-cands">
        <span class="gsw-cand">呢好</span>
        <span class="gsw-cand">泥沼</span>
        <span class="gsw-cand gsw-cand-mid">你</span>
        <span class="gsw-cand">逆好</span>
        <span class="gsw-cand">拟好</span>
      </div>

      <!-- 跟手飞起来的词 -->
      <div
        class="gsw-lift"
        :style="{
          transform: `translateY(${lift}px) scale(${1 + progress * 0.06})`,
          opacity: committed ? 0 : 1,
          textShadow: `0 ${6 + progress * 26}px ${12 + progress * 30}px rgba(0, 0, 0, ${0.18 + progress * 0.24})`,
        }"
      >
        你
      </div>

      <!-- 区域提示 -->
      <div class="gsw-zones">
        <span v-for="(z, i) in ZONES" :key="z" class="gsw-zone" :class="{ 'is-on': zone === z && dragging }">
          {{ z }}
        </span>
      </div>

      <div class="gsw-hint">
        <template v-if="committed">上屏了。</template>
        <template v-else-if="past">松手上屏。</template>
        <template v-else>按住候选往上拖。</template>
      </div>
    </div>

    <!-- 进度刻度 -->
    <div class="gsw-threshold" role="presentation">
      <div class="gsw-threshold-fill" :style="{ width: `${progress * 100}%` }" />
    </div>
    <p class="gsw-note">
      词的位移就是这个指针的位移，一像素都不多。滑到位松手就上屏，没滑到位就弹回来。跟手的意思是：你没推到那个位置，它不替你决定去哪。
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
}

.gsw-cand {
  padding: 10px 0;
  text-align: center;
  font-size: 14px;
  color: #f5f5f7;
  background: #17171a;
  border-radius: 10px;
}

.gsw-cand-mid {
  background: #1f3a68;
}

/* 飞起来的词：位置完全由 JS 的 transform 决定 */
.gsw-lift {
  position: absolute;
  left: 50%;
  top: 46px;
  margin-left: -34px;
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  font-size: 30px;
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

.gsw-zones {
  margin-top: 22px;
  display: flex;
  justify-content: space-between;
}

.gsw-zone {
  font-size: 11px;
  color: #48484a;
  transition: color 0.15s ease;
}

.gsw-zone.is-on {
  color: var(--vp-c-brand-1);
}

.gsw-hint {
  margin-top: 14px;
  text-align: center;
  font-size: 13px;
  color: #8e8e93;
  min-height: 1.4em;
}

/* 进度 */
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
