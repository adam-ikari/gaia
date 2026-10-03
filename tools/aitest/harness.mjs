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
    //
    // **按墙钟兜底,不按次数**:`dumpsys window policy` 在这个模拟器上不便宜,
    // 原来 60 次迭代实测要 ~20s/场景,而 `ci-capture.sh` 给每个场景的
    // `timeout 300` 是硬上限 —— 白烧掉 1/15 的预算。
    // 6s 还没干净就放弃并**把看到的字段打出来**:那时候需要的是信息,不是继续等。
    const deadline = Date.now() + 6000
    let last = ''
    while (Date.now() < deadline) {
      last = this.shell('dumpsys window policy 2>/dev/null')
      const dreaming = /mDreamingLockscreen=true/.test(last)
      const keyguard = /isStatusBarKeyguard(ing)?=true/.test(last) || /mShowingLockscreen=true/.test(last)
      if (!dreaming && !keyguard) return
      execSync('sleep 0.25')
    }
    const fields = last.split('\n').filter((l) => /Lockscreen|Keyguard|keyguard/.test(l)).slice(0, 4).join(' ; ')
    console.warn(`⚠ 等了 6s 锁屏还没走干净,后面的场景可能继续被抢焦点。\n  看到的字段: ${fields || '(一个都没有)'}`)
  }

  ime() { return this.shell('ime set dev.hwkbd.ime/.service.ImeService && echo OK').includes('OK') }

  /**
   * 就绪探针:**敲一个 `n`,看 IME 有没有真的建立组词**。
   *
   * ## 为什么不能用「窗口在前台」当就绪判据
   *
   * 原来只看 `mCurrentFocus` 与 `mInputShown`,两个都是**代理指标**。
   * IME 还没真正挂到编辑框上时,`input keyevent` 的按键会被系统当**普通文本**
   * 直接送进 EditText —— 症状是拿到原始字母(`editText="nni"`)而不是候选词,
   * **输入法看起来完全没参与**,而所有代理指标都是绿的。
   *
   * 探针直接问 IME:`updateSelection … comp=<数字>`(组词区间非空)才算就绪。
   * `comp=-1..-1` 是没有组词,不算信号。
   *
   * ## 为什么探针必须放在**每个场景的前置**里,而不是只在 preflight 跑一次
   *
   * 场景前置会 `am start` 重启 PlaygroundActivity,焦点要重新建立,
   * 那个窗口里按键照样会绕过 IME。preflight 证明过一次「设备就绪」,
   * **不能**推论出第 5 个场景前置之后仍然就绪。
   * 这就是 compose-space(第一个跑的场景,离 preflight 最近)反而失败的原因 ——
   * 它的输入直接以普通文本进了框。
   *
   * 探针用完必须**把输入框清干净**:多一个残留字母会让后面所有 `editText()`
   * 断言读到脏数据(实测 `editText="nni"` 里的第一个 n 就是探针留下的)。
   */
  /**
   * 等引擎真正就绪 —— 就是这个探针本身,**不要**另做一个「等就绪」步骤。
   *
   * librime 的首次部署(4.6MB `essay.txt` + 890KB `luna_pinyin.dict.yaml`
   * 转 `.ocd2` 并建 LevelDB)是**异步**的。那段时间里:
   *   - `state.raw` 正常累积、`updateSelection comp=0..2 raw=ni` 正常;
   *   - `imeShown()` 为 true、前台是 PlaygroundActivity;
   *   - 但 `state.candidates` **是空的** ⇒ `handleSpace()` 走 `commitRaw()`,
   *     空格上屏的是原始字母。
   *
   * **所有代理指标都是绿的**,只有引擎自己知道它没准备好。
   *
   * ## 这里踩过的坑:另做一个「等引擎就绪」步骤是**不可能成功**的
   *
   * 我先写了个 `waitEngineReady()`,在 preflight 开头轮询 `candidates[…]: n>=1`。
   * 它永远等不到 —— 因为那条日志**只在按过键之后**才出现,而那时一个键都还没按。
   * 结果每个场景白等 2 × 120s,10 个场景把 job 的 45 分钟吃光。
   *
   * 所以**等待必须由「按一个键」触发**,而按键正是探针干的事。
   * 一个探针就够了:它同时是「触发查询」+「等候选出现」+「确认按键真的到了 IME」。
   *
   * @param timeoutMs 给多长。首次部署在 CI 模拟器上真的要几十秒,
   *   给少了会变成随机失败;但也别给太大 —— 这段时间是每个场景进程都要付的。
   */
  probeIme(timeoutMs = 90000) {
    this.clearField()
    this.shell('logcat -c')
    this.key(42) // KEYCODE_N
    // **两个条件都要满足**(2026-10-03 加第二个)。
    //
    // 原来只验「组词建立」(`comp=<数字>`),那证明的是**按键到达了 IME**。
    // 但它**不证明引擎给出了候选** —— 而这两个是独立的:
    // librime 的首次部署(装 4.6MB 词库)是异步的,那段时间里
    // `state.raw` 正常累积、组词正常、`comp=0..2 raw=ni` 正常,
    // 而 `state.candidates` 是**空的**。
    //
    // 于是 `handleSpace()` 走 `commitRaw()` 分支,空格上屏的是原始字母:
    // `compose-space` 拿到 `editText="ni"` 而不是「你」。
    // 探针当时是**绿的** —— 它问的问题太弱。
    //
    // 判据用 `candidates[…]: … n=<非零>`(见 ImeController.logCandidates)。
    const deadline = Date.now() + timeoutMs
    let ok = false
    while (Date.now() < deadline && !ok) {
      const l = this.shell('logcat -d -s ImeService:* 2>/dev/null')
      ok = /updateSelection[^\n]*comp=(?!-1)/.test(l) && /candidates\[[^\]]*\]:[^\n]* n=[1-9]/.test(l)
      if (!ok) execSync('sleep 1')
    }
    this.clearField()
    return ok
  }

  /** 把编辑框清空:MOVE_END + 若干次 DEL。探针与场景前置都要用,字段会跨场景累积。 */
  clearField() {
    this.shell('input keyevent 123') // KEYCODE_MOVE_END
    for (let i = 0; i < 12; i++) this.shell('input keyevent 67') // KEYCODE_DEL
  }
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

  // 前置:唤醒 + 切回本输入法 + 打开实验场 + **验 IME 真的在处理输入**
  device.wake()
  device.ime()
  device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
  await sleep(2000)
  device.tap(700, 400)
  await sleep(2500)
  // 探针放这里而不是只在 preflight —— 理由见 [Device.probeIme]。
  // 不通过就让这个场景明确地失败,而不是产出一个「原始字母进了框」的假象。
  // 90s 与 preflight 一致:第一个跑的场景要等 librime 首次部署完词库。
  // 注意 `ci-capture.sh` 是**每个场景起一个 node 进程**,所以每个进程都会付这个等待;
  // 好在部署是一次性的,后面的场景第一次轮询就过。
  if (!device.probeIme(90000)) throw new Error('探针:IME 没能给出候选(按键没到 IME,或词库还没部署完 —— 两种都会让断言读到原始字母)')

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

  const preflight = async () => {
    device.wake()
    device.ime()

    device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
    await sleep(2500)
    device.tap(700, 400)
    await sleep(2500)
    const fg = device.foreground()
    const shown = device.imeShown()
    // 探针自己就是「等引擎就绪」那一步(见 [Device.probeIme] 的说明)。
    // 首给 90s:词库转换在 CI 模拟器上真的要几十秒。
    const processing = shown ? device.probeIme(90000) : false
    return { fg, shown, processing }
  }
  const ready = (p) => p.fg.includes('PlaygroundActivity') && p.shown && p.processing
  let pf = await preflight()
  if (!ready(pf)) pf = await preflight() // 失败重试一次
  if (!ready(pf)) {
    console.error(`✗ 设备未就绪:前台=${pf.fg},输入法显示=${pf.shown},IME在处理输入=${pf.processing}`)
    console.error('  可能:安全锁屏(需手动解锁)、主屏熄灭、用户正在使用手机,')
    console.error('  或编辑框没真正聚焦(此时按键会作为普通文本进 EditText,而不是走 IME)。')
    console.error('  或词库还没部署完 —— 探针会等 90s 再判,到这里说明 90s 都没给出候选。')
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
  // **`unref()` 是必须的**(2026-10-03 踩过:每个场景后面多等 282 秒才退出)。
  //
  // spawn 出来的子进程默认会让 node 的事件循环保持「有活干」,于是 node **不肯退出** ——
  // 而 `process.on('exit')` 里那句 `kill()` 是**永远不会被执行到**的,因为
  // exit 事件本身就要求事件循环先空下来。死锁。
  //
  // 症状极有欺骗性:所有输出都已经打印完了(汇总、证据路径、报告路径全在),
  // 日志尾部看起来一切正常,只是进程赖着不走 —— 于是每个场景白等 4 分半,
  // 而 job 上限是 45 分钟,6 个场景就顶满了。
  logProc.unref()
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

  // 先收摊再汇总:子进程还在写 full.log,让它活得比汇总更久没有意义。
  try { logProc.kill() } catch {}

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
  const judged = total - skipped
  const verdict = judged === 0
    ? `=== 无场景含确定性断言(${skipped} 个全待判卷) ===`
    : `=== ${passed}/${judged} 场景通过${skipped ? `(${skipped} 个待判卷)` : ''} ===`
  console.log(`\n${verdict}`)
  console.log(`证据: ${runDir}`)
  console.log(`报告: ${reportFile}`)
  if (report.crashesDuringRun > 0) console.log(`⚠ 测试期间发生 ${report.crashesDuringRun} 次崩溃!`)
  process.exitCode = passed === total - skipped && report.crashesDuringRun === 0 ? 0 : 1
}

main().catch((e) => { console.error(e); process.exit(1) })
