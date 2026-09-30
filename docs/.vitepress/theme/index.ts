import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import Downloads from './components/Downloads.vue'
import DemoVideo from './components/DemoVideo.vue'
import ScrollFrames from './components/ScrollFrames.vue'
import SwipeFeel from './components/SwipeFeel.vue'
import KeyboardStack from './components/KeyboardStack.vue'
import Shots from './components/Shots.vue'
import Home from './layouts/Home.vue'
import '../styles/apple.css'
import '../styles/home.css'
import '../styles/responsive.css'

/** 滚入视口时淡入上移。动画定义在 apple.css 里，这里只加类名。 */
function revealOnScroll() {
  if (typeof IntersectionObserver === 'undefined') return
  const targets = document.querySelectorAll('.gaia-band-copy, .gaia-band-head, .gaia-band-narrow')
  if (!targets.length) return

  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue
        ;(e.target as HTMLElement).classList.add('gaia-in')
        io.unobserve(e.target)
      }
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.05 }
  )

  for (const t of targets) {
    t.classList.add('gaia-reveal')
    io.observe(t)
  }
}

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('Downloads', Downloads)
    app.component('DemoVideo', DemoVideo)
    app.component('ScrollFrames', ScrollFrames)
    app.component('SwipeFeel', SwipeFeel)
    app.component('KeyboardStack', KeyboardStack)
    app.component('Shots', Shots)
    // 自定义布局。名字不能叫 'home' —— 那是 VitePress 内置首页布局的名字，
    // 同名会先被内置的 VPHome 接管，自定义布局根本不生效（踩过）。
    app.component('apple-home', Home)
  },
  mounted() {
    revealOnScroll()
  },
  updated() {
    revealOnScroll()
  },
} satisfies Theme
