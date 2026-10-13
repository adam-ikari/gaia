---
id: unverified-as-fact
title: "把没验证过的量当事实:2026-10-08 真机首日的四个错判,以及它们为什么长得像测过的"
category: decision
status: active
tags: [lessons, method, verification, e2e]
created: "2026-10-08T18:16:42"
updated: "2026-10-13T13:52:19"
---

<!-- compiled_truth -->
2026-10-08 真机首日(Titan 2):测出来的和推断出来的混在一起写成结论,于是**当时自认为已经知道**的东西全是错的 —— 但它们错得很有说服力,因为每一条都能自圆其说。

## 五个错判

| # | 当时写的结论 | 实际 | 为什么像测过 |
|---|---|---|---|
| 1 | 已装的是最新 APK | 是 2026-09-30 那个,`versionCode=3` / `0.3.0`,**没有 `.so`** | 没看时间戳与版本号 |
| 2 | 「键盘锁屏了」 | `mWakefulness=Awake` + Always-On | 只看到一次现象 |
| 3 | 「dev 渠道抢了输入」 | 错得很离谱,**反过来**:共存才是 `mInputShown=false` 的原因 | 先归因最顺手的那个 |
| 4 | `candidateRow` 是 `(24+40)dp` | **是猜的** | 1280 宽的设备上按 1337 算,`page-flip` / `settings-open` 全绿 |
| **5** | **`PAGE_COMMIT` 的 ζ 已调到 0.95/1.0,过冲 0.1px** | **spec 的 ζ 从未送到引擎**;实际跑 androidx `SpringForce` 默认的 **0.5**,过冲 **16.3% = 221px** | **那串数字是用 spec 里的 ζ 在纯函数里算的**,不是真机量的 |

## 第 5 个为什么最值得记

前四个是「没查就下了结论」。第 5 个是**查了、测了、单测也绿了**,但整套测量都在量一个**没接到线上的参数**:

- `toOfficial()` 写成 `SpringForce(mass).apply { setDampingRatio(dampingRatio) }`。
  裸名字在 `apply` 作用域里解析到 receiver 自己的 `getDampingRatio()`,
  也就是刚 new 出来的对象的默认值 0.5(androidx 1.1.0 硬编码)。
- **`setStiffness` 那行当时写了 `this@toOfficial.`** ⇒ 刚度对、阻尼错。
  一个对一个错混在一起,现象就成了「响应时间正常,只是总有点过冲」——
  **看起来像一个手感参数问题,而不是一个接线问题**,于是没有怀疑参数本身。
- `PageSettleTest.提交的过冲严格为零` 用的是**同一个纯函数**:
  它验证的是「spec 声称的值」,不是「引擎收到的值」。所以它永远绿。
- 历史上把 ζ 从 0.72 一路调到 1.0(用户三次反馈「弹性太强」),
  每一轮都「改了、算过、没效果」,于是被归因为「问题在拖动阶段」——
  **一个错误前提上的正确推理**。

### 识别特征(下次遇到同样的错要能闻到)

1. **一个参数对一个参数错。** 全对或全错都容易查;半对半错会伪装成手感问题。
2. **数字的精度可疑地整齐**(2.2px → 0.1px),而且真机上从来没出现过那些数。
   纯函数算出来的数字天然「好看」,因为它用的是你以为的那个量。
3. **判据指向了相邻量。** 这次的教训是同一个,只是换了对象:
   - 埋点只打 `spec.ζ` ⇒ 必须打从引擎 `getSpring().dampingRatio` **回读**的值;
   - `ring:` 只记穿越次数 ⇒ 要反解 ζ 得拿**逐帧** `(value, velocity)` 拟合。

### 修法也是教训

加 `this@toOfficial.` 能修好这一处,但同样的错随时会出现在下一个参数上,
而且**编译得过、单测看不见**。所以改成:

- 换算拆成**纯函数** `SpringSpec.forces()`,可 JVM 单测(会断言阻尼比**不是 0.5**);
- `toOfficial()` **不用 `apply`**,先算进局部变量再逐个 setter
  ⇒ 这一类错在结构上不可能发生;
