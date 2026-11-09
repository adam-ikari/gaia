#!/usr/bin/env node
/**
 * 站点版式的**数值**判据 —— 用真浏览器量,不靠看图。
 *
 * ## 为什么不是「截图看一眼」
 * 这个站栽过的两个跟头都看不见:
 *
 * - **559px 事件**(`a1ee77f` 之前的 KeyboardStack):3D 变换的外接框把 375px 的手机
 *   撑成 559px 的布局视口。手机浏览器把 `width=device-width` 当**最小**宽度,
 *   于是一个元素溢出 ⇒ **整站缩小 + 右边一截没了**,而每张截图看着都「正常」,
 *   只是内容比该有的窄 6%。单看某个组件永远发现不了。
 * - **宽表静默裁字**:容器 `overflow: hidden` 时,超出的那一列直接不存在,
 *   没有任何视觉线索告诉你少了东西。
 *
 * ⇒ 这两类都要按**数字**判:`innerWidth` 等于设备宽、横向不能滚、没有元素越过右边界。
 *
 * ## 只量得出「几何」，量不出「画错」
 * 图里画的东西与真产品不符(上滑的表面写成候选栏那类),工具一概看不见。
 * 所以它同时**输出截图**,最后一关仍然是人眼对着 Titan 2 的尺寸看一遍。
 *
 * ## 用法
 *     cd web && npm run build && npm run preview &   # 起的站必须带 base(/gaia/)
 *     node tools/sitecheck/mobile-check.mjs http://localhost:4173/gaia/
 *
 * **每次重新 build 之后都要重启 preview** —— 它记文件列表,新 CSS 会 404(见 assertStyled)。
 *
 * 退出码:有任何一条不过 ⇒ 非 0。截图落在 `out/sitecheck/`。
 *
 * 依赖 `puppeteer-core`(装在 `tools/sitecheck/` 自己的 package.json 里,
 * **不进 web/package.json** —— 站点构建走 `npm ci`,多一个依赖就多一次失败面)。
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import puppeteer from 'puppeteer-core'

const URL = process.argv[2] || 'http://localhost:4173/gaia/'
const OUT = process.argv[3] || 'out/sitecheck'
const CHROME = process.env.CHROME || '/usr/bin/chromium-browser'

/**
 * Titan 2 的 CSS 视口是 **576×576 的正方形**(面板 1440×1440 @320dpi)。
 * 它比常见手机宽、比它矮,而且不是扁的 —— 所以这一档必须单独量,
 * 不能拿 375 代表「手机」。820 是平板,1280 是桌面。
 */
const VIEWPORTS = [
  { w: 320, h: 568 },
  { w: 375, h: 667 },
  { w: 390, h: 844 },
  { w: 412, h: 915 },
  { w: 576, h: 576 },
  { w: 820, h: 1180 },
  { w: 1280, h: 900 },
]

/**
 * 一次 `scrollTo` 探测,而不是读 `scrollWidth`。
 *
 * `overflow-x: clip` 之后 `scrollWidth` **仍然偏大**(内容还在,只是不滚),
 * 用它判会天天误报;真把窗口滚一下才知道到底滚不滚得动。
 */
async function probeScrollX(page) {
  return page.evaluate(async () => {
    const doc = document.documentElement
    const before = window.scrollX
    window.scrollTo(1, 0)
    const moved = window.scrollX !== before
    window.scrollTo(before, 0)
    return moved || doc.scrollWidth > doc.clientWidth + 1
      ? { canScroll: moved, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth }
      : { canScroll: false, scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth }
  })
}

/**
 * 元素级检查。排除「存在但没显示」的东西:VitePress 的侧栏与菜单在关掉时
 * 仍在 DOM 里,盒子照样有坐标,算进去就是常驻假阳性。
 */
async function audit(page) {
  return page.evaluate(() => {
    const vw = window.innerWidth
    const shown = (el) => {
      const cs = getComputedStyle(el)
      if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return false
      if (!el.getClientRects().length) return false
      // 祖先被藏起来的,子元素盒子可能还在
      for (let p = el.parentElement; p; p = p.parentElement) {
        const pcs = getComputedStyle(p)
        if (pcs.display === 'none' || pcs.visibility === 'hidden' || +pcs.opacity === 0) return false
      }
      return true
    }
    const out = { overflow: [], tinyTap: [], smallFont: [] }
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect()
      if (r.width === 0 || r.height === 0 || !shown(el)) continue
      // 只给读屏软件存在的链接(`.visually-hidden` 是 clip 掉,不是 display:none,
      // 上面的 shown 挡不住它)。手指点不到它,拿 44px 要求它就是假阳性。
      if (el.closest('.visually-hidden')) continue
      if (r.right > vw + 1) {
        // 只报**自己**越界的:子孙越界会带着祖先一起超,列出来全是重复噪音
        const kids = [...el.children].filter((c) => c.getBoundingClientRect().right > vw + 1)
        if (!kids.length) {
          out.overflow.push({ tag: el.tagName.toLowerCase(), cls: el.className.toString().slice(0, 48), right: Math.round(r.right) })
        }
      }
      const clickable = el.matches('a[href], button, summary, [role="button"], input, select, textarea')
      if (clickable) {
        if (r.height < 44) out.tinyTap.push({ tag: el.tagName.toLowerCase(), cls: el.className.toString().slice(0, 48), h: Math.round(r.height), text: (el.textContent || '').trim().slice(0, 20) })
      }
      const fs = parseFloat(getComputedStyle(el).fontSize)
      const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
      if (hasText && fs < 12) out.smallFont.push({ tag: el.tagName.toLowerCase(), cls: el.className.toString().slice(0, 48), fs, text: el.textContent.trim().slice(0, 24) })
    }
    return out
  })
}

