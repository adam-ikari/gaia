# Brain Index

_Auto-generated. Last updated 2026-10-30T03:18:12.614Z._

- [branch-and-gate-model](pages/branch-and-gate-model.md) — category: decision | tags: [release, ci, tooling, workflow] | ## 本仓库的分支保护不可用,所以规范必须自己说明边界
- [chinese-ime-lm-survey](pages/chinese-ime-lm-survey.md) — category: reference | tags: [survey, license, eval] | - **它是什么**:`metasequoiaime/chinese-ime-lm`(Rust,仓库 GPL-3.0,2025-11 建,869 stars),面向中文输入法的语言模型工程,配套输入法是 MSIME。
- [clipboard-panel](pages/clipboard-panel.md) — category: decision | tags: [ime, clipboard, l6, ui] | ## 剪贴板历史面板(2026-10-16 定案,2026-10-17 补读取防御)
- [e2e-capture](pages/e2e-capture.md) — category: project | tags: [e2e, ci, emulator, media, capture] | ## 当前状态(2026-10-03,v0.7.0)
- [fly-zone-basis](pages/fly-zone-basis.md) — category: decision | tags: [gesture, layout, ux] | 上滑选字有**两条起手路径**，它们各自把横向空间五等分，而**基准不是同一个量**：
- [gesture-animation-ownership](pages/gesture-animation-ownership.md) — category: decision | tags: [animation, gesture, candidate-bar, correctness] | # 一句话
- [hardkey-hidden-passthrough](pages/hardkey-hidden-passthrough.md) — category: decision | tags: [ime, hardware-key, key-routing, visibility] | <current best understanding — replace this with the real content>
- [ime-insets-contract](pages/ime-insets-contract.md) — category: decision | tags: [ime android termux] | 输入法窗口的 insets 契约：**窗口全屏 ≠ 内容全屏**。
- [ime-interaction-model](pages/ime-interaction-model.md) — category: decision | tags: [ime ux keymap] | 模仿可可的交互模型，并按 [[tt2-hardware-constraints]] 重新排布可行通道。
- [ime-strategy-registry](pages/ime-strategy-registry.md) — category: decision | tags: [策略, 注册表, L4, 扩展性] | # 策略注册表与 L4 上下文键位
- [l6-virtual-keyboard](pages/l6-virtual-keyboard.md) — category: decision | tags: [ime, l6, ui, keymap] | ok
- [librime-android-build](pages/librime-android-build.md) — category: project | tags: [librime, android, ndk, cmake, jni, rime] | ## 结论先行
- [open-source-licensing](pages/open-source-licensing.md) — category: decision | tags: [license, open-source, gpl, distribution] | 盖亚输入法(Gaia)以 **GPL-3.0-only** 提供(`Copyright (c) 2026 adam-ikari`),源码仓
- [paging-feel](pages/paging-feel.md) — category: decision | tags: [gesture, animation, paging] | ---
- [pending-todos](pages/pending-todos.md) — category: project | tags: [todo, backlog] | ok
- [pinyin-dict-strategy](pages/pinyin-dict-strategy.md) — category: decision | tags: [pinyin, dict] | 自带"中等词库"，并提供导入通道扩容（用户已确认该路线）。
- [project-licensing](pages/project-licensing.md) — category: decision | status: archived | tags: [license, proprietary, distribution] | 盖亚输入法(Gaia)以 **GPL-3.0-only** 提供(`Copyright (c) 2026 adam-ikari`),源码仓
- [reference-cocoime](pages/reference-cocoime.md) — category: reference | tags: [ime, reference, cocoime] | 「可可拼音输入法」（COCOZQ，`com.cocozq.inputmethod.pinyin`）是本项目的模仿对象：基于早期 Android `LatinIME`，服务黑莓 Priv/KEYone/KEY2、Unihertz Titan 系列、Titan 2、Minimal P
- [release-flow](pages/release-flow.md) — category: project | tags: [release, ci, tooling] | ## 测试版(beta)不是版本发布:闸门要有专门模式
- [rime-candidate-consumption](pages/rime-candidate-consumption.md) — category: decision | tags: [rime, pinyin, 选词, 消费, 选字] | ## 单位是**含撇 raw 的直接下标**,由 `GetCurrentStartPosition()` 给 —— 第三次定案(2026-10-18)
- [rime-host-probe](pages/rime-host-probe.md) — category: reference | ## 它是什么
- [sensitivity-tiers](pages/sensitivity-tiers.md) — category: decision | tags: [gesture, threshold, sensitivity, deadzone] | ## 三条正交的防线(2026-10-16 之后的口径)
- [site-analytics](pages/site-analytics.md) — category: decision | tags: [Cloudflare Workers 自建 Umami:analytics, umami, cloudflare] | 站点统计走**自建 Umami**,部署在 Cloudflare Workers + D1(用户从 Umami/Plausible/GoatCounter/
- [site-copy](pages/site-copy.md) — category: decision | tags: [按 adamblog 的写作规则去 AI 味, 并对 Titan 2 量身定做的口径:copy, voice, titan2] | 站点文案的规则**不在本仓库**，在 `adamblog` 项目里。
- [site-e2e-video](pages/site-e2e-video.md) — category: project | tags: [ci, testing, video] | 站点的演示视频由 **CI 端到端测试顺便录出来**,不手工录。
- [site-mobile](pages/site-mobile.md) — category: decision | tags: [视口被撑开是首要故障, 3D 投影的尺寸必须实测:mobile, responsive, css, 3d, viewport] | # 移动端版式:先量视口,再看图
- [site-repo-topology](pages/site-repo-topology.md) — category: decision | tags: [site, monorepo, release, deploy] | 站点与应用在**同一个仓**(2026-10-27:网站仓 `adam-ikari/gaia` 删除,本仓改名为 `gaia`)。
- [site-style](pages/site-style.md) — category: decision | tags: [Apple 风格:design, theme] | 站点视觉按 **Apple 官网那种语言**做,不是"深色科技风"。
- [site-voice](pages/site-voice.md) — category: decision | tags: [naming, voice, content] | 站点的**产品名只有一个写法：盖亚输入法**。
- [spring-damping-direction](pages/spring-damping-direction.md) — category: decision | tags: [animation, physics, spring, damping] | ## 一、ζ 从 0.72→0.9→0.95→1.0 四轮「全白费」的真正原因(2026-10-09 推翻)
- [state-machine-approach](pages/state-machine-approach.md) — category: decision | tags: [architecture, state-machine] | ## 状态机怎么做(2026-10-16 定案)
- [tt2-hardware-constraints](pages/tt2-hardware-constraints.md) — category: project | tags: [titan2, hardware, gesture] | Titan 2（Android 15）的物理键盘既是输入设备也是一块电容触摸面，但**输入法拿不到它的触摸轨迹**，因此一切依赖"键盘滑动"的交互都必须换成系统事件或屏幕手势。
- [unverified-as-fact](pages/unverified-as-fact.md) — category: decision | tags: [lessons, method, verification, e2e] | 2026-10-08 真机首日(Titan 2):测出来的和推断出来的混在一起写成结论,于是**当时自认为已经知道**的东西全是错的 —— 但它们错得很有说服力,因为每一条都能自圆其说。
- [voice-input](pages/voice-input.md) — category: decision | tags: [ime, voice, permission] | 语音输入**只有一条路径:本机离线识别**,不依赖系统 `SpeechRecognizer`(它默认落到 Google 语音服务),也不再有任何云端引擎(2026-10-24 移除,见时间线 reversal)。
- [windows-native-build](pages/windows-native-build.md) — category: project | tags: [librime, windows, cmake, ndk, toolchain, build] | ## ????