- 起跑埋点常驻 `engK`/`engZ`,从引擎回读,**对账**用。

## 曾被误认为「稳定」的信号其实来自错误环境

「四个场景只要拉长 150ms 间隔就绿了」这条**规律本身是对的**
(原因见 `unverified-as-fact` 下面那段),但那四个红里
**至少有一个是被错误环境造出来的**,不是产品的缺陷。
所以:

> **不稳定的红 ≠ 噪声。** 先确认环境对,再讨论稳定性;
> 否则会把「环境造的红」和「真 bug」混在一个池子里,反复重跑直到碰上一次绿的,
> 然后把那次绿当成「已修复」。

## 真机 E2E 环境要求(Titan 2,会再犯)

- **设备上不能有两个 Gaia 输入法。** `dev.hwkbd.ime.dev` 与 `dev.hwkbd.ime` 共存时
  `mInputShown=false`、按键不进 IME(`comp=-1..-1 raw=`)。已卸载 dev。
  两个 service 名不同:dev 是 `dev.hwkbd.ime.dev/dev.hwkbd.ime.service.ImeService`,
  debug 是简写的 `dev.hwkbd.ime/.service.ImeService`。
- **新装 IME 要先 `ime enable` 再 `ime set`**,否则 `Unknown input method ... cannot be selected for user #0`。
- **通知栏抢焦点**时 `cmd statusbar collapse` 无效,要下滑手势 `input swipe 700 100 700 1400 300`。
- **IME 窗口是 `[60, 1380]` 而屏幕 1440 高**,`contentTop=1260`,候选栏只有 `1260..1380`。
- **判布局用 `Physical` 不用 `Override`**(曾处于 1436x1440@384 的 override)。
- **`input swipe` 是一个命令内从按下到抬起**,`execFileSync` 同步阻塞 ⇒
  两段背靠背时第二段的按下发生在第一段抬起之前,物理上不成立。
  打断场景必须在两段之间加 sleep,那段 sleep 就是「动画在飞」的时间。
- **`gradlew.bat` 之后的 `adb install` 可能装的是上一次的旧 APK**(构建失败但上一条命令的
  `Success` 还在)。判断依据要看编译输出里有没有 `^e:`,不能只看 install 的 `Success`。

## `consume-partial` 那次的教训

问「首字母的 `consumed` 为什么总是 0」,**它唯一的证据是「用户抱怨首字母上屏了」**
—— 一个可能同时对应首字母误消费、或 `EdgeInputDelegate` 不转发标点的现象。

在拿到区分这几种可能性的证据之前就断言:「逻辑是对的,证据只是一条不完整的信息」。
下次要断言消费语义,先分辨是哪一种。


## Timeline

- time: 2026-10-08T18:16:42
  kind: decision
  summary: "Created this page: 把没验证过的量当事实:2026-10-08 真机首日的四个错判,以及它们为什么长得像测过的"
  source: "2026-10-08 真机测试首日复盘"
  affects: [unverified-as-fact]

- time: 2026-10-08T18:16:42
  kind: decision
  summary: "Created: 真机首日四个错判、修一处坏另一处的模式、以及真机 E2E 的环境要求"
  source: "2026-10-08 真机测试首日"
  affects: [unverified-as-fact]

- time: 2026-10-09T08:53:16
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-09 补第 5 个错判:调了一个从未接到线上的参数(spec 的 ζ vs 引擎实际的 0.5),并补上 install 装旧 APK 这一条环境坑"
  affects: [unverified-as-fact]

- time: 2026-10-09T08:53:17
  kind: reversal
  summary: "新增第 5 个错判并给出识别特征:一个参数对一个错会伪装成手感问题;纯函数算出来的数字天然好看;判据必须指向从引擎回读的量"
  source: "2026-10-09 commit c928fb8 真机逐帧拟合"
  affects: [unverified-as-fact, spring-damping-direction]

