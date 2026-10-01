---
id: site-e2e-video
title: "CI 端到端测试与录屏,产物给站点做视频"
category: project
status: active
tags: [ci, testing, video]
created: "2026-09-30T12:15:33"
updated: "2026-10-01T14:25:55"
---

<!-- compiled_truth -->
站点的演示视频由 **CI 端到端测试顺便录出来**,不手工录。流程与已知约束:

**流程**:应用仓库 `hwkbd_ime` 的 e2e 工作流 → 拉起 Android 模拟器 → 装 APK → 启用 IME →
跑 `tools/aitest` 的场景 → 全程 `adb shell screenrecord` → 产出 mp4 构件 →
把 mp4 放进站点仓库 `docs/public/media/` → 站点用 `<video>` 播。

## 「产物看起来正常」不等于「产物可用」

站点上那个 1.2MB 的 mp4 **连 moov atom 都没有**,`ffprobe` 报 `Invalid data found`。
它在仓库里躺了三个提交,每轮 CI 全绿。

**根因不是"文件被截断"(我一开始猜错了),是 screenrecord 压根没录上**:

    screenrecord --time-limit 300
    → Time limit 300s outside acceptable range [1,180]

参数越界 ⇒ 它打印一行原因就退出,**文件不生成、进程不在,但 `adb shell` 本身回 0**。
所以 `nohup screenrecord ... &` 的返回值说明不了任何事;pull 报出来的
"No such file"指向文件路径,而真正的原因是参数 —— stderr 又被 `>/dev/null` 吃掉,
连那句原因都看不到。

**怎么一眼看出是"没录上"而不是"录坏了"**:按码率算内容长度。旧文件 1.16MB ÷ 8Mbps
≈ **1.2 秒**,而 brain 记的录屏是 89s。差 70 倍 ⇒ 不可能是截断。

⇒ 三条都要:① `--time-limit` 给上限值(180);② 起录后**轮询文件在不在**,
不在就把 screenrecord 自己的报错捞出来打出来(**失败要能指出原因**,不能指向路径);
③ pull 之后 `ffprobe` 验流。

**同时要 faststart**:screenrecord 出的是 moov 在**文件末尾**的 mp4,浏览器要整个
下完才知道时长和分辨率,而站点视频是懒加载的,访客看到的是"转圈很久才播"。
`ffmpeg -c copy -movflags +faststart` 重封装(不重编码),改完**再验一次**。
临时文件名**必须以 .mp4 结尾**(ffmpeg 靠扩展名判断输出格式)。

**体积小 ≠ 录坏了**:这个 AVD 上候选行大部分时间静止,H.264 对静止画面几乎不耗码率,
151s 的视频只有 0.35MB(实测 0.02Mbps)。8Mbps 是上限不是保底。判据只有一个:能不能解。

**站点侧刻意不做存在性检查**:VitePress 是静态站点,构建时看不到 `public/` 下的
运行时文件,任何判断都得靠 fetch,而 fetch 会带回加载态、失败态和"文件在但播不了"
这种最难看的状态。素材是否就位由采集端保证。

**录屏本身的坑**:码率给高一点(≥6 Mbps)上限才不花;时长上限 180s;
先 `adb shell rm` 旧文件,否则拿到的是上一次残留;pull 之后要确认文件大小不是 0。


## Timeline

- time: 2026-09-30T12:15:33
  kind: decision
  summary: "Created this page: CI 端到端测试与录屏,产物给站点做视频"
  source: "2026-09-30 用户定"
  affects: [site-e2e-video]

- time: 2026-09-30T12:16:40
  kind: decision
  summary: "CI 起模拟器跑端到端场景并 screenrecord,mp4 作为构件产出,再放进站点 public/media"
  source: "2026-09-30 用户定:用 CI 做端到端测试并录屏"
  affects: [site-e2e-video]

- time: 2026-10-01T13:14:05
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-e2e-video]

- time: 2026-10-01T14:25:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-e2e-video]

- time: 2026-10-01T14:25:55
  kind: reversal
  summary: "更正上一条:坏 mp4 不是'screenrecord 被杀导致尾部截断'(我上一提交这么写的),而是 --time-limit 300 越界被 screenrecord 直接拒绝、压根没录上。证据:1.16MB ÷ 8Mbps ≈ 1.2 秒内容,而录屏是 89s,差 70 倍,截断做不到。判据=按码率算内容长度"
  source: "2026-10-01 本机拿到 root 后实测 screenrecord 的参数校验"
  affects: [site-e2e-video]
