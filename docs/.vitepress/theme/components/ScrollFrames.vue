<script setup lang="ts">
/**
 * 滚动驱动的逐帧动画。滚过这一段，帧号就往前推一格 —— 不是播放时间轴，是跟着手指走。
 *
 * 实现要点:
 * - 外层留出 320vh 的滚动行程，内部 sticky 吸住；
 * - scroll 事件只写一个标志位，真正的计算放进 requestAnimationFrame（否则每帧都读
 *   getBoundingClientRect 会强制重排）；
 * - 帧号用 Math.floor 量化，所以是"逐帧"跳变，不是连续插值；
 * - prefers-reduced-motion 时不挂监听，直接停在最后一帧（信息完整，只是不会动）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

type Frame = {
  /** 这一帧在讲什么 */
  t: string
  /** 输入串 */
  line: string
  /** 五个候选槽，空串表示空槽 */
  cands: string[]
  /** 高亮的候选槽序号（0 起） */
  hi?: number
  /** 正在按下的键 */
  key?: 'letter' | 'space' | 'shift'
  /** 大字提示 */
  hint: string
}

const FRAMES: Frame[] = [
  { t: '打开输入框', line: '', cands: ['', '', '', '', ''], hint: '盖亚输入法接手了。屏幕只亮起候选和状态。' },
  { t: '第一个字母', line: 'n', cands: ['', '', '', '', ''], key: 'letter', hint: '开始打字。' },
  { t: '第二个', line: 'ni', cands: ['', '', '', '', ''], key: 'letter', hint: '不用看键盘。' },
  { t: '第三个', line: 'nih', cands: ['', '', '', '', ''], key: 'letter', hint: '屏幕安静地记着。' },
  { t: '第四个', line: 'niha', cands: ['', '', '', '', ''], key: 'letter', hint: '手指不用离开原来的位置。' },
  { t: '念出来', line: 'nihao', cands: ['呢好', '泥沼', '拟好', '你', '逆好'], key: 'letter', hint: '候选出现了。' },
  {
    t: '首选居中',
    line: 'nihao',
    cands: ['呢好', '泥沼', '拟好', '你', '逆好'],
    hi: 3,
    hint: '你最想要的那个，落在正中间。',
  },
  {
    t: '按空格',
    line: 'nihao',
    cands: ['呢好', '泥沼', '拟好', '你', '逆好'],
    hi: 3,
    key: 'space',
    hint: '空格就是首选。一按就上屏。',
  },
  { t: '上屏', line: '你好', cands: ['', '', '', '', ''], hint: '一次按键，一句话。' },
  {
    t: '接着打',
    line: '你好 你',
    cands: ['呢好', '泥沼', '拟好', '你', '逆好'],
    hint: '五个候选，对应五根手指。',
  },
  {
    t: 'Shift 取第二个',
    line: '你好 你',
    cands: ['呢好', '泥沼', '拟好', '你', '逆好'],
    key: 'shift',
    hi: 0,
    hint: 'Shift 选第二个。抬手之前就选好了。',
  },
  {
    t: '也可以滑',
    line: '你好 什么',
    cands: ['', '', '', '', ''],
    hint: '在键盘表面滑过去，一样能选。',
  },
]

const wrap = ref<HTMLElement | null>(null)
const stage = ref(0)

let ticking = false
let io: IntersectionObserver | null = null
let reduced = false

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    ticking = false
    const el = wrap.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    // 吸顶区间 = 元素高度 - 一屏
    const travel = Math.max(1, el.offsetHeight - window.innerHeight)
    const passed = Math.min(Math.max(-rect.top, 0), travel)
    const p = passed / travel
    stage.value = Math.min(FRAMES.length - 1, Math.floor(p * FRAMES.length))
  })
}

onMounted(() => {
  reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduced) {
    stage.value = FRAMES.length - 1
    return
  }
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
  onScroll()
  // 离开视口时不必再算
  io = new IntersectionObserver((es) => {
    for (const e of es) {
      if (e.isIntersecting) onScroll()
    }
  })
  if (wrap.value) io.observe(wrap.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
  io?.disconnect()
})

const SEL_KEYS = ['Shift', 'Sym', '空格', 'Ctrl', 'Alt']
</script>

