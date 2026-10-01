---
id: e2e-capture
title: "CI 素材采集与模拟器 E2E:生命周期坑与键码/词库前置"
category: project
status: active
tags: [e2e, ci, emulator, media, capture]
created: "2026-10-01T05:09:58"
updated: "2026-10-01T05:10:12"
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

## 五、词库要 30 秒才加载,截图之前必须等

`dict loaded: 348723 entries, 8586..29477 ms`。没就绪时组词**没有候选**,
候选行是空的 —— 截出来是一张"只有输入框"的图,看着像功能坏了。
采集前轮询 logcat 等 `dict loaded:`(最多 180 秒)。这一条不做,前 7 张图全废。

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
要么 `verify` 说不清。候选条不在 uiautomator 的 dump 里(它是另一个窗口),
动作是否发生只能靠输入法自己的日志断言(比如 `fly page:` 的行数变化)。

## 素材的诚实边界

采集跑的是 **debug 包 + x86_64 模拟器**,不是 Titan 2:分辨率、键盘、insets 都不同。
站点文案只能说「自动测试跑下来的」,**不能说「和你下载的是同一个包」**(那是假的,
站点放的是 release APK),也不能冒充真机截图。


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
