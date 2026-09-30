# Brain Index

_Auto-generated. Last updated 2026-09-30T09:50:22.179Z._

- [ime-insets-contract](pages/ime-insets-contract.md) — category: decision | tags: [ime android termux] | 输入法窗口的 insets 契约：**窗口全屏 ≠ 内容全屏**。
- [ime-interaction-model](pages/ime-interaction-model.md) — category: decision | tags: [ime ux keymap] | 模仿可可的交互模型，并按 [[tt2-hardware-constraints]] 重新排布可行通道。
- [ime-strategy-registry](pages/ime-strategy-registry.md) — category: decision | tags: [策略, 注册表, L4, 扩展性] | # 策略注册表与 L4 上下文键位
- [pinyin-dict-strategy](pages/pinyin-dict-strategy.md) — category: decision | tags: [pinyin, dict] | 自带"中等词库"，并提供导入通道扩容（用户已确认该路线）。
- [reference-cocoime](pages/reference-cocoime.md) — category: reference | tags: [ime, reference, cocoime] | 「可可拼音输入法」（COCOZQ，`com.cocozq.inputmethod.pinyin`）是本项目的模仿对象：基于早期 Android `LatinIME`，服务黑莓 Priv/KEYone/KEY2、Unihertz Titan 系列、Titan 2、Minimal P
- [release-flow](pages/release-flow.md) — category: project | tags: [release, ci, tooling] | 发布 = 改版本号 → 打 `v*` tag → 推 tag,CI 负责出包和 release 页面。
- [tt2-hardware-constraints](pages/tt2-hardware-constraints.md) — category: project | tags: [titan2, hardware, gesture] | Titan 2（Android 15）的物理键盘既是输入设备也是一块电容触摸面，但**输入法拿不到它的触摸轨迹**，因此一切依赖"键盘滑动"的交互都必须换成系统事件或屏幕手势。
