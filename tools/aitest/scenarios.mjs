/**
 * 功能测试场景定义。每个场景 = 前置(引擎自动:唤醒/切输入法/开实验场/点输入框)+ steps。
 * ctx: { device, dir, shot(label), expect(pass,msg), expectLog(log,...expected), run(fn) }
 * 断言策略:能用文本/日志的用确定性断言;依赖视觉排布的用 judge(AI 视觉判卷)。
 * 注意:组词中 IME 窗口全屏盖住 app,editText() 仅在无组词时可用。
 */

/**
 * 候选栏那一行的 y 坐标 —— **从设备自己读**,不再硬编码。
 *
 * ## 为什么原来那个 1397 是错的(2026-10-08 真机实测)
 *
 * 原来写死 1397,注释说「实测:槽0 是 175,1397」。那个实测是在**模拟器**上做的。
 * 真机(Titan 2 / 1440x1440 @ 400dpi)上同样一次 `swipe(1100,1397,400,1397,300)`:
 *
 *   y=1337 -> touch DOWN: y=1277 contentTop=1260 inUi=true
 *             page end: by=finger slide=672 commit=true page=1/9     ✅ 翻了
 *   y=1397 -> touch DOWN: y=1337 contentTop=1260 inUi=true
 *             page end: by=fling  slide=0   commit=false               ❌ 没翻
 *
 * 两个都 `inUi=true` —— **问题不是「算不算 UI 区」**。差别在:
 * IME 窗口只有 `winH=1380`(状态栏占顶部 60),而 y=1397 落在
 * `[1380, 1440]` 那 60px 的**系统手势/导航区**里 ⇒ MOVE 帧在到达候选栏之前
 * 就被系统吃掉,`slide=0`,然后被判成 `by=fling`。
 *
 * ## 判据
 *
 * 取**输入法窗口底边减去一点余量**,而不是屏幕高度。后者正是这个 bug 的成因:
 * 「IME 窗口」与「屏幕」不是同一个矩形,而 1397 = 屏幕高 - 43,恰好掉进
 * 窗口外那 60px。
 *
 * `winH` 由 `ImeRootView` 自己打在 logcat 里(`ImeRoot: touch DOWN: … winH=`),
 * 所以读它比猜可靠 —— 换设备、换状态栏高度都不必再改这里。
 * 读不到就退回「屏幕高 - 103」(1440-103=1337,即本次实测值)。
 */
