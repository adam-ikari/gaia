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
import { mkdirSync, writeFileSync, existsSync, rmSync, copyFileSync } from 'node:fs'
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
      focused: /focused="true"/.test(m[0]),
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

/** 点进输入框并清空。
 *
 *  `input keyevent` 一次可以接一串键码,所以「移到末尾 + 40 次退格」是**一次** adb 调用;
 *  写成 40 次调用要多花十几秒,CI 上没必要。
 *  清空是为了每张图都从同一个起点开始 —— 不清的话上一张的残留会跟着进下一张,
 *  拍出来是「你n」这种半截内容,既不说明问题也不好看。
 */
const clearField = async (idTail) => {
  // 点一下不一定就点中。上一步刚做完划动(飞字)手势时,第一下点会被吞掉 ——
  // 表现为"这一格没拿到焦点,焦点还在上一格",而图看起来只是内容不对。
  // 所以点完要**问一句**焦点在不在,不在就再点。这比针对某一步加延时可靠:
  // 同样的事可能发生在任何一步。
  let got = ''
  for (let k = 0; k < 4; k++) {
    await tapField(idTail)
    await sleep(800)
    got = await focusedId()
    if (got.endsWith(idTail)) break
    if (k < 3) console.log(`  ${idTail} 没拿到焦点(现在在 ${got}),再点一次`)
  }
  if (!got.endsWith(idTail)) throw new Error(`点了 4 下 ${idTail} 还是没拿到焦点(当前 ${got})`)
  shell(`input keyevent 123 ${new Array(40).fill(K.del).join(' ')}`)
  await sleep(700)
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

// 键码 = Android KeyEvent 的常量值。下面这张表对着 android.jar 核过:
//   javap -classpath platforms/android-36/android.jar -constants android.view.KeyEvent
//
// 这里踩过两次:
// - `h` 一度写成 **41**,而 41 是 **KEYCODE_M**。于是脚本一直在打 `nimao`,
//   词库里当然没有,组词出不来「你好」,截出来的图只有一个降级候选 ——
//   **图还是拍出来了,没人看得出来是键码写错**。所以每个步骤都带 verify。
// - `alt` 一度写成 58,那是 KEYCODE_ALT_RIGHT;左键是 57。
//
// 另外 **v 是拼音分节符**,不是撇号:词库里「你好」记作 `ni'hao`,
// [ChineseLanguage.mapText] 把 v 映射成分节符,所以要打 `nivhao`。
const K = {
  a: 29, h: 36, i: 37, n: 42, o: 43, s: 47, v: 50,
  space: 62, del: 67, shift: 59, alt: 57,
}

/** 只看输入法自己的两个 tag。层锁定的日志在 ImeController,其余在 ImeService。 */
const LOGCAT = 'logcat -d -b main -s ImeService:I ImeController:I 2>/dev/null || true'

/** 当前拿到焦点的那个控件的 resource-id。
 *
 *  uiautomator 的每个 node 都带 `focused="true"`,所以「点一下这个输入框,
 *  焦点真的过去了吗」是**可以直接问的** —— 不用去猜,也不用去看输入框里有没有字。
 *  (实验场里每个输入框的 hint 就是那句说明文字,所以"框里有没有字"根本不能当证据。)
 */
const focusedId = async () => {
  const hit = (await nodes()).find((n) => n.focused && n.id)
  return hit ? hit.id : '(没有控件拿到焦点)'
}

/** 断言焦点在某个输入框上。 */
const verifyFocus = (idTail, what) => async () => {
  const got = await focusedId()
  return got.endsWith(idTail) ? null : `${what}没拿到焦点,当前焦点是 ${got}`
}

/** logcat 里某个字符串出现了几行。用来断言「动作真的发生了」——
 *  候选条与状态行都不在 uiautomator 的 dump 里(输入法是另一个窗口),
 *  所以「有没有锁上层」「翻没翻页」只能靠输入法自己打的日志来断言。 */
const countLog = async (needle) =>
  shell(LOGCAT, 20000).split('\n').filter((l) => l.includes(needle)).length

/** 翻页 / 层锁定这两步把前后的计数存这儿,给各自的 verify 用。
 *  (verify 拿不到 step 的局部变量,而计数是异步取的,所以走模块级变量。) */
let flyPages = { before: 0, after: 0 }
let lockBefore = 0
let lockAfter = 0

/** 等内置词库加载完。
 *
 *  34 万条词库在后台线程读 assets + 建索引,实测模拟器上要 **29.5 秒**
 *  (logcat 里 `dict loaded: 348723 entries, 29477 ms`)。没就绪时组词**没有候选**,
 *  候选行是空的 —— 截出来就是一张"只有输入框"的图,看着像功能坏了。
 *  所以采集之前先等它,最多 180 秒;真超时也继续,只是提醒一句。
 */
async function waitDict(timeoutMs = 180000) {
  const t0 = Date.now()
  for (;;) {
    const log = shell(LOGCAT, 20000)
    const m = log.match(/dict loaded: (\d+) entries, (\d+) ms/)
    if (m) {
      console.log(`词库就绪:${m[1]} 条 / ${m[2]}ms(等了 ${Math.round((Date.now() - t0) / 1000)}s)`)
      return true
    }
    if (/dict load FAILED/.test(log)) {
      console.log('::warning::词库加载失败,截图里不会有候选')
      return false
    }
    if (Date.now() - t0 > timeoutMs) {
      console.log('::warning::等词库超时,截图里可能没有候选')
      return false
    }
    await sleep(5000)
  }
}

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

/** 每一步 = 出一张图 + 一句给站点用的话。
 *
 *  **图必须对得起那句话。** 之前 04/05 两步的文案写「五键选字」「滑动翻页」,
 *  截出来的却是一张空闲状态的状态行 —— 看的人不会知道是演示没做出来,
 *  只当是功能没有。所以每步要么拍到那个状态,要么 verify 说不清。
 *
 *  步骤顺序也是刻意的:alt 锁定那步放在 url/number 之后,免得锁住的层把后面两张
 *  输入类型对照图污染掉(设置/许可两页不显示输入法条,解锁失败也无所谓,但还是解开)。
 */
const STEPS = [
  {
    name: '01-idle',
    core: true,
    caption: '打开输入框的样子。就这一行：左边是状态，中间是这一层能按的键，右边三个按钮。',
    async run() {
      await clearField(':id/et_playground')
      await sleep(2000)
    },
  },
  {
    name: '02-composing',
    core: true,
    caption: '打了一半：nivhao。候选实时跟着变，你不用等它想完。',
    async run() {
      key(K.n, K.i, K.v, K.h, K.a, K.o)
      await sleep(2000)
    },
    async verify() {
      const t = (await byId(':id/et_playground')).text
      return t.includes("ni'hao") ? null : `组词区该是 ni'hao,实际是「${t}」`
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
    async verify() {
      const t = (await byId(':id/et_playground')).text
      return t.includes('你好') ? null : `上屏后该有「你好」,实际是「${t}」`
    },
  },
  {
    name: '04-page-swipe',
    core: true,
    caption: '候选多于一页。在这一行上左右划，就换页 —— 划的是手指走过多少，不是屏幕宽的几分之几。',
    async run() {
      await clearField(':id/et_playground')
      // shi 的候选横跨好几页,才够演示翻页(ni'hao 只有两条,划了也看不出换页)
      key(K.s, K.h, K.i)
      await sleep(1800)
      const f = await byId(':id/et_playground')
      const before = await countLog('fly page:')
      // 划在输入框那条线上。输入法窗口是全屏的,飞字手势收得到窗口外的点,
      // 所以这里不需要知道键盘有多高 —— 坐标跟着输入框走,天然分辨率无关。
      const dx = Math.round(f.cx * 0.3) || 260
      shell(`input swipe ${f.cx - dx} ${f.cy} ${f.cx + dx} ${f.cy} 220`)
      // 多等一会儿让手势彻底收尾 —— 收尾不干净的话下一步的第一下点会被吞
      await sleep(2200)
      flyPages = { before, after: await countLog('fly page:') }
    },
    async verify() {
      return flyPages.after > flyPages.before ? null : '划了一下,输入法日志里没有翻页记录'
    },
  },
  {
    name: '05-url-field',
    core: false,
    caption: '切到网址框，中间那排键换成网址常用的一键短语。',
    async run() {
      await clearField(':id/et_url')
      await sleep(2000)
    },
    verify: verifyFocus(':id/et_url', '网址框'),
  },
  {
    name: '06-number-field',
    core: false,
    caption: '切到数字框，左边这一项直接写出这类框能打的字符。',
    async run() {
      await clearField(':id/et_number')
      await sleep(2000)
    },
    verify: verifyFocus(':id/et_number', '数字框'),
  },
  {
    name: '07-alt-layer',
    core: false,
    caption: '连按两下 Alt，这一层就锁住：左边多出 alt 这一项，带下划线就是锁着的样子。再按一下解开。',
    async run() {
      // 必须先回到拼音态的普通文本框:上一步停在数字框,数字态里 alt 那一项
      // 显示的是「0-9」这类字符集,锁没锁都看不出区别 —— 图就白拍了。
      await clearField(':id/et_playground')
      lockBefore = await countLog('layer lock: true')
      // 双击 = 锁定([ImeController] 的主轴是敲击次数,单击只是借用一下)
      shell(`input keyevent ${K.alt} ${K.alt}`)
      await sleep(1500)
      lockAfter = await countLog('layer lock: true')
    },
    async verify() {
      if (lockAfter <= lockBefore) return '连按两下 Alt,输入法日志里没有出现锁定记录'
      return verifyFocus(':id/et_playground', '普通文本框')()
    },
  },
  {
    name: '08-settings',
    core: false,
    caption: '设置页。词库导入导出、词频、上下滑距离、翻页方向都在这儿。',
    async run() {
      // 解开上一步锁住的 alt 层:设置页虽然不显示输入法条,但留着锁住的状态
      // 会让「真机重跑一次」的人以为层卡住了
      shell(`input keyevent ${K.alt}`)
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
  // 词库就绪之前候选是空的,那组图等于没截 —— 先等它,再开始录屏
  process.stdout.write('▶ 等词库 ... ')
  const dictOk = await waitDict()
  console.log(dictOk ? '✓' : '✗')
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
      const entry = { file: `${s.name}.png`, caption: s.caption }
      manifest.shots.push(entry)
      // 自检:**图拍到了不等于图里是对的**。键码写错、输入法改了行为,图都照样拍出来,
      // 只有对着输入框的实际内容断言才看得出来。图照拍(要看就看得见),
      // 但不符要报出来并让退出码非 0 —— 不能悄悄混进站点。
      if (s.verify) {
        const bad = await s.verify()
        if (bad) {
          entry.warn = bad
          fail++
          if (s.core) coreFail++
          console.log(`⚠ ${bad}(图已拍,但对不上)`)
        } else console.log('✓')
      } else console.log('✓')
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
    videoOk = true
  } catch (e) {
    console.log(`录屏拉取失败:${e.message}`)
  }
  shell(`rm -f ${recPath}`)

  // 封面 = 第一张截图,不是"录完那一刻的屏幕"。
  // 录完时人在许可设置页,拿它当 <video poster> 会先给人看一页法律条文。
  // screencap 只出 PNG,所以文件名也用 .png —— 把 PNG 存成 .jpg 靠浏览器嗅探才显示。
  const poster = join(outDir, 'gaia-demo-poster.png')
  const first = join(outDir, '01-idle.png')
  if (existsSync(first)) {
    copyFileSync(first, poster)
  } else {
    screencap(poster)
  }
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