<template>
  <div ref="wrap" class="gfs-scroll">
    <div class="gfs-sticky">
      <div class="gfs-frame">
        <!-- 上屏内容 -->
        <div class="gfs-line">
          <span v-if="!FRAMES[stage].line" class="gfs-caret" />
          <span v-for="(ch, i) in FRAMES[stage].line.split('')" :key="i" :class="{ 'is-new': i === FRAMES[stage].line.length - 1 }">{{ ch }}</span>
        </div>

        <!-- 候选 -->
        <div class="gfs-cands">
          <div v-for="(c, i) in FRAMES[stage].cands" :key="i" class="gfs-cand" :class="{ 'is-hi': i === FRAMES[stage].hi }">
            <span v-if="c">{{ c }}</span>
          </div>
        </div>

        <!-- 五键 -->
        <div class="gfs-keys">
          <div
            v-for="(k, i) in SEL_KEYS"
            :key="k"
            class="gfs-key"
            :class="{ 'is-down': (k === 'Shift' && FRAMES[stage].key === 'shift') || (k === '空格' && FRAMES[stage].key === 'space') }"
          >
            {{ k }}
          </div>
        </div>

        <!-- 正在按下的字母键 -->
        <div class="gfs-alpha" :class="{ 'is-down': FRAMES[stage].key === 'letter' }">
          {{ FRAMES[stage].key === 'letter' ? FRAMES[stage].line.slice(-1).toUpperCase() : ' ' }}
        </div>

        <p class="gfs-hint">{{ FRAMES[stage].hint }}</p>
      </div>

      <!-- 帧标 -->
      <div class="gfs-frames" role="presentation">
        <span v-for="(f, i) in FRAMES" :key="f.t" class="gfs-tick" :class="{ 'is-on': i <= stage }" />
      </div>
      <p class="gfs-caption">第 {{ stage + 1 }} / {{ FRAMES.length }} 帧 · {{ FRAMES[stage].t }}</p>
    </div>
  </div>
</template>

<style scoped>
.gfs-scroll {
  /* 滚动行程：帧数 × 一屏的节奏感 */
  height: 360vh;
}

.gfs-sticky {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 24px;
}

.gfs-frame {
  width: min(420px, 100%);
  padding: 22px 20px;
  border-radius: 34px;
  border: 1px solid var(--vp-c-divider);
  background: #0b0b0d;
  color: #f5f5f7;
  font-family: var(--vp-font-family-base);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.22);
}

/* 已上屏的文字 */
.gfs-line {
  min-height: 44px;
  font-size: 26px;
  letter-spacing: 0.02em;
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.gfs-line .is-new {
  color: var(--vp-c-brand-1);
}

.gfs-caret {
  display: inline-block;
  width: 2px;
  height: 26px;
  background: var(--vp-c-brand-1);
}

/* 候选槽 */
.gfs-cands {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  margin: 14px 0;
}

.gfs-cand {
  height: 52px;
  display: grid;
  place-items: center;
  font-size: 17px;
  border-radius: 12px;
  background: #17171a;
  transition: background-color 0.25s ease, color 0.25s ease;
}

.gfs-cand.is-hi {
  background: var(--vp-c-brand-1);
  color: #fff;
  box-shadow: inset 0 -2px 0 0 rgba(255, 255, 255, 0.6);
}

/* 五键条 */
.gfs-keys {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.gfs-key {
  text-align: center;
  font-size: 12px;
  color: #8e8e93;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.gfs-key.is-down {
  color: #fff;
  background: var(--vp-c-brand-1);
  border-radius: 8px;
}

/* 字母键 */
.gfs-alpha {
  margin-top: 12px;
  height: 56px;
  border-radius: 12px;
  background: #17171a;
  color: #f5f5f7;
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 600;
  transition: background-color 0.2s ease, transform 0.2s ease;
}

.gfs-alpha.is-down {
  background: #2f6fd0;
  transform: translateY(2px);
}

.gfs-hint {
  margin: 16px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: #a1a1a6;
  min-height: 2.6em;
}

/* 帧标 */
.gfs-frames {
  display: flex;
  gap: 6px;
}

.gfs-tick {
  width: 18px;
  height: 3px;
  border-radius: 2px;
  background: var(--vp-c-divider);
  transition: background-color 0.2s ease;
}

.gfs-tick.is-on {
  background: var(--vp-c-brand-1);
}

.gfs-caption {
  margin: 0;
  font-size: 12px;
  color: var(--vp-c-text-3);
}

@media (max-width: 640px) {
  .gfs-scroll {
    height: 300vh;
  }
}
</style>
