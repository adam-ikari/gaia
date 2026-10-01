---
id: e2e-capture
title: "CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
category: project
status: active
tags: [e2e, ci, emulator, media, capture]
created: "2026-10-01T05:09:58"
updated: "2026-10-01T06:02:36"
---

<!-- compiled_truth -->
# CI 素材采集与模拟器 E2E

站点的截图与录屏由本仓库的 E2E 工作流产,一次跑出 9 张图 + 一段 mp4 + 一份
`manifest.json`(图文对照表),落到 `hwkbd_ime-web/docs/public/media/shots/`。
站点那边**不写死文件名**,由 `Shots.vue` 读 `manifest.json` 渲染。

## 一、模拟器的生命周期 == 「启动模拟器」那一个 step

这是全部问题的根源,2026-09-30 连续六轮都诊断错方向(KVM / GPU / offline / 崩溃)。

`reactivecircus/android-emulator-runner` 在**该 step 结束时立刻 `emu kill`**。
所以安装 APK、启用输入法、截图、录屏**必须全在这个 step 内部完成**。
拆到后面的 step,等它们跑起来 `adb devices` 已经空了。

日志长这样,注意时序:

```
Starting emulator → Boot completed in 56010 ms → [group]Terminate Emulator → emu kill
```

`emu kill` 之后的 `libc++abi: terminating` 是**收尾噪音,不是启动失败** —— 我误读过一次,
以为模拟器自己崩了。模拟器一直是好的,56 秒 boot 完全正常。

## 二、workflow 的 `script:` 是逐行执行的

```
[command]/usr/bin/sh -c set -u
[command]/usr/bin/sh -c for i in $(seq 1 60); do
/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "done")
```

不是当脚本文件跑,是**一行一个 `/usr/bin/sh -c`**。所以:
- 多行 shell 结构(`for ...; do ... done`)必挂
- `set -o pipefail` 必挂(runner 上的 `sh` 是 dash,没这选项)
- `script:` 只能写一行 → `script: bash tools/aitest/ci-capture.sh`,逻辑全在那个文件里

## 三、adb 路径与序列号要显式给

PATH 上的 adb 不保证是 action 用的那个,`adb shell getprop` 会**静默无输出**,
看着像 boot 不完(实测 24 次轮询一条回显都没有)。用
`/usr/local/lib/android/sdk/platform-tools/adb -s emulator-5554`。

## 四、装完包不能立刻 `ime set`

`adb install` 返回 `Success` 时 PackageManager 往往还没处理完新包,
`ime enable/set` 会回 `Unknown input method ... cannot be enabled for user #0`。
**这是竞态不是配置错**(同一份脚本隔一轮就复现一次)。轮询等它出现在 `ime list -s -a` 里。

## 五、词库要 5–30 秒才加载,而且顺序不能反

`dict loaded: 348723 entries, 4291..29477 ms`。两条:

1. 没就绪时组词**没有候选**,候选行是空的 —— 截出来是一张"只有输入框"的图,
   看着像功能坏了。采集前必须等 `dict loaded:`。
2. **词库日志要等输入法服务的 `onCreate`**,而服务是在**第一个输入框拿到焦点**时
   才起来的。所以顺序必须是:开实验场 → 点一下输入框 → 再等词库。
   反过来(先等词库)在冷启动时是**死等**:实测等满 180s 超时,因为那时服务还没建,
   日志根本不会出现。

## 六、键码表对着 android.jar 核,别背

`javap -classpath platforms/android-36/android.jar -constants android.view.KeyEvent`

踩过:`h` 写成 **41**(那是 `KEYCODE_M`)、`alt` 写成 58(那是 `KEYCODE_ALT_RIGHT`)。
**后果是图照样拍出来,只是内容不对** —— 「你好」打不出来,图里只有一个降级候选,
看的人不会知道是脚本键码错了。所以每个核心步骤都带 `verify()`,对着输入框的
实际内容断言;不符要报出来并让退出码非 0,不能悄悄混进站点。

