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
 * 自定义容器 `::: band` —— 让首页每个 section 在 **md 文件里**就能有自己的背景，
 * 而不用把整页写成一个 Vue 组件。底色名字对应 home.css 里的 gaia-band-*。
 *
 *   ::: band
 *   ## 标题
 *   正文…
 *   :::
 *
 * ## 底色是**按顺序自动轮换**的,不在 md 里写（2026-10-02 用户「自动颜色轮换」）
 *
 * md 里只写 `::: band`,颜色按这段是第几个自动取:
 *
 * ```
 *   第 1 段 → stone   第 4 段 → stone
 *   第 2 段 → ink     第 5 段 → ink
 *   第 3 段 → white   第 6 段 → white
 * ```
 *
 * 写死颜色时踩过的坑:六段要人肉排一遍,加一段就得重排,而且**很容易排出相邻同色**
 * （连着两段看着像一整块,分区就没了）。轮换从定义上排除掉这件事。
 *
 * **从 stone 起而不是 white**:首屏 hero 是 white,第一段若也是 white 就是两块白;
 * 从 stone 起还能让 hero 与第一段之间有边界。末段落在 white,与首屏呼应。
 *
 * 仍可显式覆盖:`::: band ink` —— 那就绕过轮换,适合"这一段就是要某个色"。
 *
 * 注意 markdown-it-container 把**整个** info 串（" band ink"，含容器名）交给 validate/render，
 * 所以取参数要拿最后一段，不是整串 trim。这是踩过的坑：按字面写 validate 会永远匹配不上，
 * 表现是 `:::` 原样漏到页面上。
 */

/** 轮换顺序。三个底色、周期 3 —— 相邻两段必然不同色。 */
const BAND_TONES = ['stone', 'ink', 'white'] as const

/** md 里显式写的底色（白名单之外的一律当没写,交给轮换）。 */
const BAND_TONE_OVERRIDE = new Set<string>(BAND_TONES)

function bandTone(info: string) {
  return (info.trim().split(/\s+/).pop() ?? '').trim()
}

function bandPlugin(md: any) {
  // **每个页面重新数**。放在插件闭包里而不是模块级:模块级会被上一次构建的计数污染,
  // 于是改一处内容重新构建,首页的配色就变了 —— 那种 bug 极难从页面上看出来。
  let seq = 0

  md.use(container, 'band', {
    // 参数可省:给了且是白名单里的名字就用它,否则轮换。所以 validate 只认容器名本身。
    validate: (params: string) => bandTone(params) === 'band' || BAND_TONE_OVERRIDE.has(bandTone(params)),
    render(tokens: any[], idx: number) {
      const token = tokens[idx]
      if (token.nesting === 1) {
        const named = bandTone(token.info)
        const tone = BAND_TONE_OVERRIDE.has(named)
          ? named
          : BAND_TONES[seq++ % BAND_TONES.length]
        return `<section class="gaia-band gaia-band-${tone}">\n`
      }
      return '</section>\n'
    },
  })

  // 一页开始前把计数清零,见上面关于"每个页面重新数"的说明。
  // 挂在 'normalize' **之前**:那是 core 链的第一个规则,而容器的 render 回调是在
  // core 跑完之后(renderer.render 阶段)才被调用的,所以清零一定早于本段任何一次轮换。
  md.core.ruler.before('normalize', 'band_seq_reset', () => {
    seq = 0
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
