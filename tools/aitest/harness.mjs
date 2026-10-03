/**
 * hwkbd_ime AI 驱动功能测试 —— 设备驱动 + 场景引擎 + 证据收集
 *
 * 用法:
 *   node harness.mjs run all            跑全部场景
 *   node harness.mjs run compose        跑单个场景(按场景名)
 *   node harness.mjs list               列出场景
 *
 * 环境变量:
 *   ADB              adb 可执行路径(默认 $LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe)
 *   AITEST_DEVICE    adb -s 序列号(多设备时指定)
 *
 * 证据输出:tools/aitest/evidence/<时间戳>/<场景名>/ 下保存截图、ui.xml、logcat 片段、结果 JSON。
 */
import { execFileSync, execSync, spawnSync, spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync, openSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { scenarios } from './scenarios.mjs'

const HERE = import.meta.dirname ?? fileURLToPath(new URL('.', import.meta.url))
const EVIDENCE = join(HERE, 'evidence')

// ---------------------------------------------------------------- adb 封装

export function adbPath() {
  if (process.env.ADB) return process.env.ADB
  const local = join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk', 'platform-tools', 'adb.exe')
  return existsSync(local) ? local : 'adb'
}

export class Device {
  constructor(serial = process.env.AITEST_DEVICE) {
    this.serial = serial
    this.adb = adbPath()
    this.args = serial ? ['-s', serial] : []
  }

  shell(cmd, timeoutMs = 15000) {
    return execFileSync(this.adb, [...this.args, 'shell', cmd], { encoding: 'utf8', timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 })
  }

  tap(x, y) { this.shell(`input tap ${x} ${y}`) }
  key(...codes) { for (const c of codes) this.shell(`input keyevent ${c}`) }
  combo(primary, secondary) { this.shell(`input keycombination ${primary} ${secondary}`) }
  swipe(x1, y1, x2, y2, ms = 250) { this.shell(`input swipe ${x1} ${y1} ${x2} ${y2} ${ms}`) }
  text(t) { this.shell(`input text '${t}'`) }

  wake() {
    this.shell('cmd statusbar collapse') // 收起通知栏(会抢手势)
    // 主屏会单独熄灭;POWER 是开关切换,先查状态,灭才按亮
    const st = this.shell('dumpsys window policy 2>/dev/null')
    if (st.includes('SCREEN_STATE_OFF')) this.shell('input keyevent KEYCODE_POWER')
    this.shell('input keyevent KEYCODE_WAKEUP')
    this.shell('input swipe 700 1350 700 300 200') // 上滑过锁屏
    // AVD 上没有 home app,锁屏是 `FallbackHome`。`wm dismiss-keyguard` 只是**请求**,
    // 它要过几百毫秒才真正打完(日志里就是 `FallbackHome: User unlocked`)。
    this.shell('locksettings set-disabled true 2>/dev/null || true')
    this.shell('wm dismiss-keyguard')
    this.shell('svc power stayon true') // USB 供电期间保持常亮,防 10s 熄屏
    // **必须等它真的走完**(2026-10-03 page-flip 就是这么红的)。
    //
    // 不等的话,解锁会**落在场景跑到一半的时候**:那一刻抢走焦点 ⇒ IME 断连 ⇒
    // 组词被清掉(`winmode: composing=false` / `raw=`),而症状是
    // 「手势没反应」—— 日志里连 `touch DOWN` 都没有,看起来像代码问题。
    //
    // 判据用 `mDreamingLockscreen` 而不是「有没有 Unlocked 日志」:
    // 后者要等日志落盘,前者是**当下的状态**。
    for (let i = 0; i < 60; i++) {
      const p = this.shell('dumpsys window policy 2>/dev/null')
      const dreaming = /mDreamingLockscreen=true/.test(p)
      const keyguard = /isStatusBarKeyguard(ing)?=true/.test(p) || /mShowingLockscreen=true/.test(p)
      if (!dreaming && !keyguard) return
      execSync('sleep 0.25')
    }
    console.warn('⚠ 等了 15s 锁屏还没走干净,后面的场景可能继续被抢焦点')
  }

  ime() { return this.shell('ime set dev.hwkbd.ime/.service.ImeService && echo OK').includes('OK') }
  /** 前台窗口名。双屏设备(主屏 + subdisplay)每屏各有一个 mCurrentFocus,全取再拼接——只取第一个会永远命中副屏 launcher。 */
  foreground() {
    const all = [...this.shell('dumpsys window').matchAll(/mCurrentFocus=Window\{[^ ]+ ([^}]+)\}/g)].map((m) => m[1])
    return all.join(' | ')
  }
  imeShown() { return this.shell('dumpsys input_method').includes('mInputShown=true') }
  crashCount() { return Number(this.shell('logcat -d -b crash 2>/dev/null | grep -c FATAL || true').trim() || '0') }

  /** 返回并清空 logcat 中匹配行(证据用)。 */
  takeLog(pattern) {
    // tag 白名单:**`-s` 会把不在列表里的 tag 全部丢掉**,所以在别处加的日志
    // 必须把它的 tag 加进来,否则断言永远看不到(加日志 ≠ 可观测)。
    // CandidateBar 是 2026-10-03 补的 —— 翻页手势在那边,而它此前一条日志都没有。
    const out = this.shell(`logcat -d -s ImeService:* CandidateBar:* ImeRoot:* 2>/dev/null | grep -E "${pattern}" | tail -20`)
    this.shell('logcat -c')
    return out.trim()
  }

  /**
   * 截图。原来写的是 `screencap -d 4627039422300187648 -p /sdcard/…` —— 那个 `-d` 是
   * **Titan 单屏专用的 displayId**,在模拟器上直接 `Command failed`
   * (2026-10-03:compose-space / candidate-center 两个场景都死在这,看着像场景有问题)。
   * `tools/aitest/shots.mjs` 里早就写了这件事,这里却没跟上。
   * 改用 `exec-out screencap -p`:不过 sdcard、不用 pull、字节流直出。
   */
  screenshot(file) {
    const buf = execFileSync(this.adb, [...this.args, 'exec-out', 'screencap', '-p'], { timeout: 20000, maxBuffer: 64 * 1024 * 1024 })
    writeFileSync(file, buf)
  }

  /**
   * 主输入框的内容(UI dump)。
   *
   * **必须按 resource-id 取,不能取「最后一个 EditText」**。
   *
   * 原来那个写法在 2026-10-03 表现成:l2-output 场景拿到
   * `"密码 → 强制英文"` —— 那是 `playground_password_hint`,**密码框的 hint**。
   * 两个原因叠在一起:
   *   1. 循环里每个 EditText 都覆盖一次 `et`,所以留下的是 dump 里最后那个,
   *      而 playground 的最后一个是 `et_password`;
   *   2. 空的 EditText,uiautomator 的 `text` 属性会**回落到 hint**。
   *
   * 所以那个断言失败跟 Alt+W 没关系 —— 键可能一直是好的,只是读错了框。
   * 这比「功能坏了」更难查:它给出一个**看起来像内容**的真值。
   */
  editText() {
    try {
      this.shell('uiautomator dump /sdcard/u.xml', 20000)
      const xml = this.shell('cat /sdcard/u.xml', 10000)
      for (const m of xml.matchAll(/<node[^>]*>/g)) {
        const node = m[0]
        if (!node.includes('android.widget.EditText')) continue
        if (!/resource-id="[^"]*\/et_playground"/.test(node)) continue
        return (node.match(/text="([^"]*)"/) ?? [])[1] ?? ''
      }
      return ''
    } catch { return '' }
  }
}