## 七、拼音分节符是 v 键,不是撇号

词库里「你好」记作 `ni'hao`,而 [ChineseLanguage.mapText] 把 **v** 映射成分节符,
所以要打 `nivhao`。打 `nihao` 匹配不上任何词条。

## 八、`pkill -f screenrecord` 不能用来停录屏

`-f` 匹配整条命令行,而那条命令行是 `sh -c pkill -f screenrecord` ——
pkill 会把**自己的父 shell** 一起杀掉,adb 那头永远等不到 shell 退出,
20 秒后被 timeout 砍掉,整轮采集跟着炸(`status: 143` = SIGTERM)。
用 `killall -9 screenrecord`(`killall` 匹配进程**名**,父 shell 叫 sh,不会误伤),
再兜一层 `screenrecord --time-limit`。

## 九、图必须对得起那句话

原先有两步文案写「五键选字」「滑动翻页」,截出来的却是一张空闲状态的状态行。
看的人不会知道是演示没做出来,只当功能没有。现在每步要么拍到那个状态,
要么 `verify` 说不清。

能直接问的不要猜:
- **输入框定位按 resource-id**,不要按顺序索引(实验场里插一个框就全错位;
  原来 `editTexts()[2]` 当"数字框",其实是邮箱框)。
- **焦点有没有切过去**:uiautomator 每个 node 带 `focused="true"`,直接读。
  (实验场每个输入框的 hint 就是那句说明文字,所以"框里有没有字"根本不能当焦点证据。)
- **候选条与状态行不在 dump 里**(输入法是另一个窗口),
  "翻没翻页""锁没锁层"只能靠输入法自己的日志断言
  (`fly page:` / `ImeController` 的 `layer lock:` 行数变化)。

## 十、组词期间 App 里点不动任何东西(待真机确认)

采集时发现的:一步结束时留着未上屏的拼音,下一步就点不到任何别的输入框,
焦点一直留在原来那个框上。读 [ImeRootView] 定位到根因:

`dispatchTouchEvent` 里,`ev.y < contentTop`(栏以上 = App 区域)的触摸一律交给
飞字手势,而 `handleFlyMotion` **永远返回 true**(吃掉事件)。同时 `onMeasure` 在
`flyMode`(= 有组词)时把窗口撑到全屏,好让飞字手势能收到栏以外的触摸。

⇒ **组词期间,输入法窗口占满全屏并吃掉所有触摸,App 完全点不动**,直到上屏或清掉组词。
这是"接飞字手势"与"别挡住 App"之间的取舍。**采集这边不依赖它的结论**:
每一步结束都 `cancelComposition()`,各步互不影响。**要不要改是产品决定,没动。**


## Timeline

- time: 2026-10-01T05:09:58
  kind: decision
  summary: "Created this page: CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
  source: "2026-10-01 实测"
  affects: [e2e-capture]

- time: 2026-10-01T05:10:12
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 连续九轮 CI 实测"
  affects: [e2e-capture]

- time: 2026-10-01T05:10:12
  kind: evidence
  summary: "2026-10-01:九轮 CI 后 E2E 全绿,9 张图 + mp4 + manifest 一次产出,0 失败步骤;站点已接入并在 gaia 仓库上线"
  source: run 36817406644
  affects: [e2e-capture]

- time: 2026-10-01T06:02:36
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 端到端断言时发现,读 ImeRootView 定位到根因"
  affects: [e2e-capture]

- time: 2026-10-01T06:02:36
  kind: evidence
  summary: "发现:组词未上屏时输入法窗口全屏吃掉所有触摸,App 里点不动任何控件(ImeRootView.dispatchTouchEvent + flyMode 全屏)。已记入待确认,采集侧用每步收尾规避"
  source: "tools/aitest/shots.mjs 的 focused 断言 + ImeRootView.kt:66"
  affects: [e2e-capture]
