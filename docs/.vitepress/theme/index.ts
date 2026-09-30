import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import Downloads from './components/Downloads.vue'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('Downloads', Downloads)
  },
} satisfies Theme
