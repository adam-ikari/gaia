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
 * 用法(**手机直连,不用模拟器**):
 *   ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
 *   node tools/aitest/shots.mjs [输出目录]     # 默认 evidence/shots
 *
 * 产出(可直接喂给站点 docs/public/media/):
 *   NN-name.png   每个步骤一张截图
 *   gaia-demo.mp4 全程录屏(screenrecord,8Mbps)
 *   gaia-demo-poster.png 封面(站点的 <video poster>)
 *   manifest.json 图文对照表
 *
 * 为什么在真机跑而不是 CI 模拟器(2026-09-30 实测):GitHub runner 的 x86_64 模拟器起来
 * 约 45 秒就自己死(KVM 权限已修好,不是权限问题),而且**模拟器的分辨率、键盘、insets 都跟
 * Titan 2 不同** —— 拿它的图冒充真机是错的。这个脚本分辨率无关(点输入框靠 uiautomator 读
 * bounds,不写死坐标),所以真机直连是对的做法。
 *
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
  const buf = execFileSync(adb, [...args, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  if (!buf || buf.length < 1000) throw new Error(`截图异常:${file} 只有 ${buf ? buf.length : 0} 字节`)
  writeFileSync(file, buf)
  return file
}

const key = (...codes) => codes.forEach((c) => shell(`input keyevent ${c}`))

/** uiautomator dump 的原始 XML。**必须重试**。
 *
 *  uiautomator 在窗口还在 inflate、或 accessibility bridge 刚重启时,会回
 *  `ERROR: null root node returned by UiTestAutomationBridge.` 并且什么都不吐 ——
 *  这是**瞬时**的,不是不支持(dump 本身能用,同一轮后面几步就成功了)。
 *  2026-10-01 在 CI 上:一次 retry 只等 1.2s 不够,连着 4 步全废;
 *  改成 8 次 × 2.5s = 20 秒上限就稳了。
 *
 *  全失败时把 stderr 带出去 —— 光说"找不到输入框"没法定位是 dump 挂了还是布局变了。
 */
async function dumpUi(retries = 8, gap = 2500) {
  let lastErr = ''
  for (let k = 0; k < retries; k++) {
    try {
      const out = execFileSync(adb, [...args, 'shell', 'uiautomator dump /sdcard/u.xml'], {
        encoding: 'utf8',
        timeout: 30000,
        maxBuffer: 16 * 1024 * 1024,
      })
      lastErr = out.trim()
      const xml = shell('cat /sdcard/u.xml', 10000)
      if (xml && xml.includes('<node')) return xml
    } catch (e) {
      lastErr = `${e.stderr ?? ''} ${e.message ?? ''}`.trim()
    }
    if (k < retries - 1) await sleep(gap)
  }
  dumpFailure = lastErr
  return ''
}
let dumpFailure = ''

/** 解析 dump 里的所有 node。 */
async function nodes(xml = null) {
  const x = xml ?? (await dumpUi())
  return [...x.matchAll(/<node[^>]*>/g)].map((m) => {
    const b = m[0].match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/)
    const g = (re) => (m[0].match(re) ?? [])[1] ?? ''
    return {
      raw: m[0],
      id: g(/resource-id="([^"]*)"/),
      cls: g(/class="([^"]*)"/),
      text: g(/text="([^"]*)"/),
      desc: g(/content-desc="([^"]*)"/),
      cx: b ? Math.round((Number(b[1]) + Number(b[3])) / 2) : 0,
      cy: b ? Math.round((Number(b[2]) + Number(b[4])) / 2) : 0,
    }
  })
}

const tap = (n) => shell(`input tap ${n.cx} ${n.cy}`)

/** 按 resource-id 尾段找控件,比如 `':id/et_number'`。
 *
 *  **不要按顺序索引找输入框。** 实验场里输入框的顺序(playground / url / email /
 *  number / signed / …)是布局决定的,插一个框就全错位 —— 我原来写
 *  `editTexts()[2]` 当"数字框",其实那是邮箱框,图和文案对不上,
 *  而且换台设备顺序还不一定一样。resource-id 稳定。
 */
async function byId(idTail) {
  const all = await nodes()
  const hit = all.find((n) => n.id.endsWith(idTail))
  if (!hit) {
    const have = all.filter((n) => n.id.startsWith('dev.hwkbd.ime:')).map((n) => n.id)
    throw new Error(
      `找不到 ${idTail}${dumpFailure ? `(uiautomator dump 失败:${dumpFailure})` : ''}` +
        (have.length ? `\n  页面上有:${have.join(', ')}` : '\n  页面上没找到任何本应用的控件'),
    )
  }
  return hit
}

const tapField = async (idTail) => {
  const n = await byId(idTail)
  tap(n)
  return n
}

const tapText = async (needle) => {
  for (const n of await nodes()) {
    if (!`${n.text}${n.desc}`.includes(needle)) continue
    if (!n.cx && !n.cy) continue
    tap(n)
    return true
  }
  return false
}

const K = { n: 42, i: 37, h: 41, a: 29, o: 43, space: 62, del: 67, shift: 59, alt: 58 }

