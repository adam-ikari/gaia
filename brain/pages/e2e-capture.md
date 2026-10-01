---
id: e2e-capture
title: "CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
category: project
status: active
tags: [e2e, ci, emulator, media, capture]
created: "2026-10-01T05:09:58"
updated: "2026-10-01T08:03:23"
---

<!-- compiled_truth -->
# CI 素材采集与模拟器 E2E

站点的截图与录屏由本仓库的 E2E 工作流产,一次跑出 9 张图 + 一段 mp4 + 一份
`manifest.json`(图文对照表),落到 `hwkbd_ime-web/docs/public/media/shots/`。
站点那边**不写死文件名**,由 `Shots.vue` 读 `manifest.json` 渲染。

## 零、屏幕 = Titan 2 的网格(1440x1440 @ 320dpi = 576dp 宽)

**这一条不是「分辨率」问题,是版式问题。** 状态行中区宽 = 屏宽 − 2 × bar_side_box − 12dp:
手机(1080dp 屏)上只有 **159dp**,Titan 2 上是 **346dp** —— 差一倍多。于是 L4 一行
装得下几把键、哪些装不下要溢出到 L5,全都不一样。

实测对照:同一个「切到网址框」的截图,手机屏上 L4 还是默认的 `;` `…`,网址那一串被挤去了
L5(实验场自己的说明还写着「L4 http://」,于是图和说明对不上);
换成 1440x1440 之后 L4 里就是 `http://` `https://` `www.`,和说明一致。

三条实现上的坑:

1. **不能事后 `wm size` / `wm density`。** 改了会把系统搞瘫:Launcher3 崩 →
   `am start -W` 一直回 `LaunchState: UNKNOWN (0)` / `TotalTime: 0`,Activity 起不来。
   只能**开机前**配好。
2. **AOSP 的 Launcher3 在正方形屏上必崩**(100% 复现):
   `com.android.launcher3.DeviceProfile.calculateHotseatBorderSpace`
   → `ArithmeticException: divide by zero`,崩在 system_server 派发 USER_UNLOCKED 时。
   **这不是 Titan 2 的行为**(它跑的不是 AOSP 这个 Launcher3),是镜像的毛病。
   采集不需要桌面,`pm disable-user com.android.launcher3` 关掉即可。
3. **AVD 手写,别用 `avdmanager`。** 它在 runner 上 `create avd` 回 rc=0 却什么都没建
   (`$HOME/.android` 下连 `titan2*` 都没有),而且**不认 `--sdk_root`**(给了就打印用法)。
   AVD 本来就是两个文本文件:`<name>.ini` + `<name>.avd/config.ini`,手写更可控 ——
   要改的就是 `hw.lcd.width/height/density`。

`ci-capture.sh` 里**只核对不修改**(对不上直接 `::error::` 退出):拍出来的版式全错,
比失败更糟。`shots.mjs` 里**一行都不改** —— 真机采集不能把设备改成不是它自己的样子。

## 一、模拟器的生命周期 == 「启动模拟器」那一个 step

这是全部问题的根源,2026-09-30 连续六轮都诊断错方向(KVM / GPU / offline / 崩溃)。

`reactivecircus/android-emulator-runner` 在**该 step 结束时立刻 `emu kill`**。
所以安装 APK、启用输入法、截图、录屏**必须全在这个 step 内部完成**。
拆到后面的 step,等它们跑起来 `adb devices` 已经空了。

```
Starting emulator → Boot completed in 56010 ms → [group]Terminate Emulator → emu kill
```

`emu kill` 之后的 `libc++abi: terminating` 是**收尾噪音,不是启动失败** —— 我误读过一次,
以为模拟器自己崩了。模拟器一直是好的,56 秒 boot 完全正常。

## 二、workflow 的 `script:` 是逐行执行的

```
[command]/usr/bin/sh -c for i in $(seq 1 60); do
/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "done")
```

一行一个 `/usr/bin/sh -c`,不是当脚本文件跑。所以多行 shell 结构必挂、
`set -o pipefail` 必挂(dash 没这选项)。⇒ `script:` 只写一行
`bash tools/aitest/ci-capture.sh`,逻辑全在那个文件里。

## 三、adb 路径与序列号要显式给

PATH 上的 adb 不保证是 action 用的那个,`adb shell getprop` 会**静默无输出**,
看着像 boot 不完(实测 24 次轮询一条回显都没有)。

## 四、装完包不能立刻 `ime set`

`adb install` 返回 `Success` 时 PackageManager 往往还没登记新包,
`ime enable/set` 回 `Unknown input method ... cannot be enabled for user #0`。
**竞态不是配置错**(同一份脚本隔一轮就复现一次)。轮询等它出现在 `ime list -s -a` 里。

## 五、词库要 5–30 秒才加载,而且顺序不能反

`dict loaded: 348723 entries, 4291..29477 ms`。两条:

1. 没就绪时组词**没有候选**,候选行是空的 —— 截出来是一张「只有输入框」的图。
2. **词库日志要等服务 `onCreate`,而服务要等第一个输入框拿到焦点才起来。**
   所以顺序必须是:开实验场 → 点一下输入框 → 再等词库。反过来是**死等**
   (实测等满 180s 超时,那时服务还没建,日志根本不会出现)。

## 六、实验场要反复 `am start`,不能启动一次然后等

`am start -W` + 轮询不够:系统重新布局期间第一次 start 会白费。实测在正方形屏上
(system_server 被 Launcher3 崩溃拖累)连续 10 轮都起不来。所以**反复 start**,
并在失败时把 `am start` 的输出、**前台窗口是谁**、分辨率、dump 到的标题一起打出来。

> 「没等到」这三个字本身没有信息量。它让我把三轮时间花在猜权限、猜模拟器、猜键码上。
> **失败时必须打印真实状态**,而不是让人猜。

## 七、键码表对着 android.jar 核,别背

`javap -classpath platforms/android-36/android.jar -constants android.view.KeyEvent`

踩过:`h` 写成 **41**(那是 `KEYCODE_M`)、`alt` 写成 58(那是 `KEYCODE_ALT_RIGHT`)。
**后果是图照样拍出来,只是内容不对** —— 「你好」打不出来,图里只有一个降级候选,
看的人不会知道是脚本键码错了。所以每个核心步骤都带 `verify()`,对着输入框的
实际内容断言;不符要报出来并让退出码非 0,不能悄悄混进站点。

## 八、拼音分节符是 v 键,不是撇号

词库里「你好」记作 `ni'hao`,而 [ChineseLanguage.mapText] 把 **v** 映射成分节符,
所以要打 `nivhao`。打 `nihao` 匹配不上任何词条。

## 九、`pkill -f screenrecord` 不能用来停录屏

`-f` 匹配整条命令行,而那条命令行是 `sh -c pkill -f screenrecord` ——
pkill 会把**自己的父 shell** 一起杀掉,adb 那头永远等不到 shell 退出,
20 秒后被 timeout 砍掉,整轮采集跟着炸(`status: 143` = SIGTERM)。
用 `killall -9 screenrecord`(匹配进程**名**,父 shell 叫 sh,不会误伤),
再兜一层 `screenrecord --time-limit`。

## 十、图必须对得起那句话

原先两步文案写「五键选字」「滑动翻页」,截出来的却是一张空闲状态的状态行。
看的人不会知道是演示没做出来,只当功能没有。现在每步要么拍到那个状态,要么 `verify` 说不清。

能直接问的不要猜:

- **输入框定位按 resource-id**,不要按顺序索引(插一个框就全错位;
  原来 `editTexts()[2]` 当「数字框」,其实是邮箱框)。
- **焦点有没有切过去**:uiautomator 每个 node 带 `focused="true"`,直接读。
  (每个输入框的 hint 就是那句说明文字,所以「框里有没有字」根本不能当焦点证据。)
- **候选条与状态行不在 dump 里**(输入法是另一个窗口),
  「翻没翻页」「锁没锁层」只能靠输入法自己的日志断言(`fly page:`、`layer lock:` 的行数变化)。
- **候选条的排列不是排序**:它按**五键位置**渲染(左→右 = 4 2 1 3 5,
  见 `ImeController.selectCandidate`),所以首选必然落在正中间(空格那一格)。
  引擎给的是 `是 时 使 事 市`,图上是 `事 时 是 使 市` —— 换算回来完全吻合。
  **查过一次,别再查。**

## 十一、组词期间 App 里点不动任何东西(待真机确认)

采集时发现的:一步结束时留着未上屏的拼音,下一步就点不到任何别的输入框,
焦点一直留在原来那个框上。读 [ImeRootView] 定位到根因:

`dispatchTouchEvent` 里,`ev.y < contentTop`(栏以上 = App 区域)的触摸一律交给
飞字手势,而 `handleFlyMotion` **永远返回 true**(吃掉事件)。同时 `onMeasure` 在
`flyMode`(= 有组词)时把窗口撑到全屏,好让飞字手势能收到栏以外的触摸。

⇒ **组词期间,输入法窗口占满全屏并吃掉所有触摸,App 完全点不动**,直到上屏或清掉组词。
这是「接飞字手势」与「别挡住 App」之间的取舍。**采集这边不依赖它的结论**:
每一步**开头**要点别的框之前先 `cancelComposition()`(放结尾会把上一步的组词也清掉,
下一步就没东西可上屏了)。**要不要改是产品决定,没动。**

## 素材的诚实边界

采集跑的是 **debug 包 + x86_64 模拟器**,不是 Titan 2 本机:渲染是 AOSP 的,
Launcher3 已被关掉。**屏幕网格与 dp 版式和 Titan 2 一致**(1440×1440 / 576dp)。
站点文案只能说「自动测试跑下来的,屏幕尺寸和真机一样」,
**不能说「和你下载的是同一个包」**(那是假的,站点放的是 release APK),也不能冒充真机截图。


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

- time: 2026-10-01T08:03:23
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-01 屏幕对齐 Titan 2 网格"
  affects: [e2e-capture]

- time: 2026-10-01T08:03:23
  kind: evidence
  summary: "屏幕对齐 Titan 2 网格(1440x1440@320dpi=576dp)后 E2E 全绿:9 张图 + 89s 录屏,0 失败。中区从 159dp 变 346dp,L4 里 http:// https:// www. 回来了。三个坑:不能事后 wm size、AOSP Launcher3 在正方形屏必崩(divide by zero)、avdmanager 不可用(rc=0 却什么都没建)→ AVD 手写"
  source: run 36832802217
  affects: [e2e-capture]
