---
id: site-e2e-video
title: "CI 端到端测试与录屏,产物给站点做视频"
category: project
status: active
tags: [ci, testing, video]
created: "2026-09-30T12:15:33"
updated: "2026-10-01T13:14:05"
---

<!-- compiled_truth -->
站点的演示视频由 **CI 端到端测试顺便录出来**,不手工录。流程与已知约束:

**流程**:应用仓库 `hwkbd_ime` 的 e2e 工作流 → 拉起 Android 模拟器 → 装 APK → 启用 IME →
跑 `tools/aitest` 的场景 → 全程 `adb shell screenrecord` → 产出 mp4 构件 →
把 mp4 放进站点仓库 `docs/public/media/` → 站点用 `<video>` 播。

## 「产物看起来正常」不等于「产物可用」(2026-10-01)

站点上那个 1.2MB 的 mp4 **连 moov atom 都没有**,`ffprobe` 报 `Invalid data found`,
浏览器播不了。它在仓库里躺了三个提交,每轮 CI 全绿。

**根因不是没检查,是检查了但看不见**:`adb pull` 不报错就是成功;而 `killall -9`
杀掉的 screenrecord 缓冲区还没落盘,**尾部截断的 mp4 依然是合法文件**。
截图全绿 + 退出码 0 ⇒ 没人发现。

⇒ 采集端现在**必须验内容**:pull 之后用 `ffprobe` 读流信息,读不出就删文件并让退出码
非 0。截断的 mp4 过不了这关 —— moov 在尾部,一截就没了。

**同时要 faststart**:screenrecord 出的是 moov 在**文件末尾**的 mp4,浏览器要整个
下完才知道时长和分辨率,而站点视频是懒加载的,访客看到的是"转圈很久才播"。
`ffmpeg -c copy -movflags +faststart` 重封装(不重编码),改完**再验一次**。

两个实现细节:临时文件名**必须以 .mp4 结尾**(ffmpeg 靠扩展名判断输出格式,
叫 `x.mp4.faststart` 会报 "Unable to find a suitable output format");坏文件要在
`manifest.json` 里写 `video:false`,站点的 `Shots`/`DemoVideo` 据此不渲染。

**站点侧刻意不做存在性检查**:VitePress 是静态站点,构建时看不到 `public/` 下的
运行时文件,任何判断都得靠 fetch,而 fetch 会带回加载态、失败态和"文件在但播不了"
这种最难看的状态。素材是否就位由采集端保证。

**录屏本身的坑**:模拟器 `screenrecord` 码率给高一点(≥6 Mbps)才不花;时长要设上限,
并且先 `adb shell rm` 旧文件,否则拿到的是上一次残留;pull 之后要确认文件大小不是 0。


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
