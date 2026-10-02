import { defineConfig } from 'vitepress'
import container from 'markdown-it-container'

// 访问统计:自建 Umami(Cloudflare Workers + D1)。
// 两项都留空就不输出 <script>,所以本地开发和"还没部署好"的中间状态都不会产生 404。
// 填法:部署完 Umami 后,把后台看到的 website id 与脚本地址填进来即可。
const umami = {
  websiteId: '', // 例:'a1b2c3d4-e5f6-...'
  scriptUrl: '', // 例:'https://umami.example.com/script.js'
} as const

const umamiHead =
  umami.websiteId && umami.scriptUrl
    ? ([['script', { defer: true, src: umami.scriptUrl, 'data-website-id': umami.websiteId }]] as const)
    : []

/**
 * 自定义容器 `::: band <底色>` —— 让首页每个 section 在 **md 文件里**就能指定自己的背景，
 * 而不用把整页写成一个 Vue 组件。底色名字对应 home.css 里的 gaia-band-*。
 *
 *   ::: band ink
 *   ## 标题
 *   正文…
 *   :::
 *
 * 注意 markdown-it-container 把**整个** info 串（" band ink"，含容器名）交给 validate/render，
 * 所以取参数要拿最后一段，不是整串 trim。这是踩过的坑：按字面写 validate 会永远匹配不上，
 * 表现是 `:::` 原样漏到页面上。
 */
function bandTone(info: string) {
  return (info.trim().split(/\s+/).pop() ?? '').trim()
}

function bandPlugin(md: any) {
  md.use(container, 'band', {
    validate: (params: string) => ['white', 'stone', 'ink'].includes(bandTone(params)),
    render(tokens: any[], idx: number) {
      const token = tokens[idx]
      if (token.nesting === 1) {
        return `<section class="gaia-band gaia-band-${bandTone(token.info)}">\n`
      }
      return '</section>\n'
    },
  })
}

export default defineConfig({
  base: '/gaia/',
  lang: 'zh-CN',
  markdown: {
    config(md) {
      bandPlugin(md)
    },
  },
  title: '盖亚输入法',
  description: '给 Unihertz Titan 2 量身定做的拼音输入法：三层键盘、五键选字、上滑飞字。',
  lastUpdated: false,
  cleanUrls: true,

  head: [
    ['meta', { name: 'theme-color', content: '#0071e3' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: '盖亚输入法 — 给 Titan 2 量身定做的拼音输入法' }],
    [
      'meta',
      {
        property: 'og:description',
        content: '三层键盘、五键选字、上滑飞字。为 Unihertz Titan 2 量身定做。',
      },
    ],
    ...umamiHead,
  ],

  themeConfig: {
    // 只剩首页，所以没有导航、没有侧栏、没有目录。导航栏里不放「下载」——
    // 页面上任何位置的「下载」都直接指向 APK，中间不隔页面。
    nav: false,
    sidebar: false,
    outline: false,
    docFooter: false,
    darkModeSwitchLabel: '外观',
    returnToTopLabel: '回到顶部',

    footer: {
      message: '内置词库数据来自 jieba（MIT）与 pinyin-pro（MIT）。',
      copyright: 'Copyright © 2026 盖亚输入法',
    },
  },
})