- time: 2026-10-09T09:49:07
  kind: evidence
  summary: "第 6 个错判:到头橡皮筋在真机上从来没发生过,而三条现象在日志里都表现为「什么都没有」—— 埋点打在意图上(rubber end 只在 runRubberAnim 里,而它只在 raw>0 时才被调)、ACTION_UP 全是静默 return。加上三个真 bug:归属每帧重判 / 在飞回滚撤销归属 / Landing 落点永远到不了 0"
  source: "2026-10-09 Titan 2 逐帧埋点与真机复现,commit c3ec71e"
  affects: [unverified-as-fact, paging-feel, spring-damping-direction]

- time: 2026-10-09T10:16:34
  kind: evidence
  summary: "第 7 个错判,也是第三种形状:consume-partial 的最后一条是 imeShown(),它量的是「输入法窗口可见」不是「还在组词态」,所以功能整个是死的时候照样绿。三种形状分别是:量的不是这件事 / 量的是相邻量 / 用 spec 声称的值而非引擎收到的值"
  source: 2026-10-09 commit ab878f9
  affects: [unverified-as-fact]

- time: 2026-10-09T11:20:07
  kind: reversal
  summary: "第 8 条:「内部状态对、用户看到的画面不对」是第四种形状(判据量的是上游)。同时否掉一个被否掉的假设:飞字分支画旧页 —— 埋点显示 setState 收到 45 个候选、draw 画的已是 hao 的新候选;真正落地的修复是对账那处"
  source: "2026-10-09 真机埋点,commit 3c5fa53"
  affects: [unverified-as-fact, rime-candidate-consumption, paging-feel]

- time: 2026-10-12T09:39:37
  kind: evidence
  summary: "2026-10-12 第十个错判,第五种形状:证据本体落错对象 —— 四张判卷截图 PNG 存在、能打开,内容是副屏表盘(screencap 不带 -d 落在 displayId 非 0 的屏上);同时 display-vs-commit 的红是环境造的(wm override 1436x1440@358 残留,候选行只能铺 1436,断言拿物理 1440 比)。修 display-id + 清 override 后 16/16 全绿。five-key-slots 的红是断言脆弱:三次查询候选序随词频漂移, Ctrl 选的就是它槽位当时显示的词。判据:清掉 override 再谈红;断言用不变量(zone)别用会漂移的量(互异的词)。"
  source: "2026-10-12 真机全量回归 TTANCN2100002663(v0.7.11 debug @93f62ba)"
  affects: [e2e-capture, spring-damping-direction]

- time: 2026-10-12T16:54:05
  kind: evidence
  summary: "第 11 个错判,近似形状:撇号坐标 —— RimeEngine.query 跳过 ' 不喂引擎(代码事实),而 rawAfterConsuming/readRemainder/ImeController 三处注释都说'坐标含撇'、依据是宿主机探针(探针绕过 skip 直接喂)与 E2E(输入从无撇)。探针证据 ≠ 生产证据:raw 含 ' 时(本机只能 hold-Alt+L 输入,极难构造)consumed 的下标语义可能错。适用于:任何'引擎收到了什么'的结论,判据必须是生产路径本身,探针只证明引擎能力。当天顺带实测:Alt+L 组合键会被五键选字吃掉(手动分节符在本机几乎不可达),这正是自动分隔符需求的由来。"
  source: "2026-10-12 读码 + 真机注入取证"
  affects: [rime-candidate-consumption]

- time: 2026-10-13T13:52:19
  kind: evidence
  summary: "2026-10-13 评审自查发现同批代码的真 bug:clearComposition 清了 raw 没清 spelling(拼音显示串),而 syncComposing 的写入判据是 spelling.ifEmpty{raw} —— 陈旧拼写让 finishComposingText 不再触发,ESC/abandon 后拼音留在输入框。E2E 16/16 是绿的:没有场景测 ESC/换框时的组词清除,而现有场景断言的都在上屏之后。教训形状:全绿套件 + 真机验证过主路径,依然漏掉'两条状态字段的清除必须同步'这类不变量 —— 不变量要么收进同一个漏斗(clearComposition)并用单测钉'每个清空入口',要么靠评审。已修(clearComposition 清 spelling)+ 三条入口回归测试。"
  source: "2026-10-13 代码评审"
  affects: [e2e-capture]
