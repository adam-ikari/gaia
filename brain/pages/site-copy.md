---
id: site-copy
title: "站点文案:按 adamblog 的写作规则去 AI 味,并对 Titan 2 量身定做的口径"
category: decision
status: active
tags: [copy, voice, titan2]
created: "2026-09-30T12:56:04"
updated: "2026-10-04T07:14:30"
---

<!-- compiled_truth -->
站点文案的规则**不在本仓库**，在 `adamblog` 项目里。改任何页面之前先读那份，别凭印象写。

**规则来源**:`adamblog/.claude/skills/blog-writing/SKILL.md`(作者最新写作意图，权重最高)+ 同项目
`brain/pages/blog-conventions.md`。那套 skill 明确说:旧文章只能当格式参考,风格基准是 skill 本身。
本项目照它执行,针对产品页做两处收敛:

- **广告词按 Apple 那一套收敛**:每屏只讲一件事,标题 2–8 字,CTA 用动词短语(下载 / 进一步了解),
  不用感叹号,不堆形容词。
- **句式规则全盘照用**:主语要是行动者不是概念;一句说完一句事,不写"问题出在 X"再另起一句解释;
  限定语该写就写("账面上看是…"而不是"账面是…");主干一路走到谓语,中间不插"跟…无关,却…";
  段落长短要交错,不许全是三四字短句(断奏)。

**给产品页额外加的两条**:

1. **不用生僻词**。"阈值""键位映射""状态行""运行时筛选""分节符"这类词,用户一看就卡住。改成
   "拖到位""这行键""屏幕左下角"。技术细节要么删掉,要么换成一句人话。
2. **不讲实现,不讲仓库**。不提包名、构建脚本、内部命名、CI 细节;权限/数据这类事按事实陈述,
   不解释机制。

## 写交互前先读代码,别照着印象写(2026-10-01 用户纠正「上滑是上滑键盘」)

**手势的"表面"和"分区维度"都是能被写错、且写错了看不出来的东西。** 站上把飞字写成
「按住候选往上拖」——表面错了(真机是在**键盘表面**上滑,候选栏上滑只是同样联动的次要路径),
文案还跟着 `SwipeFeel.vue` 一起错:那个组件只有一行候选词、没有键盘。

对照代码核过的几条事实(应用仓库 `hwkbd_ime`):

- **分区按横向,不按纵向**:`ImeService.kt:541` 的 `zoneOf(x) = x / xMax * 5`(xMax=1440)。
  手指落在哪一列 → 决定选哪个词;竖直位移只决定**抬起多远算够**(threshold 默认 160,
  设置页三档 100/160/240),死区 = threshold/4,死区内整体忽略,过阈值立即提交不等抬手。
- **点击与上滑是两条路径、一个终点**:都汇到 `ImeController.selectCandidate(468)`,
  经 `displayOrder` 把显示位换算成逻辑位再 `commitText`。所以两种手势上屏的是同一条词。
- **候选栏自己也支持上滑**(`CandidateBarView.kt:619` 的 `beginFlyDrag`),是同一组件内的另一条路。

⇒ 演示交互的组件,形状必须和真机一致(哪个表面、分区怎么切),否则它演示的不是这个东西。

**产品口径(用户明确纠正过两次)**:盖亚输入法是**给 Unihertz Titan 2 量身定做**的,不是"兼容全键盘"。
别家机型没测过,站上要这么写,别写"多数机型可用"。

**不讲的东西清单**(见 [[site-voice]]):不提 Gaia、不提闭源、不提包名、不写反馈渠道、不提模仿对象。

**数据表述跟事实走**:应用将来会收集匿名使用数据与词库改进数据(选了哪个词、排第几位等),
用来改词库;不收输入框内容与草稿;可随时关。所以**站上不许再写"不联网/不上传"**——
用户明确说过未来要收,写了就是撒谎。文案按 `about.md` / 首页最后一屏那段口径走。


## Timeline

- time: 2026-09-30T12:56:04
  kind: decision
  summary: "Created this page: 站点文案:按 adamblog 的写作规则去 AI 味,并对 Titan 2 量身定做的口径"
  source: "2026-09-30 用户指定参考 adamblog 写作技能"
  affects: [site-copy]

- time: 2026-09-30T12:56:04
  kind: decision
  summary: "文案以 adamblog 的 blog-writing skill 为准:去 AI 味 + 不用生僻词;口径改为 Titan 2 量身定做"
  source: "2026-09-30 用户指定"
  affects: [site-copy]

- time: 2026-10-01T12:59:34
  kind: decision
  summary: "「上滑」的说法统一成在键盘上划,且必须带上分区维度:区号由手指落点的横向位置决定(屏宽五等分,ImeService.kt:541 zoneOf),竖直位移只决定何时上屏 —— 站上原先写成「按住候选往上拖」,既说错了表面也说错了分区"
  source: "2026-10-01 用户纠正 + 读 ImeService.kt/CandidateBarView.kt 核对"
  affects: [site-copy, site-mobile]

- time: 2026-10-01T13:00:10
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [site-copy]

- time: 2026-10-04T07:14:30
  kind: decision
  summary: "首页加两屏(物理动画 / Rime);页脚与设置页署名从 jieba/pinyin-pro 改成 Rime —— 那两个换内核时已删除,留着是错的署名"
  source: "2026-10-04 用户要求说明使用 rime 与基于物理的动画"
  affects: [site-copy, site-voice]
