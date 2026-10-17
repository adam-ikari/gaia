---
id: state-machine-approach
title: "状态机怎么做:sealed 状态 + 纯 transition 函数,不引库(2026-10-16 定)"
category: decision
status: active
tags: [architecture, state-machine]
created: "2026-10-16T08:18:37"
updated: "2026-10-17T05:16:37"
---

<!-- compiled_truth -->
## 状态机怎么做(2026-10-16 定案)

本项目**不引入状态机库**,一律用 **sealed 类型 + 纯 transition 函数**(状态是值、迁移是函数),配 JVM 单测。

已按此形态落地的:[[ime-interaction-model]] 的 `LayerState`(L1 / Locked / OneShot / Hold —— 非法组合写不出来)、`ShiftMode`(OFF/ONCE/CAPS/HELD,取代两个字段)、长按字母键的 `LongPress`(arm / confirm / cancel / clear 四个迁移);物理与动画侧的 `SpringSpec`、`SwapAnim`、`Rubber` 也是同一路数("纯函数 + 薄壳")。

### 为什么不用库(2026-10-16 调研,当天用户定案)

- Kotlin/Android 生态里最成比例的实现是 **Tinder StateMachine**(`com.tinder.statemachine:statemachine`),但它**最新版仍是 0.2.0(2019 年发布,7 年未更新)**,且要往构建里引入 **kapt / 注解处理器(本仓当前零 kapt)**,生成类又会削弱"状态机直接可读"这一点;更重的 MVIKotlin / Decompose 属架构级替换,**不成比例**。
- 本仓**闭源且在意 APK 体积**(见 [[project-licensing]]),加依赖还要补 THIRD_PARTY_NOTICES。
- **已有正面证据**:[[unverified-as-fact]] 里那条 ζ 阻尼比的错判(查了、测了、单测全绿还是错),最终就是靠**拆纯函数 + JVM 单测**(断言阻尼比不是 0.5)修掉的。这正是"可单测的纯函数"相对"库"的价值。
- Maven Central 可达(技术上能加依赖),结论仍是**不需要**。

### 判断口径

状态复杂度用"**把状态与迁移写成值 + 纯函数 + 单测**"来降,不用框架来降。判据:一次迁移如果写不成能单测的纯函数,那多半是状态本身没编排对,而不是缺个库。

### 范围(2026-10-16 用户定)

**只维持已经收口的长按状态机**,其余两块暂不动:① `ImeController` 的输入机(onKeyDown/onKeyUp 的修饰键×层×组词×五键选字交叉);② `CandidateBarView` 手势机(spring/翻页/飞字/橡皮筋/swap)。将来要整理时,按本节口径办。


## Timeline

- time: 2026-10-16T08:18:37
  kind: decision
  summary: "Created this page: 状态机怎么做:sealed 状态 + 纯 transition 函数,不引库(2026-10-16 定)"
  source: "2026-10-16 用户「使用状态机库解决状态的复杂性」→ 选定 sealed + 纯函数"
  affects: [state-machine-approach]

- time: 2026-10-16T08:18:37
  kind: decision
  summary: "建立状态机方针页:sealed 状态 + 纯 transition 函数 + 单测,不引状态机库(调研 Tinder 0.2.0/kapt/体积/许可证四条理由),范围只维持长按、其余两块暂不动"
  source: "2026-10-16 用户「使用状态机库解决状态的复杂性」+ 选定 B 方案"
  affects: [state-machine-approach]

- time: 2026-10-17T05:16:37
  kind: decision
  summary: "raw/rawBase 写权限收敛(本方针落地实例):ImeState 的 raw/rawBase 改 private set,写入收成 appendRaw/dropLastRaw/consumeRaw/rebuildRaw/clearRaw 五个语义方法,每个按语义正确配对 raw/base(片段内编辑 base 不变/消费 base=consumed/重建 base=0/清空全清)。非法组合(raw 截了 base 忘归零)从写不出来。根因同 2026-10-16 连续消费 bug:三处手写复位点漏一处就漂。7 处调用点改方法,读取点零改动。"
  source: "2026-10-17 用户「修复」+ 选方案 B(收敛写权限,不加值对象类)"
  affects: [state-machine-approach, rime-candidate-consumption]
