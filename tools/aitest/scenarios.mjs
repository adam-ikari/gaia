/**
 * 功能测试场景定义。每个场景 = 前置(引擎自动:唤醒/切输入法/开实验场/点输入框)+ steps。
 * ctx: { device, dir, shot(label), expect(pass,msg), expectLog(log,...expected), run(fn) }
 * 断言策略:能用文本/日志的用确定性断言;依赖视觉排布的用 judge(AI 视觉判卷)。
 * 注意:组词中 IME 窗口全屏盖住 app,editText() 仅在无组词时可用。
 */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const K = { n: 42, i: 37, space: 62, shift: 59, alt: 58, ctrl: 113, del: 67 }
const last = (s) => (s ? s[s.length - 1] : '')

export const scenarios = [
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
    steps: [
      async (c) => {
        c.device.key(K.n, K.i)
        await sleep(900)
        c.device.shell('logcat -c')
        c.device.swipe(1100, 1100, 500, 1100, 200)
        await sleep(700)
        const log1 = c.device.takeLog('fly (judge|page)')
        c.expectLog(log1, 'fly judge', 'fly page: next')
        c.device.swipe(500, 1100, 1100, 1100, 200)
        await sleep(700)
        const log2 = c.device.takeLog('fly (judge|page)')
        c.expectLog(log2, 'fly page: prev')
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
        c.device.key(59, 62) // Shift+Space 切英文
        await sleep(700)
        c.shot('en-mode') // 英文空闲:ab/ALT/SYM 三颗全灰(ab 不常亮)
        c.device.key(59) // 单击 Shift → 大写一次:只亮 ab,ALT/SYM 压灰
        await sleep(500)
        c.shot('en-caps')
        c.device.key(59, 62) // 切回中文(切 CN 会把 shift 清掉)
        await sleep(400)
      },
    ],
  },

  {
    name: 'en-mode-typing',
    desc: '英文态字母直出',
    steps: [
      async (c) => {
        c.device.key(59, 62) // 切 EN
        await sleep(500)
        c.device.text('ab')
        await sleep(700)
        const t = c.device.editText()
        c.expect(t.endsWith('ab'), `英文态应直接上屏 ab,实际="${t}"`)
        c.device.key(59, 62) // 切回中文
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
