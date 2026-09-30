/**
 * 站点素材采集：真机 / 模拟器上都跑，出一组"讲特点用"的截图 + 一份对照表。
 *
 * 为什么单独写一个脚本,而不是直接跑 harness.mjs:
 * - harness.mjs 里的点击坐标是按 Titan 2 写死的(700,400),模拟器上必然点空;
 *   这里改成从 uiautomator dump 里读 EditText 的 bounds 再点中心,分辨率无关。
 * - harness 的 screenshot() 用 `screencap -d <displayId>`(Titan 单屏专用),
 *   这里用 `exec-out screencap -p`,模拟器和多屏设备都能出图。
 * - 站点要的是"每张图配一句话",所以这里顺带产出 manifest.json。
 *
 * 用法:
 *   node tools/aitest/shots.mjs [输出目录]     # 默认 evidence/shots
 * 环境变量:
 *   ADB           adb 路径(默认 adb)
 *   AITEST_DEVICE adb -s 序列号(多设备时指定)
 *
 * 退出码:0 = 核心步骤全过;1 = 有核心步骤失败(产物仍在,可先看图再判断)
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = import.meta.dirname ?? fileURLToPath(new URL('.', import.meta.url))
const PKG = 'dev.hwkbd.ime'
const IME = `${PKG}/.service.ImeService`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const adb = process.env.ADB || 'adb'
const serial = process.env.AITEST_DEVICE
const args = serial ? ['-s', serial] : []

const shell = (cmd, timeout = 20000) =>
  execFileSync(adb, [...args, 'shell', cmd], { encoding: 'utf8', timeout, maxBuffer: 16 * 1024 * 1024 }).trim()

/** 截图:`exec-out screencap -p` 直出 PNG,不过 sdcard,也不用 pull。 */
function screencap(file) {
  execFileSync(adb, [...args, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 }).length // noop guard
  const buf = execFileSync(adb, [...args, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  writeFileSync(file, buf)
  return file
}

const key = (...codes) => codes.forEach((c) => shell(`input keyevent ${c}`))

/** uiautomator dump,返回所有 EditText 的 bounds(按界面从上到下)。 */
function editTexts() {
  try {
    shell('uiautomator dump /sdcard/u.xml', 30000)
    const xml = shell('cat /sdcard/u.xml', 10000)
    const out = []
    for (const m of xml.matchAll(/<node[^>]*>/g)) {
      if (!m[0].includes('android.widget.EditText')) continue
      const b = m[0].match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)
      if (!b) continue
      const [, x1, y1, x2, y2] = b.map(Number)
      out.push({ cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2), text: (m[0].match(/text="([^"]*)"/) ?? [])[1] ?? '' })
    }
    return out
  } catch {
    return []
  }
}

const tapField = (i) => {
  const f = editTexts()[i]
  if (!f) throw new Error(`找不到第 ${i} 个输入框(共 ${editTexts().length} 个)`)
  shell(`input tap ${f.cx} ${f.cy}`)
  return f
}

const tapText = (needle) => {
  shell('uiautomator dump /sdcard/u.xml', 30000)
  const xml = shell('cat /sdcard/u.xml', 10000)
  for (const m of xml.matchAll(/<node[^>]*>/g)) {
    const t = (m[0].match(/text="([^"]*)"/) ?? [])[1] ?? ''
    if (!t.includes(needle)) continue
    const b = m[0].match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)
    if (!b) continue
    const [, x1, y1, x2, y2] = b.map(Number)
    shell(`input tap ${Math.round((x1 + x2) / 2)} ${Math.round((y1 + y2) / 2)}`)
    return true
  }
  return false
}

const K = { n: 42, i: 37, h: 41, a: 29, o: 43, space: 62, del: 67, shift: 59, alt: 58 }

/** 每一步 = 出一张图 + 一句给站点用的话。core 的步骤失败会让退出码非 0。 */
const STEPS = [
  {
    name: '01-idle',
    core: true,
    caption: '打开输入框的样子。屏幕上就这两行：上面是候选，下面是状态。',
    async run() {
      tapField(0)
      await sleep(2500)
    },
  },
  {
    name: '02-composing',
    core: true,
    caption: '打了一半：nihao。候选实时跟着变，你不用等它想完。',
    async run() {
      tapField(0)
      await sleep(1200)
      key(K.n, K.i, K.h, K.a, K.o)
      await sleep(2000)
    },
  },
  {
    name: '03-committed',
    core: true,
    caption: '空格上屏。首选在正中间，按一下空格就是它。',
    async run() {
      key(K.space)
      await sleep(1500)
    },
  },
  {
    name: '04-five-key',
    core: false,
    caption: '五个候选分给五根手指。Shift、Sym、Ctrl、Alt 各管一个，手不用挪。',
    async run() {
      key(K.n, K.i)
      await sleep(1500)
      key(K.shift)
      await sleep(1200)
      key(K.del, K.del)
      await sleep(800)
    },
  },
  {
    name: '05-page-swipe',
    core: false,
    caption: '左右滑动键盘，候选词就跟着翻页。',
    async run() {
      key(K.n, K.i, K.h, K.a, K.o)
      await sleep(1800)
      const f = editTexts()[0]
      if (f) shell(`input swipe ${f.cx - 260} ${f.cy} ${f.cx + 260} ${f.cy} 220`)
      await sleep(1200)
      key(K.del, K.del, K.del, K.del, K.del, K.del)
      await sleep(600)
    },
  },
  {
    name: '06-url-field',
    core: false,
    caption: '切到网址框，这行键就换成网址开头。',
    async run() {
      const f = editTexts()[1]
      if (!f) throw new Error('实验场里没有网址输入框')
      shell(`input tap ${f.cx} ${f.cy}`)
      await sleep(2200)
    },
  },
  {
    name: '07-number-field',
    core: false,
    caption: '切到数字框，只留下数字能打的键。',
    async run() {
      const f = editTexts()[2]
      if (!f) throw new Error('实验场里没有数字输入框')
      shell(`input tap ${f.cx} ${f.cy}`)
      await sleep(2200)
    },
  },
  {
    name: '08-settings',
    core: false,
    caption: '设置页。词库导入导出、词频、上下滑距离、翻页方向都在这儿。',
    async run() {
      shell(`am start -n ${PKG}/.settings.SettingsActivity >/dev/null 2>&1`)
      await sleep(2500)
    },
  },
  {
    name: '09-licenses',
    core: false,
    caption: '用了哪些开源组件、什么许可，应用里写得很清楚。',
    async run() {
      tapText('许可')
      await sleep(1200)
      tapText('开源许可')
      await sleep(1500)
    },
  },
]

async function main() {
  const outDir = process.argv[2] ? join(process.cwd(), process.argv[2]) : join(HERE, 'evidence', 'shots')
  rmSync(outDir, { recursive: true, force: true })
  mkdirSync(outDir, { recursive: true })

  const manifest = { package: PKG, serial: serial ?? 'default', shots: [] }
  let coreFail = 0
  let fail = 0

  shell(`ime enable ${IME} >/dev/null 2>&1 || true`)
  shell(`ime set ${IME}`)
  shell(`am start -n ${PKG}/.playground.PlaygroundActivity >/dev/null 2>&1`)
  await sleep(3000)

  for (const s of STEPS) {
    process.stdout.write(`▶ ${s.name} ... `)
    try {
      await s.run()
      const file = join(outDir, `${s.name}.png`)
      screencap(file)
      manifest.shots.push({ file: `${s.name}.png`, caption: s.caption })
      console.log('✓')
    } catch (e) {
      fail++
      if (s.core) coreFail++
      console.log(`✗ ${e.message}`)
    }
  }

  writeFileSync(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2))
  console.log(`\n截图 ${manifest.shots.length} 张 → ${outDir}`)
  console.log(`失败步骤 ${fail} 个（其中核心 ${coreFail} 个）`)
  process.exitCode = coreFail > 0 ? 1 : 0
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
