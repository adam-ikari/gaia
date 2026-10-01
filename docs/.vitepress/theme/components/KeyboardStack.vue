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

    <!--
      窄屏专用的层名按钮行。宽屏用板子左侧那个层名（.kplane-name），位置贴着板子、
      好看，但它是**绝对定位**的：320px 上板子左边只剩 28px，放不下两个汉字的按钮，
      实测「字母」被左边缘裁掉 35px —— 一半字都看不见。
      与其在窄屏把板子缩到 180px（每键只剩 30px，按不准），不如把层名挪到板子下面
      横排：占满整行宽度，命中区域也是整块，而不是 4px 的碎片。
      宽屏 display:none，不影响原来的排法。
    -->
    <div class="kstack-tabs">
      <button
        v-for="(l, i) in LAYERS"
        :key="l.name"
        type="button"
        class="kstack-tab"
        :class="{ 'is-on': i === active }"
        @click="go(i)"
      >
        {{ l.name }}
      </button>
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

  /*
   * 必须裁:倾斜 + 透视之后,.kplane 的**投影外接框**比它的布局框宽得多
   * (300px 的板转 -24° 再带透视,量出来 340–351px),默认 overflow:visible
   * 就让这个外接框溢出到页面外,把整页的横向宽度撑大。
   * 移动端撑大横向宽度 = 布局视口跟着变宽(device-width 视作最小宽度),
   * 整站于是按更宽的视口排版 —— 手机上不是"某个组件歪了",是**全站都缩小了**。
   *
   * 用 clip 而不是 hidden:clip 不建立滚动容器,不会影响 sticky/absolute 的定位基准;
   * hidden 会。旧的 hidden 留在前面兜底。
   */
  overflow: hidden;
  overflow: clip;
}

.kstack-stage.is-dragging {
  cursor: grabbing;
}

.kstack-world {
  position: relative;
  height: 100%;
  transform-style: preserve-3d;
  /*
   * transform 链从**右往左**应用,所以 rotateX/rotateZ 之后再 translate,
   * 是沿倾斜平面的位移 —— 不是我们要的屏幕竖直方向。
   *
   * 两个 -116 是量出来的,不是估的:
   *   translateY:板子摆在 top:50%,rotateX(56deg) + z 位移把它投影到屏幕上时
   *              整体下坠约 116px ⇒ 上面空 116px、下面被裁 117px。
   *   translateX:同一个投影顺带把整块往右带约 27px(375px 与 320px 两档实测
   *              一致,与板宽无关),所以在窄屏上右边缘会被切掉一条。
   * 修之前先量 gapAbove / stage.left - planes.left,别照抄数字。
   */
  transform: translate(-27px, -116px) rotateX(56deg) rotateZ(-24deg);
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

/*
 * 触控设备：层名撑到 44px 以上。
 *
 * 用 hover:none 而不是 max-width：鼠标设备上 31px 完全够点，没必要撑大视觉。
 */
@media (hover: none) {
  .kplane-name button {
    /*
     * 64 是量出来的，不是拍的。层名在 rotateX 的 3D 空间里，屏幕上的高度会被透视压扁：
     *   本地 44px → 屏幕 33–38px
     *   本地 60px → 屏幕 43px（375/412 两档实测，还差 1px）
     * 压缩比约 0.72，所以本地要留到 44 / 0.72 ≈ 61，取 64 留余量。
     *
     * 改这个值之后请量 `getBoundingClientRect().height`，**别看 CSS 里的数** ——
     * CSS 里写 44 只会得到 31–33px，这个坑过一次。
     */
    min-height: 64px;
    padding: 0 10px;
  }
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
  /* 不低于 12px:再小就不是"字"了,手机上会糊成一团 */
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

/* 窄屏的层名按钮行：默认不渲染，宽屏看不见 */
.kstack-tabs {
  display: none;
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

/* ---------------------------------------------------------------- 窄屏 */
@media (max-width: 560px) {
  /*
   * 板宽 300px + 转 -24°，在 375px 手机上投影外接框（340px+）比舞台（327px）还宽，
   * 光靠 clip 会被切掉边角 —— 所以窄屏把板子和倾角一起收小，让它留在舞台里。
   * 倾角要跟着板宽走：板子窄了还转 24° 的话，投影外接框照样比舞台宽。
   */
  .kstack-stage {
    /* 窄屏这块板子的投影高约 271px，舞台给到 276px，配合上面那个 -116px 的上移正好装下 */
    height: 276px;
  }

  .kstack-world {
    /* 倾角比宽屏收小（54°/-15° vs 56°/-24°），跟着板宽走 */
    transform: translate(-27px, -116px) rotateX(54deg) rotateZ(-15deg);
  }

  .kplane {
    width: 248px;
    margin-left: -124px;
  }

  /* 层名往里收:键帽窄了(248px),原来的 -46px 会让它顶到舞台右边缘外面 */
  .kplane-name {
    left: -52px;
  }
}

/*
 * 320px 档：再窄一档。板子、舞台一起收，否则层名会被左边缘吃掉
 * （量过：320px 上前排层名左侧 -9px，裁掉 33px，「字母」只剩半个）。
 */
@media (max-width: 400px) {
  .kstack-stage {
    height: 252px;
  }

  .kplane {
    width: 216px;
    margin-left: -108px;
  }

  /*
   * 层名从板子左侧挪到板子下方横排。板子左侧那份 .kplane-name 收掉，
   * 免得两个入口叠在一起、还各被裁一半。
   */
  .kplane-name {
    display: none;
  }

  .kstack-tabs {
    display: flex;
    gap: 8px;
    margin-top: 12px;
  }

  .kstack-tab {
    flex: 1;
    /* 触控命中区域：这个 demo 只有这三个按钮能点，按不准就是整个演示废了 */
    min-height: 44px;
    border: 1px solid var(--vp-c-divider);
    border-radius: 12px;
    background: transparent;
    color: var(--vp-c-text-2);
    font-size: 14px;
    font-family: var(--vp-font-family-base);
    cursor: pointer;
    transition: color 0.2s ease, border-color 0.2s ease, background-color 0.2s ease;
  }

  .kstack-tab.is-on {
    color: var(--vp-c-brand-1);
    border-color: var(--vp-c-brand-1);
    font-weight: 600;
  }
}
</style>
