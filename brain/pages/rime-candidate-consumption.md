---
id: rime-candidate-consumption
title: "选词消费:吃掉的量必须由引擎给,不能从 commit_text_preview 反推"
category: decision
status: active
tags: [rime, pinyin, 选词, 消费, 选字]
created: "2026-10-05T05:08:57"
updated: "2026-10-05T05:09:20"
---

<!-- compiled_truth -->
<current best understanding — replace this with the real content>

## Timeline

- time: 2026-10-05T05:08:57
  kind: decision
  summary: "Created this page: 选词消费:吃掉的量必须由引擎给,不能从 commit_text_preview 反推"
  source: "2026-10-05 真机 E2E 日志 + librime 源码"
  affects: [rime-candidate-consumption]

- time: 2026-10-05T05:09:07
  kind: reversal
  summary: "推翻「commit_text_preview = 未消费的原始输入 + 已选中的候选文字」这个模型:Composition::GetCommitText 里每个段都填候选文字(Segment::selected_index 默认 0,else 分支几乎走不到),所以剩下那部分是汉字。真机实测 nihao 选「你」把缓冲截成 n(应 hao)。改用 Composition::GetCurrentStartPosition() 直接问引擎要 consumed 的个数。"
  source: "2026-10-05 真机 E2E 日志 + librime src/rime/composition.cc + segmentation.h"
  affects: [rime-candidate-consumption, librime-android-build, e2e-capture]

- time: 2026-10-05T05:09:20
  kind: decision
  summary: "顺带修 ImeController.selectCandidate 给引擎的是页内下标:librime Select(index) 索引整份菜单(0..44),两者只在第 0 页相等。上屏的字不受影响(用我们自己的候选表),症状是「翻页后缓冲被截错」,第 0 页完全正常。改为 globalIndex = page * pageSize + li。"
  source: "2026-10-05 读 ImeController.selectCandidate 与 librime Context::Select/menu.cc 的索引口径"
  affects: [rime-candidate-consumption, paging-feel]