export async function candidateRow(c) {
  // ## 这个函数**不许有任何副作用**
  //
  // 第一版是「打一次 swipe 去拿 ImeRoot 的 DOWN 埋点」,两处副作用直接把测试搞坏:
  //
  //  1. `logcat -c` —— **清空了场景自己的日志**。而场景随后用
  //     `takeLog('updateSelection…')` 做断言,于是断言读的是**探测之后**的日志,
  //     场景真正的输入过程已经不在里面。
  //  2. 那次探测 `swipe` 本身**留下了一个未完成的翻页手势**(只走了 120ms、
  //     方向朝左)。而实测证明:一次手势没走完时,下一次手势会带着它的
  //     `pageFrom` / `pageTo` 起步 —— 于是 `page-flip` 这个原本全绿的场景
  //     也跟着红了(`page begin` 之后直接断,没有 `page end`)。
  //
  // ⇒ 所以改成**纯读取**:只看屏幕与密度,不发任何事件、不碰 logcat。
  // IME 窗口高度 = 屏幕高 − 顶部状态栏 − 底部系统区。
  try {
    const size = c.device.shell('wm size')
    const dens = c.device.shell('wm density')
    // 只认 Physical,忽略 Override —— override 是调试残留,不是这台设备的真实布局。
    const phys = /Physical size:\s*(\d+)x(\d+)/.exec(size)
    const d = /Physical density:\s*(\d+)/.exec(dens)
    const h = phys ? Number(phys[2]) : 1440
    const dpi = d ? Number(d[1]) : 400
    // ## 底部系统区的实测值 = **41.25dp**,不是「猜一个 40dp」
    //
    // Titan 2 实测:能翻页的 y=1337,落在系统区里的 y=1397 不能翻 ⇒
    // 底部不可用区 = 1440 − 1337 = **103px = 41.25dp**。
    //
    // 我第一版按 `(24dp + 40dp)` 算,得出 1280 —— **比 1337 高了 57px,
    // 落在 contentTop(实测 1260)之上**,于是手势被当成「不在 UI 区」,
    // `page-flip` 与 `settings-open` 双双从绿变红。
    //
    // **教训**:这个数我是**猜**的,而代码里它表现得像测出来的。
    // 「一个我没量过的数写进了判据」—— 今天第三次犯这个错。
    // 下面的 41.25 是从上面那两次实测反推的,不是设计值。
    const bottomInsetDp = 41.25
    return h - Math.round(bottomInsetDp * (dpi / 160))
  } catch { return 1337 }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// Android KEYCODE_*:字母是 29..54 的连续段(A=29),i=37/n=42 可反推校验。
const K = {
  a: 29, b: 30, c: 31, d: 32, e: 33, f: 34, g: 35, h: 36, i: 37, j: 38,
  k: 39, l: 40, m: 41, n: 42, o: 43, p: 44, q: 45, r: 46, s: 47, t: 48,
  u: 49, v: 50, w: 51, x: 52, y: 53, z: 54,
  space: 62, shift: 59, alt: 58, ctrl: 113, del: 67,
}
const last = (s) => (s ? s[s.length - 1] : '')
/** 字母 → Android KEYCODE_*(A=29 起的连续段)。 */
const kcOf = (ch) => K[ch.toLowerCase()]

/**
 * 「横滑之后页码没动」时,**说出是哪一环没动**(2026-10-05)。
 *
 * ## 为什么值得写这十几行
 *
 * 上一轮这个场景红了,而现场是:`page begin 0→1` 与 `end: 提交 commit=true`
 * 都在,可之后 `select:` 显示 `page=0/9`。
 *
 * 当时能确定的是「**view 说它提交了**」,不能确定的是「**controller 有没有真的翻**」——
 * 中间那一步(`pageStep`)**不打任何日志**。于是两种解释都能自圆其说:
 * 「提交了但页码被夹回 0」与「压根没提交」,谁也排除不掉,查不下去。
 *
 * 这个函数把三处埋点一起读出来给出结论:
 * - `pageStep: … page=A→B/N` ⇒ controller **真的翻了**,`B` 就是权威页码
 * - `end: 判定提交 … willSend=false` ⇒ view 判定提交但**没发** `onPage`
 *   (被 [pageCommitted] 挡了 —— 那就去看 `page begin#` 里的那个标记)
 * - `page begin#` 出现两次 ⇒ 一次手势起了两次手(为什么?)
 *
 * ⇒ 失败时消息直接指出断点,**不用再翻 full.log 人工推理**。
 */
const describePageFailure = (page, pageLog) => {
  const steps = [...pageLog.matchAll(/pageStep: delta=(-?\d+) page=(\d+)→(\d+)\//g)].map((m) => m[0])
  const sends = [...pageLog.matchAll(/end: 判定提交.*willSend=(true|false)/g)].map((m) => m[1])
  const begins = pageLog.split('\n').filter((l) => l.includes('page begin#:')).length
  const facts = [
    `最终 page=${page}(应为 >0)`,
    `pageStep 调用 ${steps.length} 次${steps.length ? ': ' + steps.join(' / ') : ' ⇒ **controller 侧压根没被要求翻页**'}`,
    sends.length ? `view 判定提交时 willSend=${sends.join(',')}(false ⇒ 被 pageCommitted 挡在 onPage 之前)` : 'end 判定提交: **无记录** ⇒ 抬手时没判定过线',
    `page begin# ${begins} 次${begins > 1 ? '(一次手势起了两次手)' : ''}`,
  ]
  return facts.join('\n    · ')
}

export const scenarios = [
  {
    // 「横向滑动候选词没有打断动画」—— 要证明**打断真的发生了**。
    //
    // ## 第一版这个场景的三处错(都白跑了一轮)
    //
    // 1. **没组词**:preflight 打完字又退格清空了,组词没在进行 ⇒ 输入法窗口
    //    根本没显示,我划的是空白屏幕 —— 日志里一条触摸事件都没有。
    // 2. **坐标错了**:y=1100 是**键盘面**(external drive),候选行在 y≈1397。
    // 3. **没过静默窗**:刚打完字就划,`swipeArmed` 为 false ⇒ 不接管。
    //
    // ## 为什么不需要 motionevent
    //
    // 打断的定义是「上一段动画还没走完就开始下一次手势」。`input swipe` 虽然阻塞,
    // 但它**抬手之后**弹簧才开始跑(最长 600ms),而下一条 swipe 的 shell 往返
    // 只有百来毫秒 —— 顺序执行天然就落在动画窗口内。
    // `input motionevent` 逐条投递看着更可控,却因为上面第 1 条整个手势没打出去。
    name: 'page-interrupt-live',
    desc: '动画在飞时横滑要真的打断它',
    steps: [
      async (c) => {
        const ROW = await candidateRow(c)
        const sw = (x1, x2, ms) => c.device.swipe(x1, ROW, x2, ROW, ms)
        // 先组词:候选行必须存在
        c.device.key(K.n, K.i)
        await sleep(500) // > 300ms 静默窗,否则 swipeArmed=false 不接管
        c.device.shell('logcat -c')
        sw(1100, 400, 300) // 第一段:翻页,抬手后弹簧开始走
        // **两段之间必须有间隔**(2026-10-08 真机实测)。
        //
        // `input swipe x1 y1 x2 y2 ms` 是**一个命令内从按下到抬起**,
        // 而 Node 的 `execFileSync` 同步阻塞 ⇒ 两段背靠背时,
        // **第二段的按下发生在第一段的抬起之前** —— 物理上不成立。
        //
        // 实测症状:两段只隔 118ms,`pageDragging` 全程 true、
        // `pageCommitted` 在第一段途中就变 true,`springRunning` **全程 false**,
        // 于是「动画在飞时打断它」在真机上根本没发生,`interrupt:` 打不出来。
        //
        // 真手指必然是:抬手 →(动画在飞)→ 再按下。这个 sleep 就是那段「在飞」。
        await sleep(150)
        sw(400, 1100, 300) // 第二段:动画在飞时立刻反向划 —— 必须打断它
        await sleep(1000)
        c.shot('after-interrupt')
        const log = c.device.takeLog('(SpringChannel|takeover): .*')
        c.expect(/start:/.test(log), `第一段应起跑弹簧(实际: ${JSON.stringify(log.slice(0, 300))})`)
        c.expect(/interrupt:/.test(log), '动画在飞时的第二次手势应打出 interrupt(没有它就是「没有打断」)')
      },
    ],
  },

  {
    // 「拼音消费按实际选词消费」的真引擎验证。
    //
    // ## 这个场景是 2026-10-05 那个真机 bug 的判据
    //
    // 上一版只断言「窗口还在」—— 那条在 bug 存在时**照样是绿的**:
    // 缓冲被截成 `n` 之后组词仍在进行,窗口当然还在。
    // 「还在」只区分了「全清」与「没全清」,**分不出「截对了」与「截错了」**。
    //
    // 真机日志实测(修之前):
    //     consume: preview="你好" word="你"
    //     query#18 from=选词后剩余 raw="n"      ← 应该是 "hao"
    // `nihao` 选「你」(只覆盖 `ni`)⇒ 吃掉 2 个字符 ⇒ 剩 `hao`。
    // `n` 是把 preview 里那个**汉字**的长度当成了拼音长度。
    //
    // ## 断言写成关系式,不钉死某个词
    //
    // 剩下的是「原 raw 去掉开头 consumed 个字母」—— 这条对**任何**部分消费都成立。
    // 「槽 0 恰好是那个只覆盖 ni 的词」依赖词库与用户词频、会漂,钉死 `hao`
    // 就会变成一条**测词库**的断言:词频一变它就红,而功能其实没坏。
    // 关系式则三种坏法各被抓一处(全清 / 拿汉字长度当拼音长度 / 截错方向),
    // 且与具体是哪个词无关。
    //
    // 触发方式用 Shift(显示槽 0)。注意槽 0 在页内是第 4 个逻辑候选,
    // 而「你」恰好只覆盖 `ni` —— 所以这个组合稳定地产生「部分消费」,
    // 也就是 bug 唯一会露头的那条路。
    //
    // ## pattern 必须**全 ASCII**
    //
    // `takeLog` 把 pattern 塞进 `adb shell ... | grep -E "<pattern>"`。
    // pattern 里的**非 ASCII 会在设备那一侧被吃掉**:Android 的 `sh` + `grep`
    // 按设备 locale 处理字节,而「选词后剩余」是 5 个汉字 = 15 字节 UTF-8 ——
    // 传过去就匹配不上了(2026-10-05 实测:日志里那行明明在,`takeLog` 却没收到)。
    //
    // 所以这里用 **`from=`** 这个 ASCII 前缀(`query#N from=… raw=…`),
    // 真正的中文标签留给**上面的 JS 正则**去取 —— JS 在本地跑,没有编码问题。
    //
    // 代价:`from=` 会顺带捞到别的 `from=` 行(字符键/退格/部署完成回调…)。
    // 无妨:下面的正则只取带 `raw="` 的那一条,且断言要的是**关系**。
    //
    // 飞字动画期间窗口也在,断言前等它飞完(与其它场景同样 900ms)。
    name: 'consume-partial',
    desc: '选完候选后剩下的拼音留在缓冲里',
    steps: [
      async (c) => {
        c.device.shell('logcat -c')
        for (const ch of 'nihao') c.device.key(kcOf(ch))
        await sleep(1200)
        c.shot('composing-nihao')
        c.expect(c.device.imeShown(), '组词中输入法窗口显示')
        c.device.key(K.shift) // 显示槽 0
        await sleep(900)
        // **pattern 必须全 ASCII**(见这条下面的说明)。
        const log = c.device.takeLog('consume:|select:|from=')
        c.shot('after-select')
        // ① 引擎报出「吃掉了几个**输入**字符」。
        c.expect(
          /consume: .*consumed=[1-9]/.test(log),
          `应打出非零的 consumed(实际日志: ${JSON.stringify(log.slice(0, 300))})`,
        )
        // ② 决定性的那条:**剩下的 raw 必须正好是「原 raw 去掉开头 consumed 个字母」**。
        //
        //   写成关系式而不是钉死 "hao",是因为「槽 0 恰好是那个只覆盖 ni 的词」
        //   依赖词库与用户词频、会漂。关系式对**任何**部分消费都成立,
        //   而三种坏法各被抓一处:
        //     · 全清(旧行为)        → raw_after 为空,而 consumed < len ⇒ 红
        //     · 把汉字长度当拼音长度  → raw_after 对不上 tail      ⇒ 红
        //     · 截错方向(留前缀)    → raw_after 对不上 tail      ⇒ 红
        const sel = /select: zone=\d+ li=\d+ global=\d+ word="[^"]*" page=\d+\/\d+ raw="([^"]*)"/.exec(log)
        const consumed = Number(/consume: .*consumed=(-?\d+)/.exec(log)?.[1] ?? -1)
        const after = /from=选词后剩余 raw="([^"]*)"/.exec(log)?.[1]
        c.expect(!!sel, `应打出 select 埋点(含 raw)(实际: ${JSON.stringify(log.slice(0, 300))})`)
        c.expect(after !== undefined, `应打出「from=选词后剩余 raw=…」那行(实际: ${JSON.stringify(log.slice(0, 300))})`)
        if (sel && after !== undefined && consumed > 0) {
          const before = sel[1]
          c.expect(
            consumed < before.length,
            `本用例要求**部分**消费(consumed=${consumed} 应小于 raw 长度 ${before.length};相等就说明整串被吃掉了)`,
          )
          c.expect(
            after === before.slice(consumed),
            `剩下的拼音应是「原 raw 去掉开头 ${consumed} 个字母」= ${JSON.stringify(before.slice(consumed))},实际 ${JSON.stringify(after)}`,
          )
        }
        // ③ **剩余拼音必须真的有候选**(2026-10-06 补,漏了这条就发出去一个空候选栏)
        //
        // ①②③ 之前只钉了「缓冲里剩的是不是对的拼音」。但用户看到的是**候选栏**,
        // 不是缓冲 —— 而 v0.7.10 的症状正是:缓冲里明明白白写着 `hao`,
        // 候选栏却一个词都没有(用户原话「拼音可以部分消费,但是没有剩余拼音的
        // 候选词出现」)。缓冲对而候选空,是 ①②③ 全绿时**照样能过**的一种坏法。
        //
        // 为什么这条当时没被发现:partial consumption 与「剩余部分有候选」在
        // **单音节下同形**(吃掉全部 ⇒ 没有剩余 ⇒ 两边都「空」,看着一致)。
        // 跟 rime-candidate-consumption 那条教训是同一个:判据要在最常见的情形下
        // 也分得清,不能只在多音节下才成立。
        //
        // 日志行格式:`query#N from=选词后剩余 raw="hao" n=45`
        const nCand = Number(/from=选词后剩余 raw="[^"]*" n=(\d+)/.exec(log)?.[1] ?? -1)
        c.expect(
          nCand > 0,
          `剩余拼音应有候选(实际 n=${nCand})。raw 与 n 都在上面那行里`,
        )
        // ④ 组词缓冲还在 ⇒ 输入法窗口也还在。
        c.expect(c.device.imeShown(), '选完仍有组词缓冲 ⇒ 输入法窗口应还在')
      },
    ],
  },

  {
    // 「翻页后选的候选与上屏的不一致」—— 2026-10-05 用户报障的那个 bug。
    //
    // ## 为什么第 0 页永远验不出来
    //
    // 上屏用的是我们自己的候选表(`state.candidates`),界面显示的也是它 ——
    // 所以**上屏的字从来不会错**。错的是给引擎的**下标**:
    // 状态机传的是**页内**下标(0..4),而 librime 的 `Select(index)` 索引的是
    // **整份菜单**(0..44)。两者只在第 0 页相等。
    //
    // 症状因此是「翻过页之后**选完缓冲不对**」,而不是「上错字」。
    //
    // ## 判据是 `global=` 那一段
    //
    // `select: … li=N global=M page=P/…`,恒等式 `M == N + P * 5`。
    // 修之前压根没有 `global` 这个字段,所以这条必红。纯文本断言,不依赖截图。
    //
    // ## 第一版这个场景三处错(白跑一轮)
    //
    // 1. **翻页没生效**,而我把「翻没生效」写成了断言失败的样子。
    //    原因:`pageStep` **不打任何日志** —— 它只改 `state.page` 再 render。
    //    于是「翻页成功」与「按键没进 IME」在 logcat 里**同形**(都没有痕迹)。
    //    第一版去断言 `updateSelection`,那是**组词区**的回调,翻页不改组词区,
    //    所以它永远不该出现 —— 我等于断言了一条不存在的东西。
    //    ⇒ 现在不中间断言,直接看 `select:` 那行里的 `page=P`,并要求 `P > 0`:
    //    **翻没翻成功由 `page` 自己说**,不需要额外埋点。
    // 2. DPAD_RIGHT 在**组词态**下会不会真的翻页,取决于它有没有进到 IME。
    //    证据里两次按键**零日志**、`page` 停在 0 ⇒ 没进。
    //    这里改成**用候选栏横滑**翻页(那条路有 `page begin:` 埋点),
    //    顺带验「翻页手势真到了候选行」。
    // 3. 忘了 `probeIme` 会把输入框清空、而**上一个场景的残留字**还在字段里,
    //    于是 `select-global-index` 之后 `compose-space` 读到脏数据。
    //    每个场景结尾必须清干净字段(见 `clearField`)。
    name: 'select-global-index',
    desc: '翻页后给引擎的仍是全局下标',
    steps: [
      async (c) => {
        const ROW = await candidateRow(c)
        // `ni` 前缀命中 45 个候选 ⇒ 9 页,一次横滑就到第 2 页。
        c.device.key(K.n, K.i)
        await sleep(900)
        c.expectLog(c.device.takeLog('updateSelection.*comp=[0-9]'), 'updateSelection')
        c.device.shell('logcat -c')
        await sleep(400) // > 300ms 静默窗,否则候选行不接管这次横滑
        // 横滑翻页(有 `page begin:` 埋点 ⇒ 能确认手势真的到了候选行)。
        c.device.swipe(1100, ROW, 400, ROW, 300)
        await sleep(1000)
        c.shot('after-page-swipe')
        // **翻页那一段的日志单独留一份**(2026-10-05)。
        //
        // 上一轮这里直接 `logcat -c` 清掉了,而翻页到底发没发生**只有这里有答案**:
        // `pageStep`(controller 侧,页码真正落地的地方)此前**完全静默**,
        // 候选栏那边的 `page end` 打的又是 view 自己那份字段、而 render 经
        // RenderPump 延后 ⇒ 那些 `page=` 不能当作 state.page 的证据。
        // 结果就是「view 说提交了、页码却是 0」这个现场**推不出自洽解释**。
        //
        // 现在 pageStep / page begin# / end: 判定提交 三处都有埋点,
        // 所以这个场景失败时能**自己说出是哪一环没动**,不用再猜。
        const pageLog = c.device.takeLog('page begin#|end: |pageStep:')
        c.device.shell('logcat -c')
        c.device.key(K.space) // 显示槽 2 = 当前页的首选
        await sleep(900)
        const log = c.device.takeLog('select: zone=.*')
        c.shot('after-paged-select')
        const m = /select: zone=(\d+) li=(\d+) global=(\d+) .*?page=(\d+)\//.exec(log)
        c.expect(m, `应打出 select 埋点(含 li/global/page)(实际: ${JSON.stringify(log.slice(0, 300))})`)
        if (m) {
          const [, , li, global, page] = m
          const p = Number(page)
          // **先验「确实翻过页」**:这一条把「下标算错」与「压根没翻页」分开。
          // 少它的话,page=0 时恒等式 M==N+0*5 恒成立,断言会**假绿**。
          c.expect(p > 0, describePageFailure(p, pageLog))
          const want = Number(li) + p * 5
          c.expect(Number(global) === want, `global 应等于 li + 页号×页宽 = ${want},实际 ${global}(page=${p})`)
        }
        // **清干净字段**:上一个场景的残留字会让后面 `editText()` 断言读到脏数据
        // (2026-10-05 实测:select-global-index 选完留下的字让 compose-space 拿到
        // editText="昵" 而非 "你")。这条与 harness 的 `probeIme` 同理。
        c.device.key(K.del, K.del, K.del)
        await sleep(400)
      },
    ],
  },

  {
    // 2026-10-04 用户「自從換過輸入法一直是繁體中文,必須輸入簡體中文」。
    //
    // ## 为什么不能用「中国 / 中國」这种词
    //
    // 首选候选由词典顺序 + 用户词频决定,不是我们能定的。
    // 上一版用 `zhongguo`,实际首选是「中过」而不是「中国」——
    // 断言挂在词的选择上,而不是挂在「有没有转简体」这件事实上。
    //
    // ## 这两个音节是决定性的
    //
    // 它们的**词典首条就是繁体**,所以转换关掉时输出必然是繁体:
    //   diu  → 词典第 6877 行 丟,第 6880 行 丢(首条是繁体)
    //   shei → 词典第 26317 行 誰(唯一的单字候选)
    // 单音节没有短语候选来抢首位,所以「首选是谁」是确定的。
    // 转换开着时 丟/誰 经 t2s 变成 丢/谁,uniquifier 再去重。
    //
    // 这条同时守住三件事:
    //   ① schema 里 `reset: 1` 让 zh_simp 默认开(缺了它滤镜全 off);
    //   ② assets 里的 opencc/t2s.json + 两张 .txt 表能被找到(缺了滤镜创建失败);
    //   ③ `install()` 真的把新 schema 覆盖到设备上(缺了跑的还是旧的那份)。
    name: 'simplified',
    desc: '输出简体(不是繁体)',
    steps: [
      async (c) => {
        for (const [letters, want, trad] of [['diu', '丢', '丟'], ['shei', '谁', '誰']]) {
          for (const ch of letters) c.device.key(kcOf(ch))
          await sleep(1200)
          c.device.key(K.space)
          await sleep(900)
          const t = last(c.device.editText())
          c.expect(t === want, `${letters} 应上屏简体「${want}」,实际="${t}」(${trad} 就是没转)`)
        }
      },
    ],
  },

  {
    // 「拼音组词 + 空格上屏首选」。
    //
    // ## 为什么不能断言 `editText == "你"`(2026-10-05)
    //
    // 「ni 的首选是『你』」**不是我们能定的事实** —— 它由词典顺序 + **用户词频**决定。
    // 而 librime 会从每次上屏里学习(用户词频走 LevelDB,**跨场景累积**)。
    //
    // 2026-10-05 实测:同一个 run 里,前面的 `select-global-index` 提交了一次「妳」,
    // 于是之后 `ni` 的首选变成「妳」,这条断言拿到 `editText="妳"` 而红。
    // 它自己什么都没做错 —— 与 `simplified` 那条「为什么不能用『中国/中國』」是同一个坑。
    //
    // ⇒ 断言挂在**关系**上,不挂在具体是哪个字:
    //   空格上屏的必须是**一个汉字**,且**正好等于当前页里 li=0 那个**(槽2 的定义)。
    // 后者顺带证明了「空格走的是首选那条路」,比「是个汉字」强。
    //
    // `select:` 埋点里 `pageWords` 就是当前页的词(顺序即逻辑序),
    // `li` 是空格选中时的页内下标 —— 断言它等于 0 就等于「空格选的就是首选」。
    name: 'compose-space',
    desc: '拼音组词 + 空格上屏首选',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        c.shot('composing')
        c.expect(c.device.imeShown(), '组词中输入法窗口显示(全屏飞字态)')
        c.device.shell('logcat -c')
        c.device.key(K.space)
        await sleep(900)
        const t = c.device.editText()
        const log = c.device.takeLog('select: zone=.*')
        c.expect(
          /[\u4e00-\u9fff]$/.test(t),
          `空格后应上屏一个汉字而不是原始字母 ni,实际 editText="${t}"`,
        )
        const m = /select: zone=(\d+) li=(\d+) global=(\d+) .*?pageWords=([^ ]+)/.exec(log)
        c.expect(m, `应打出 select 埋点(实际: ${JSON.stringify(log.slice(0, 300))})`)
        if (m) {
          const [, zone, li, , pageWords] = m
          c.expect(Number(zone) === 2, `空格应走显示槽 2(实际 zone=${zone})`)
          // li=0 ⇔ 选的是页内第 0 个 ⇔ 首选。这是「空格选首选」这件事本身。
          c.expect(Number(li) === 0, `空格应选页内第 0 个候选(实际 li=${li})`)
          const top = pageWords.split('|')[0]
          c.expect(last(t) === top, `空格上屏的应是当前页首选「${top}」,实际 "${last(t)}"`)
        }
      },
    ],
  },

  {
    name: 'candidate-center',
    desc: '候选中置:首选显示在中间槽位(AI 判卷)',
    judge: { shot: 'candidates', question: '这是输入法候选栏截图。共有几个候选词?「你」是否位于正中间(第 3 个)槽位?左右两侧各两个候选?简短回答事实。' },
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(1000)
        c.shot('candidates')
        c.device.key(K.del, K.del) // 清掉组词
        await sleep(400)
      },
    ],
  },

  {
    // 反复打断翻页动画 —— 2026-10-04 用户报「小幅多次振盪」时加的复现场景。
    //
    // 为什么单独做一个场景:现有的 page-flip 是「划一次 → 抬手 → 断言」,
    // 抓不到打断路径。而 `settle` 走 `animateToFinalPosition` **保留速度**,
    // 手指在动画播着时再次按下就会带着速度进下一段 —— 这是「振铃」的头号嫌疑,
    // 但它在现有场景里永远不会发生。
    //
    // 断言只看一件事:**穿過目標的次數**。判据来自振铃探测埋点:
    //   0/1 次 ⇒ 正常(单调逼近 / 单次过冲)
    //   ≥3 次 ⇒ 真在振荡,而 ζ<1 的欠阻尼解不可能这样 ⇒ 不是弹簧在振
    name: 'page-interrupt',
    desc: '翻页动画播着时再次按下(反复打断),看位移会不会来回振荡',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        c.device.shell('logcat -c')
        // 划到刚过线就松手(动画开始播),立刻再按下并往回拖 —— 打断提交动画。
        c.device.swipe(1100, 1100, 500, 1100, 120)
        c.device.swipe(500, 1100, 1100, 1100, 120)
        c.device.swipe(1100, 1100, 500, 1100, 120)
        await sleep(1500)
        const log = c.device.takeLog('ring:|settle:|end:|commit:')
        // 每次 settle 打印一行,数它就知道有几段动画;每段各自报自己的振铃次数。
        const rings = log.split('\n').filter((l) => l.includes('ring: 第'))
        const worst = rings.reduce((m, l) => {
          const n = Number(/第 (\d+) 次/.exec(l)?.[1] ?? 0)
          return Math.max(m, n)
        }, 0)
        c.expect(
          rings.length === 0 || worst <= 1,
          `每段动画最多穿过目标 1 次,实测最多次数=${worst}(共 ${rings.length} 次记录)`,
        )
        c.device.key(K.del, K.del)
        await sleep(300)
      },
    ],
  },

  {
    name: 'five-key-slots',
    desc: '五键选字:Shift/Alt/Ctrl 各选左右候选(相对断言,词随词频漂移)',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i, K.shift)
        await sleep(900)
        const t1 = c.device.editText()
        c.expect(t1.length >= 1 && /[\u4e00-\u9fff]$/.test(t1), `Shift 应上屏一个汉字槽0,实际="${t1}"`)
        const c1 = last(t1)

        c.device.key(K.n, K.i, K.alt)
        await sleep(900)
        const t2 = c.device.editText()
        c.expect(last(t2) !== c1 && t2.length === t1.length + 1, `Alt 应上屏不同汉字(槽4),实际="${t2}"`)

        c.device.key(K.n, K.i, K.ctrl)
        c.device.key(K.ctrl) // 松开 Ctrl 触发独立敲击选字
        await sleep(900)
        const t3 = c.device.editText()
        c.expect(last(t3) !== last(t2) && t3.length === t2.length + 1, `Ctrl 独立敲击应上屏另一汉字(槽3),实际="${t3}"`)

        c.device.key(K.del, K.del, K.del) // 清理:删掉三个汉字
        await sleep(300)
      },
    ],
  },

  {
    name: 'page-flip',
    desc: '键盘面左右滑翻页(左滑=下一页)',
    // 断言的是 `page begin` / `page end`,**不是** `fly judge` / `fly page:`。
    //
    // 原因(2026-10-03 实测):横滑在 MOVE 里就被接管去画跟手动画
    // (`FlyDetector.paging = true`),抬手走 CandidateBarView.endPageDrag,
    // **不经过** ACTION_UP 的兜底分支。而 `fly judge` / `fly page: next`
    // 两条日志都只打在兜底分支上 —— 那条分支只在「MOVE 压根没接管」时才轮得到。
    //
    // 所以旧断言在**翻页完全正常**时也三条全红(看着像功能坏了)。
    // 修法不是把场景改松,而是给正常路径补上可观测的日志(见 CandidateBarView)。
    // 顺带断言 `commit=true`:到头时 begin 走橡皮筋、end 也 commit=false,
    // 这样「翻页」与「到头弹回」分得开。
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        // **先验「有组词」再验手势**(2026-10-03 加)。
        //
        // 没有组词时 `onPageDragBegin` 静默早退,logcat 里没有 `page begin` ——
        // 与「手势压根没进窗口」同形。这条断言把两者分开。
        //
        // 判据用 `updateSelection … comp=` 而不是 `hasComposition=true`:
        // 后者只在 `commitFly`(飞字提交)那条日志里出现,**量词阶段根本不会有**。
        // (第一版就写成 `hasComposition`,于是无论组词有没有都必红 —— 自己造的假信号。)
        c.expectLog(c.device.takeLog('updateSelection.*comp=[0-9]'), 'updateSelection')
        c.device.shell('logcat -c')
        c.device.swipe(1100, 1100, 500, 1100, 200)
        await sleep(700)
        const log1 = c.device.takeLog('page (begin|end)')
        c.expectLog(log1, 'page begin: dir=1.0 → 跟手翻页', 'commit=true')
        c.device.swipe(500, 1100, 1100, 1100, 200)
        await sleep(700)
        const log2 = c.device.takeLog('page (begin|end)')
        c.expectLog(log2, 'page begin: dir=-1.0 → 跟手翻页', 'commit=true')
        c.device.key(K.del, K.del)
      },
    ],
  },

  {
    name: 'fly-select',
    desc: '键盘面上滑飞字(槽位选字,动画)',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        c.device.shell('logcat -c')
        c.device.swipe(841, 1199, 989, 632, 250) // 真机实测坐标:上滑 → 槽2(首选)
        await sleep(900)
        const log = c.device.takeLog('fly (select|judge)')
        c.expectLog(log, 'fly select: zone=2', 'hasComposition=true')
        c.device.key(K.del) // 删掉飞入的字
        await sleep(300)
      },
    ],
  },

  {
    name: 'longpress-delete',
    desc: '长按候选删除用户词(删词后槽0 换词)',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i, K.shift)
        await sleep(800)
        const first = c.device.editText()
        c.device.key(K.n, K.i, K.shift) // 同槽再选一次(词频+2)
        await sleep(800)
        c.device.key(K.n, K.i)
        await sleep(700)
        c.device.swipe(175, await candidateRow(c), 175, await candidateRow(c), 700) // 原���长按槽0
        await sleep(900)
        c.device.key(K.n, K.i, K.shift)
        await sleep(800)
        const after = c.device.editText()
        c.expect(last(after) !== last(first), `长按删词后槽0 应换词(原 ${last(first)}),实际 "${after}"`)
        c.device.key(K.del, K.del, K.del)
        await sleep(300)
      },
    ],
  },

  {
    name: 'layer-chips',
    desc: '层芯片视觉:锁 L2 实心「ALT」、三颗互斥芯片同刻只亮一颗(AI 判卷)',
    judge: [
      { shot: 'locked-l2', question: '这是输入法状态行截图。左侧是否有一个橙/红色「拼」字芯片和一个蓝色实心背景的「ALT」芯片?简短回答。' },
      { shot: 'en-mode', question: '这是输入法状态行截图。左侧是否有一个灰色的「En」芯片,以及三颗灰色描边的「ab」「ALT」「SYM」芯片?简短回答。' },
      { shot: 'en-caps', question: '这是输入法状态行截图。左侧「ab」芯片是否为橙/红色(亮起),而「ALT」「SYM」芯片是否仍是灰色描边?简短回答。' },
    ],
    steps: [
      async (c) => {
        c.device.key(58, 58) // 双击 Alt 锁 L2
        await sleep(700)
        c.shot('locked-l2')
        c.device.key(58) // 解锁
        await sleep(400)
        c.device.combo(59, 62) // Shift+Space 切英文:必须 keycombination(一次组合),分两次 keyevent 是"Shift 敲击+空格敲击",切不动
        await sleep(700)
        c.shot('en-mode') // 英文空闲:ab/ALT/SYM 三颗全灰(ab 不常亮)
        c.device.key(59) // 单击 Shift → 大写一次:只亮 ab,ALT/SYM 压灰
        await sleep(500)
        c.shot('en-caps')
        c.device.combo(59, 62) // 切回中文(切 CN 会把 shift 清掉)
        await sleep(400)
      },
    ],
  },

  {
    name: 'en-mode-typing',
    desc: '英文态字母直出',
    steps: [
      async (c) => {
        c.device.combo(59, 62) // 切 EN(必须 keycombination,分两次 keyevent 切不动)
        await sleep(500)
        c.device.text('ab')
        await sleep(700)
        const t = c.device.editText()
        // 空格是模式判据:英文态 'ab' 已上屏 → "ab ";拼音态是组词 'ab' → 空格走选字,不会留下尾随空格
        c.device.key(62)
        await sleep(500)
        const t2 = c.device.editText()
        c.expect(t2.endsWith('ab '), `英文态应上屏 ab 并吃下空格,实际="${t2}"(输入时="${t}")`)
        c.device.combo(59, 62) // 切回中文
        c.device.key(K.del, K.del)
        await sleep(300)
      },
    ],
  },

  {
    name: 'settings-open',
    desc: '设置齿轮按钮直达设置页',
    steps: [
      async (c) => {
        // 齿轮坐标动态算:右缘 - (hide 26dp + 右边距 6dp + 齿轮半宽 17dp)
        const size = c.device.shell('wm size')
        const w = Number((size.match(/(\d+)x\d+\s*$/) ?? [])[1] ?? 1436)
        const dens = Number((c.device.shell('wm density').match(/(\d+)/) ?? [])[1] ?? 384) / 160
        c.device.tap(Math.round(w - 78 * dens), await candidateRow(c)) // 状态栏也不走硬编码
        await sleep(2000)
        c.expect(c.device.foreground().includes('SettingsActivity'), `应打开设置页,实际前台=${c.device.foreground()}`)
        c.device.key(4) // 返回
        await sleep(600)
        // 返回后前台可能被别的窗口顶掉(实测残留 documentsui 选择器):先把实验场弄回前台再聚焦输入框,
        // 否则后续场景的注入键全落空,表现为 editText=""
        for (let i = 0; i < 2 && !c.device.foreground().includes('PlaygroundActivity'); i++) {
          c.device.key(4)
          await sleep(600)
        }
        if (!c.device.foreground().includes('PlaygroundActivity')) {
          c.device.shell('am start -n dev.hwkbd.ime/.playground.PlaygroundActivity >/dev/null 2>&1')
          await sleep(1500)
        }
        c.device.tap(700, 400)
        await sleep(600)
      },
    ],
  },

  {
    name: 'l2-output',
    desc: 'Alt 层九宫格输出(W=1,数字半角)',
    steps: [
      async (c) => {
        c.device.key(K.alt, 51) // Alt 一次性 + W
        await sleep(800)
        const t = c.device.editText()
        c.expect(last(t) === '1', `Alt+W 应输出 1(半角),实际="${t}"`)
        c.device.key(K.del)
        await sleep(300)
      },
    ],
  },
]
