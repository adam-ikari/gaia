/**
 * 功能测试场景定义。每个场景 = 前置(引擎自动:唤醒/切输入法/开实验场/点输入框)+ steps。
 * ctx: { device, dir, shot(label), expect(pass,msg), expectLog(log,...expected), run(fn) }
 * 断言策略:能用文本/日志的用确定性断言;依赖视觉排布的用 judge(AI 视觉判卷)。
 * 注意:组词中 IME 窗口全屏盖住 app,editText() 仅在无组词时可用。
 */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// Android KEYCODE_*:字母是 29..54 的连续段(A=29),i=37/n=42 可反推校验。
const K = {
  a: 29, b: 30, c: 31, d: 32, e: 33, f: 34, g: 35, h: 36, i: 37, j: 38,
  k: 39, l: 40, m: 41, n: 42, o: 43, p: 44, q: 45, r: 46, s: 47, t: 48,
  u: 49, v: 50, w: 51, x: 52, y: 53, z: 54,
  space: 62, shift: 59, alt: 58, ctrl: 113, del: 67,
}
const last = (s) => (s ? s[s.length - 1] : '')

export const scenarios = [
  {
    // 2026-10-04 用户「自從換過輸入法一直是繁體中文,必須輸入簡體中文」。
    //
    // 用 `zhongguo` 是因为它的简繁**字形不同**:中国 / 中國。
    // 挑一个简繁一样的词(比如 nihao → 你好)断言等于没断言。
    //
    // 这条同时守住三件事:
    //   ① schema 里 `reset: 1` 让 zh_simp 默认开(缺了它滤镜全 off);
    //   ② assets 里的 opencc/t2s.json + 两张 .txt 表能被找到(缺了滤镜创建失败);
    //   ③ `install()` 真的把新 schema 覆盖到设备上(缺了跑的还是旧的那份)。
    name: 'simplified',
    desc: '输出简体(不是繁体)',
    steps: [
      async (c) => {
        c.device.key(K.z, K.h, K.o, K.n, K.g, K.u, K.o)
        await sleep(1200)
        c.device.key(K.space)
        await sleep(900)
        const t = c.device.editText()
        c.expect(last(t) === '中国', `应上屏简体「中国」,实际 editText="${t}"(繁体会是「中國」)`)
      },
    ],
  },

  {
    name: 'compose-space',
    desc: '拼音组词 + 空格上屏首选',
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        c.shot('composing')
        c.expect(c.device.imeShown(), '组词中输入法窗口显示(全屏飞字态)')
        c.device.key(K.space)
        await sleep(900)
        const t = c.device.editText()
        c.expect(last(t) === '你', `空格后应上屏首选「你」,实际 editText="${t}"`)
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
        c.device.swipe(175, 1397, 175, 1397, 700) // 原地长按槽0
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
        c.device.tap(Math.round(w - 78 * dens), 1397) // 状态行右侧齿轮(右缘 - 收起48dp - 边距6dp - 齿轮半宽24dp)
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