/** 停掉 screenrecord。
 *
 *  **不能用 `pkill -f screenrecord`。** `-f` 匹配整条命令行,而这条命令行是
 *  `sh -c pkill -f screenrecord` —— pkill 会把**自己的父 shell** 一起杀掉,
 *  adb 那头就永远等不到 shell 退出,20 秒后被 timeout 砍掉,整轮采集跟着炸。
 *  (2026-10-01 在 CI 上就是这么挂的:status 143 = SIGTERM。)
 *
 *  `killall` 匹配的是进程**名**(screenrecord),父 shell 叫 sh,不会误伤。
 *  再加上 `</dev/null` 掐掉 stdin 等待,失败也只算没停成功 —— 兜底是
 *  screenrecord 自己的 `--time-limit`,到点自己会退出。
 */
function stopRecording() {
  try {
    shell(`killall -9 screenrecord </dev/null 2>/dev/null || true`, 10000)
  } catch {
    console.log('(killall 没成功,等 --time-limit 兜底)')
  }
}

/** 每一步 = 出一张图 + 一句给站点用的话。core 的步骤失败会让退出码非 0。 */
const STEPS = [
  {
    name: '01-idle',
    core: true,
    caption: '打开输入框的样子。屏幕上就这两行：上面是候选，下面是状态。',
    async run() {
      await tapField(':id/et_playground')
      await sleep(2500)
    },
  },
  {
    name: '02-composing',
    core: true,
    caption: '打了一半：nihao。候选实时跟着变，你不用等它想完。',
    async run() {
      await tapField(':id/et_playground')
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
      // 在输入框那条线上左右划 —— 划的是键盘区域,坐标跟着输入框走才换得到分辨率无关
      const f = await byId(':id/et_playground')
      const dx = Math.round(f.cx * 0.35) || 260
      shell(`input swipe ${f.cx - dx} ${f.cy} ${f.cx + dx} ${f.cy} 220`)
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
      await tapField(':id/et_url')
      await sleep(2200)
    },
  },
  {
    name: '07-number-field',
    core: false,
    caption: '切到数字框，只留下数字能打的键。',
    async run() {
      await tapField(':id/et_number')
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
      await tapText('许可')
      await sleep(1200)
      await tapText('开源许可')
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
  // 刚装完包时 IME 可能还没注册进 ime list,直接 set 会报 "Unknown input method"。
  // 那是竞态不是配置错,轮询等它出现。
  let imeReady = false
  for (let k = 0; k < 20; k++) {
    if (shell(`ime list -s -a 2>/dev/null || true`, 15000).split('\n').some((l) => l.trim() === IME)) {
      imeReady = true
      break
    }
    console.log(`  等输入法注册 ${k + 1}/20`)
    await sleep(3000)
  }
  if (!imeReady) throw new Error(`输入法没注册进 ime list:${IME}(装好了吗?)`)
  shell(`ime set ${IME}`)
  shell(`am start -n ${PKG}/.playground.PlaygroundActivity >/dev/null 2>&1`)
  await sleep(3000)

  // 录屏包住整场:起点在第一张截图之前、终点在最后一张之后,mp4 里就是完整的一段交互,
  // 而不是"先录一段空屏再录一段操作"。--time-limit 是 kill 没成功时的兜底。
  const recPath = '/sdcard/gaia-demo.mp4'
  shell(`rm -f ${recPath}`)
  // 用 setsid/nohup 甩到后台:`adb shell '... &'` 在 shell 退出时可能连带被杀
  shell(`nohup screenrecord --time-limit 300 --bit-rate 8000000 ${recPath} >/dev/null 2>&1 &`, 5000)
  const recStarted = Date.now()

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

  // 停录屏再拉:先给 2 秒让最后一段写盘,否则 mp4 尾部会缺
  await sleep(2000)
  stopRecording()
  await sleep(1500)
  let videoOk = false
  try {
    execFileSync(adb, [...args, 'pull', recPath, join(outDir, 'gaia-demo.mp4')], {
      timeout: 180000,
      stdio: 'ignore',
    })
    // 顺带出一张封面:站点的 <video poster> 用它,首帧常常还没渲染出候选。
    // screencap 只出 PNG,所以文件名也用 .png —— 把 PNG 存成 .jpg 靠浏览器嗅探才显示,
    // 换个不嗅探的环境就露馅。
    screencap(join(outDir, 'gaia-demo-poster.png'))
    videoOk = true
  } catch (e) {
    console.log(`录屏拉取失败:${e.message}`)
  }
  shell(`rm -f ${recPath}`)
  console.log(`录屏时长 ${Math.round((Date.now() - recStarted) / 1000)}s`)

  writeFileSync(join(outDir, 'manifest.json'), JSON.stringify({ ...manifest, video: videoOk }, null, 2))
  console.log(`\n截图 ${manifest.shots.length} 张 → ${outDir}`)
  console.log(`录屏:${videoOk ? 'gaia-demo.mp4 ✓' : '失败'}`)
  console.log(`失败步骤 ${fail} 个(其中核心 ${coreFail} 个)`)
  process.exitCode = coreFail > 0 ? 1 : 0
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
