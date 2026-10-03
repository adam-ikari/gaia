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
import { execFileSync, execSync, spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
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
    this.shell('wm dismiss-keyguard')
    this.shell('svc power stayon true') // USB 供电期间保持常亮,防 10s 熄屏
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
    const out = this.shell(`logcat -d -s ImeService:* CandidateBar:* 2>/dev/null | grep -E "${pattern}" | tail -20`)
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
  const preflight = async () => {
    device.wake()
    device.ime()
    device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
    await sleep(2500)
    device.tap(700, 400)
    await sleep(2500)
    const fg = device.foreground()
    return { fg, shown: device.imeShown() }
  }
  let pf = await preflight()
  if (!pf.fg.includes('PlaygroundActivity') || !pf.shown) pf = await preflight() // 失败重试一次
  if (!pf.fg.includes('PlaygroundActivity') || !pf.shown) {
    console.error(`✗ 设备未就绪:前台=${pf.fg},输入法显示=${pf.shown}`)
    console.error('  可能:安全锁屏(需手动解锁)、主屏熄灭或用户正在使用手机。处理后重跑。')
    process.exitCode = 2
    return
  }

  for (const name of selected) {
    const dir = join(runDir, name)
    mkdirSync(dir, { recursive: true })
    process.stdout.write(`▶ ${name} ... `)
    try {
      const r = await runScenario(name, device, dir)
      r.results.forEach((x) => process.stdout.write(`\n   ${x.pass ? '✓' : '✗'} ${x.msg}`))
      const passed = r.results.length > 0 && r.results.every((x) => x.pass)
      console.log(`\n   ⇒ ${passed ? 'PASS' : 'FAIL'}`)
      report.scenarios.push({ name, passed, results: r.results, shots: r.shots })
    } catch (e) {
      console.log(`\n   ⇒ ERROR: ${e.message}`)
      report.scenarios.push({ name, passed: false, results: [ok(false, e.message)], shots: [] })
    }
  }

  report.crashesDuringRun = device.crashCount() - crashesBefore
  report.finishedAt = new Date().toISOString()
  const reportFile = join(runDir, 'report.json')
  writeFileSync(reportFile, JSON.stringify(report, null, 2))

  // AI 视觉判卷:有 API Key 时对每张截图执行场景定义的 judge 问题
  const { judgeEvidence } = await import('./judge.mjs')
  await judgeEvidence(report, runDir, device)

  const total = report.scenarios.length
  const passed = report.scenarios.filter((s) => s.passed).length
  console.log(`\n=== ${passed}/${total} 场景通过 ===`)
  console.log(`证据: ${runDir}`)
  console.log(`报告: ${reportFile}`)
  if (report.crashesDuringRun > 0) console.log(`⚠ 测试期间发生 ${report.crashesDuringRun} 次崩溃!`)
  process.exitCode = passed === total && report.crashesDuringRun === 0 ? 0 : 1
}

main().catch((e) => { console.error(e); process.exit(1) })