/**
 * 样式没加载全就别量 —— 量到的全是 UA 默认值。
 *
 * 2026-10-30 实测踩到:`vitepress preview` 启动时就把 `dist/` 的文件列表记住了,
 * 重新 build 之后**不重启服务器**,新的 `style.<hash>.css` 是 404 —— 页面只剩一张样式表,
 * 导航按钮量出来 6px 高、下载按钮 17px,报告 6 档全红,而**每一条都是假阳性**。
 * 反过来更坏:要是服务器恰好还能服务上一版 dist,新写的组件会量出一片绿。
 * 所以这一条不过就直接停,不进逐档判定。
 */
async function assertStyled(page) {
  const s = await page.evaluate(() => {
    const links = [...document.querySelectorAll('link[rel~="stylesheet"]')]
    const sheets = [...document.styleSheets]
    const missing = links.filter((l) => !sheets.some((sh) => sh.href === l.href)).map((l) => l.getAttribute('href'))
    return { links: links.length, loaded: sheets.length, missing }
  })
  if (!s.links) throw new Error('页面上没有样式表链接')
  if (s.missing.length) {
    throw new Error(`${s.missing.length}/${s.links} 张样式表没加载(${s.missing.join(', ')})—— 服务器指着的 dist 比这次构建旧,重启 preview 再来`)
  }
}

const fail = []
mkdirSync(OUT, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: CHROME,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--hide-scrollbars'],
})
try {
  const page = await browser.newPage()
  for (const vp of VIEWPORTS) {
    const mobile = vp.w < 1024
    await page.setViewport({ width: vp.w, height: vp.h, deviceScaleFactor: 1, isMobile: mobile, hasTouch: mobile })
    await page.goto(URL, { waitUntil: 'networkidle2', timeout: 60000 })
    try {
      await assertStyled(page)
    } catch (e) {
      console.error(`✗ ${vp.w}×${vp.h}:${e.message}`)
      break
    }
    // 滚到底再量:淡入动画靠 IntersectionObserver,没滚过的项目还是 opacity 0
    await page.evaluate(async () => {
      for (let y = 0; y <= document.body.scrollHeight; y += 600) {
        window.scrollTo(0, y)
        await new Promise((r) => setTimeout(r, 30))
      }
      window.scrollTo(0, 0)
    })
    await new Promise((r) => setTimeout(r, 700))

    const got = await page.evaluate(() => window.innerWidth)
    const sx = await probeScrollX(page)
    const au = await audit(page)
    const shots = [`full-${vp.w}.png`]
    await page.screenshot({ path: join(OUT, `${vp.w}x${vp.h}.png`), fullPage: true })

    const bad = []
    if (got !== vp.w) bad.push(`innerWidth=${got}(应为 ${vp.w})—— 有元素把布局视口撑大了,整站会缩小`)
    if (sx.canScroll) bad.push(`横向可滚(scrollWidth ${sx.scrollWidth} > clientWidth ${sx.clientWidth})`)
    for (const o of au.overflow.slice(0, 6)) bad.push(`越右界 ${o.right}>${vp.w}: <${o.tag} class="${o.cls}">`)
    if (mobile) for (const t of au.tinyTap.slice(0, 6)) bad.push(`命中区 ${t.h}px<44px: <${t.tag} class="${t.cls}"> 「${t.text}」`)
    for (const f of au.smallFont.slice(0, 6)) bad.push(`字号 ${f.fs}px<12px: <${f.tag} class="${f.cls}"> 「${f.text}」`)

    console.log(`${vp.w}×${vp.h}  innerWidth=${got}  scrollX=${sx.canScroll}  ${bad.length ? '✗' : '✓'}`)
    for (const b of bad) console.log(`    · ${b}`)
    if (bad.length) fail.push(`${vp.w}×${vp.h}: ${bad.length} 条`)
    void shots
  }
} finally {
  await browser.close()
}

if (fail.length) {
  console.error(`\n不过 ${fail.length} 档:${fail.join('、')}  —— 截图在 ${OUT}/`)
  process.exit(1)
}
console.log(`\n${VIEWPORTS.length} 档全过。截图在 ${OUT}/(人眼那一关还得自己过)`)