// ---------------------------------------------------------------- 断言工具

const ok = (cond, msg) => ({ pass: !!cond, msg })

/** 常用断言:logcat 行包含期望片段。 */
export const logContains = (log, ...expected) => expected.map((e) => ok(log.includes(e), `log 应含 "${e}"`))

// ---------------------------------------------------------------- 场景引擎

async function runScenario(name, device, dir) {
  const sc = scenarios.find((s) => s.name === name)
  if (!sc) throw new Error(`场景不存在: ${name}(可选: ${scenarios.map((s) => s.name).join(', ')})`)
  const results = []
  const shots = []
  const ctx = {
    device,
    dir,
    shot: (label) => { const f = join(dir, `${label}.png`); device.screenshot(f); shots.push(f); return f },
    expect: (pass, msg) => results.push(ok(pass, msg)),
    expectLog: (log, ...expected) => results.push(...logContains(log, ...expected)),
    run: (fn) => fn(ctx), // 自定义步骤
  }

  // 前置:唤醒 + 切回本输入法 + 打开实验场
  device.wake()
  device.ime()
  device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
  await sleep(2000)
  device.tap(700, 400)
  await sleep(2500)

  for (const step of sc.steps) await step(ctx)
  return { name, results, shots }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------------------------------------- 主入口

async function main() {
  const [, , cmd, arg] = process.argv
  const device = new Device()
  if (!existsSync(adbPath())) { console.error('adb 不存在,设置 ADB 环境变量'); process.exit(2) }

  if (cmd === 'list') { scenarios.forEach((s) => console.log(s.name.padEnd(18), s.desc)); return }

  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const selected = cmd === 'run' ? (arg && arg !== 'all' ? [arg] : scenarios.map((s) => s.name)) : null
  if (!selected) { console.error('用法: node harness.mjs run all|<场景名> | list'); process.exit(2) }

  mkdirSync(EVIDENCE, { recursive: true })
  const runDir = join(EVIDENCE, stamp)
  mkdirSync(runDir, { recursive: true })

  const crashesBefore = device.crashCount()
  const report = { startedAt: '', device: device.serial ?? 'default', scenarios: [] }

  // 前置检查:解锁 + 实验场到前台 + 编辑框聚焦,失败立刻终止(避免整场假失败)
  //
  // **就绪判据是「IME 真的在处理输入」,不是「窗口在前台」**(2026-10-03)。
  //
  // 原来只查 `mCurrentFocus` 与 `mInputShown`,两个都是**代理指标**。
  // 于是在 IME 还没真正挂上编辑框时,`input keyevent` 里的按键被系统当**普通文本**
  // 直接送进 EditText —— compose-space 拿到 `editText="ni"`(原始字母),
  // 而不是候选词「你」。**输入法看起来完全没参与**,但所有代理指标都是绿的。
  //
  // 所以探针直接问 IME:敲一个 `n`,看有没有 `updateSelection … comp=`。
  // 有 ⇒ 键盘事件真的走到 IME 了。没有 ⇒ 还没就绪,重来。
  const imeProcessing = async () => {
    device.shell('logcat -c')
    device.key(42) // KEYCODE_N
    let ok = false
    for (let i = 0; i < 12 && !ok; i++) {
      // `comp=<数字>` = 组词区间非空 ⇒ 键盘事件真的进了 IME。
      // (`comp=-1..-1` 是没有组词,不算就绪信号。)
      const l = device.shell('logcat -d -s ImeService:* 2>/dev/null')
      ok = /updateSelection[^\n]*comp=(?!-1)/.test(l)
      if (!ok) await sleep(250)
    }
    device.key(67) // KEYCODE_DEL 收尾,别把探针的字母留在框里
    return ok
  }

  const preflight = async () => {
    device.wake()
    device.ime()
    device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
    await sleep(2500)
    device.tap(700, 400)
    await sleep(2500)
    const fg = device.foreground()
    const shown = device.imeShown()
    const processing = shown ? await imeProcessing() : false
    return { fg, shown, processing }
  }
  const ready = (p) => p.fg.includes('PlaygroundActivity') && p.shown && p.processing
  let pf = await preflight()
  if (!ready(pf)) pf = await preflight() // 失败重试一次
  if (!ready(pf)) {
    console.error(`✗ 设备未就绪:前台=${pf.fg},输入法显示=${pf.shown},IME在处理输入=${pf.processing}`)
    console.error('  可能:安全锁屏(需手动解锁)、主屏熄灭、用户正在使用手机,')
    console.error('  或编辑框没真正聚焦(此时按键会作为普通文本进 EditText,而不是走 IME)。')
    process.exitCode = 2
    return
  }

  // ---- 常驻 logcat(整轮),场景结束时按行切片存证据 -------------------
  //
  // 不能靠 `logcat -d` 取:`takeLog` 每次断言后都 `logcat -c` 清空缓冲区,
  // 所以**任何时刻** `-d` 能看到的只有最后一次断言之后的东西。
  // 2026-10-03 就是这样:page-flip 目录里的 ime.log 只剩 9 行,
  // 而那 9 行恰好**不含**失败发生时的三条埋点 —— 证据机制自己把证据抵消了。
  //
  // 所以开一个不间断的 `logcat -v time`,落成 runDir/full.log,
  // 每个场景记录起始行号,结束时把那段切出来。
  const fullLog = join(runDir, 'full.log')
  const fullFd = openSync(fullLog, 'w')
  const logProc = spawn(device.adb, [...device.args, 'logcat', '-v', 'time'], { stdio: ['ignore', fullFd, 'ignore'] })
  const lineCount = () => { try { return readFileSync(fullLog, 'utf8').split('\n').length } catch { return 0 } }
  process.on('exit', () => { try { logProc.kill() } catch {} })

  for (const name of selected) {
    const dir = join(runDir, name)
    mkdirSync(dir, { recursive: true })
    const startLine = lineCount()
    process.stdout.write(`▶ ${name} ... `)
    try {
      const r = await runScenario(name, device, dir)
      r.results.forEach((x) => process.stdout.write(`\n   ${x.pass ? '✓' : '✗'} ${x.msg}`))
      // 三态而不是两态。
      //
      // 有些场景(candidate-center / layer-chips)的判定**全部**交给 AI 判卷,
      // 自己一条确定性断言都没有。这种场景在 `AITEST_API_KEY` 没配时
      // `results` 必然为空,而旧写法 `results.length > 0 && …` 会把它判成 **FAIL**。
      //
      // 后果不是「多报一个失败」,而是**这两个场景永远红着** ——
      // 而永远红的场景会教会人无视红灯,连带真的失败也被看过去。
      // 所以这里先记成 `pending`,判卷之后把答案附回来(见下面)。
      const pending = r.results.length === 0
      const passed = !pending && r.results.every((x) => x.pass)
      console.log(`\n   ⇒ ${pending ? 'PENDING(待判卷)' : passed ? 'PASS' : 'FAIL'}`)
      report.scenarios.push({ name, passed, pending, results: r.results, shots: r.shots })
    } catch (e) {
      console.log(`\n   ⇒ ERROR: ${e.message}`)
      report.scenarios.push({ name, passed: false, pending: false, results: [ok(false, e.message)], shots: [] })
    } finally {
      // 每个场景留**自己那一段**的完整 logcat(见上面常驻 logcat 的说明)。
      // 取不到就算了 —— 不能因为存证据而让场景失败。
      try {
        writeFileSync(join(dir, 'ime.log'), readFileSync(fullLog, 'utf8').split('\n').slice(startLine).join('\n'))
      } catch { /* ignore */ }
    }
  }

  report.crashesDuringRun = device.crashCount() - crashesBefore

  // AI 视觉判卷:有 API Key 时对每张截图执行场景定义的 judge 问题
  const { judgeEvidence } = await import('./judge.mjs')
  await judgeEvidence(report, runDir, device)
  // **不**把判卷答案再猜成 pass/fail。
  //
  // `judge.mjs` 只产出自由文本(「共 5 个候选,「你」在正中间」),没有可用的判定。
  // 用正则去猜「答案里有没有『不』」会造出**假信心** —— 中文答复里出现「不」太常见了,
  // 于是「是」可能被判成失败、「不是」可能被判成通过。
  //
  // 所以待判卷的场景就**一直是 pending**:把答案附在结果里给人/代理看,
  // 由人读。要自动判定,得在场景的 judge 定义里加一条明确的判据,而不是猜。
  for (const sc of report.scenarios) {
    if (!sc.pending) continue
    const answers = (report.judge ?? []).filter((j) => j.scenario === sc.name && j.answer)
    if (answers.length) sc.results = answers.map((j) => ok(true, `判卷(${j.shot}): ${j.answer}`))
  }

  report.finishedAt = new Date().toISOString()
  const reportFile = join(runDir, 'report.json')
  writeFileSync(reportFile, JSON.stringify(report, null, 2))

  const total = report.scenarios.length
  const passed = report.scenarios.filter((s) => s.passed).length
  const skipped = report.scenarios.filter((s) => s.pending).length
  // **分母只算真的判过的**。把待判卷的算进分母,等于宣布「这次跑不可能全绿」,
  // 于是没人再看这个数字 —— 一个永远达不到的指标等于没有指标。
  console.log(`\n=== ${passed}/${total - skipped} 场景通过${skipped ? `(${skipped} 个待判卷)` : ''} ===`)
  console.log(`证据: ${runDir}`)
  console.log(`报告: ${reportFile}`)
  if (report.crashesDuringRun > 0) console.log(`⚠ 测试期间发生 ${report.crashesDuringRun} 次崩溃!`)
  process.exitCode = passed === total - skipped && report.crashesDuringRun === 0 ? 0 : 1
}

main().catch((e) => { console.error(e); process.exit(1) })
