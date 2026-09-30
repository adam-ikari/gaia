---
id: site-e2e-video
title: "CI 端到端测试与录屏,产物给站点做视频"
category: project
status: active
tags: [ci, testing, video]
created: "2026-09-30T12:15:33"
updated: "2026-09-30T12:16:40"
---

<!-- compiled_truth -->
站点的演示视频由 **CI 端到端测试顺便录出来**,不手工录。流程与已知约束:

**流程**:应用仓库 `hwkbd_ime` 的 e2e 工作流 → 拉起 Android 模拟器 → 装 APK → 启用 IME →
跑 `tools/aitest` 的场景 → 全程 `adb shell screenrecord` → 产出 mp4 构件 →
把 mp4 放进站点仓库 `docs/public/media/` → 站点用 `<video>` 播。

**必须在文档/说明里讲清的边界**:这个应用是给**物理全键盘**设计的(Titan 2 的 Alt/Sym/Fn),
模拟器没有那些键。所以:
- 能录到的:拼音组词、空格上屏、五键选字(Shift/Sym/空格/Ctrl/Alt 都是标准 KEYCODE,
  `input keyevent` 能注入)、中英切换、状态行与 L4 虚拟键、设置页。
- **录不到/测不了的**:`KEYCODE_SYM` 这类非标准键码在模拟器上会拉起 AGUI 选择器,
  L3 层与 Sym 参与的五键槽位必须真机才验得了(见 `tools/aitest/PLAYBOOK.md` 的"局限"一节)。
- `tools/aitest` 里两个场景依赖 AI 视觉判卷(`candidate-center`、`layer-chips`),
  CI 没有多模态 API Key,所以 CI 只跑确定性断言,这两个场景标记为跳过而不是判 FAIL。

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
