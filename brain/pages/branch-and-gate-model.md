---
id: branch-and-gate-model
title: "分支模型与闸门:能强制与不能强制的分界"
category: decision
status: active
tags: [release, ci, tooling, workflow]
created: "2026-10-05T21:00:00"
updated: "2026-10-11T04:05:41"
---

<!-- compiled_truth -->
## 本仓库的分支保护不可用,所以规范必须自己说明边界

私有仓 + Free 套餐:`branches/master/protection` 返回 **403**(要 Pro)。
于是「开 required status checks」这类建议在本仓库**根本不能执行**。

推论有两条,都很硬:

1. **没有 PR 评审环节。** 没有分支保护时,PR 合并是**无检查的合并**
   (`gh pr merge --admin`、网页直接点合并都能过)。此时 PR 只剩「多一步点按钮」,
   却让人**以为**有评审 —— 这是又一处 fail-open,比直接推更糟。所以直接推 master。
2. **规范必须把「闸门」与「约定」分开写。** 混在一起会让人以为每条都有强制力。
   - 闸门 = `tools/release/gate.sh` 的退出码,CI 里跑,红了流水线就红。
   - 约定 = 文档里的句子,靠记性,`--no-verify` 能绕。

## 核心原则:判定依据是退出码,不是 CI 的状态

GitHub Actions 在私有仓超额度时,让 job **一个 step 都不执行**就失败:

    The job was not started because recent account payments have failed

2 秒、`conclusion: failure`、**零检查**。徽章是红的,看着像「测试挂了」,
实际是「什么都没发生」。

⇒ **fail-open**:它制造了有闸门的错觉,比没有闸门更危险,因为人会因此放心。
⇒ 所以「CI 是绿的」**不能**作为「检查过了」的证据。可信的只有闸门脚本的退出码。

这条同时解释了为什么规则要收进仓库:两边各写一遍命令,改一边忘另一边,
规则就只在一半的地方生效(已付过两次代价,见 [[release-flow]])。

## 版本号只在发版那一个 commit 里改

规则:`versionCode` / `versionName` 只允许在**发版 commit** 里改,
且那个 commit 必须是 master 的 tip。于是恒等式成立:

    master 的版本号 == 最新已发布版本的版本号

**来源是一次真实漂移**:2026-10-05 发现 `3eac2f1`(补埋点 + 修动画 + 加 E2E 的功能
commit)顺手把版本号提到 29/0.7.10,于是 master 停在 0.7.10 整整 14 个 commit,
而最新已发布的是 v0.7.9 且无对应 tag。

代价不是历史难看,是两句具体的话都定位不到东西:
- 用户报 bug 说「我用的 0.7.10」—— 指向一个**从不存在的产物**;
- master 编出的包自称 0.7.10,装到真机上设置页也写 0.7.10。

而 `check-version.sh` 验的是「号有没有撞」与「tag 对不对」,
**没有「master 该自称什么」这一问** —— 这是覆盖率的缺口,不是它的 bug。

### 判据要比「值」,不是比「谁碰过这个文件」

实现时的第一版判据查的是「`$top..HEAD` 里哪个 commit 碰过 `build.gradle.kts`」,
结果把「顺手修了个 typo」也判成一次 bump。正确做法是解出 HEAD 与最高 tag
各自的版本号**比值**。

**教训:判据要对准被问的那个量。** 「谁碰过文件」是「有没有人改过」,
「值相等吗」才是「master 该自称什么」—— 两者在多数 commit 上同形,
只在顺手改动的 commit 上分形。

## 四道闸门各有覆盖面,谁也替代不了谁

| 闸门 | 验什么 | 覆盖不到 |
| --- | --- | --- |
| 单测 | 纯函数、状态机 | native、R8 |
| JNI 符号 | R8 有没有削掉入口名 | 交互时序 |
| E2E | 打字 / 选字 / 翻页时序 | **debug 包不过 R8** ⇒ 产物 |
| 签名核对 | 能不能覆盖升级 | 任何功能 |

依据:JNI 闸门抓到过已发布的 v0.7.1 有 7/14 个入口被 R8 削掉(真机拼音引擎全废),
而 **E2E 结构上看不到**,因为它跑 debug 包。

### E2E 硬接不进流水线 —— 这是结论,不是拖延

`on: push: tags` 上 E2E 与 release **并行、无依赖** ⇒ E2E 红了 release 照样绿。
但接成前置需要:`needs` 跨不了 workflow ⇒ 重构成 reusable workflow ⇒
代价大且丢掉 `workflow_dispatch`(素材采集要用)。而**换来的仍不是「产物被验过」**,
因为 E2E 装的是 debug 包。

⇒ 诚实做法:E2E 是**发版前的人工前置**,不是产物闸门。
唯一可查的部分是「不许默默跳过」:发版 commit 须引用 E2E run 或写明为何不做。

**这个检查只在本地推 tag 前跑,不能进 CI。** tag 推上去时 commit message 早写死了,
CI 里查它等于稳定误报 —— **会误报的闸门只会被绕过去**,不如没有。

## 环境差异要主动说出来

本机 CMake 回落到了 PATH 上的 3.31.10(`$SDK/cmake` 不存在),CI 固定 3.22.1
⇒ 本地编出的包与 CI 编的不是同一套工具链。

所以预检查的是**生效的** CMake(AGP 解析顺序:`local.properties` 的 `cmake.dir`
→ `$SDK/cmake/<v>` → PATH),并**报出差异而不 fail** —— 本地闸门不是产物闸门。
但必须说出来,因为它的失效方式正是「本地能跑线上崩」。
**拦得住的只有「产物必须由 CI 出」这一条。**

## 会误报的闸门等于没有闸门

推广:任何检查若在正常运行路径上稳定误报,唯一结果是被绕过。
所以定检查时先问「它在健康状态下会不会报?」——
漂移判据的四种情形(无漂移 / 待发版 / 漂移 / 健康仓库)都实测过,
其中「健康仓库不误报」是最容易被跳过、也最致命的那一种。

## Timeline

- time: 2026-10-06T09:44:32
  kind: decision
  summary: "① E2E 搬回本地真机(tools/aitest/local.sh),删掉 e2e.yml —— 私有仓 Actions 计费且已欠费,而 E2E 本来就不是发布闸门(与 release 并行无依赖);本机无 /dev/kvm 时模拟器 SystemUI 自己 ANR,所以「是不是模拟器」做成会拦人的判据。② 公开渠道一律过 R8(用户「任何版本只要公开就需要混淆」)⇒ 闸门判据从「某个渠道名」改成**「有没有人能从公网拿到这个字节」**。③ 站点显示每条渠道的 sha256 与字节数:这个网络下下载会**静默截断**,而截断的 APK 安装器只给一句「软件包无效」,用户无从分辨"
  source: "2026-10-06 用户三条指令"
  affects: [branch-and-gate-model]

- time: 2026-10-06T13:55:04
  kind: evidence
  summary: "2026-10-06 实测:读闸门退出码时踩了 zsh 的坑 —— 管道首段的退出码是 `$pipestatus[1]`,不是 `${PIPESTATUS[0]}`(那是 bash)。我据此以为 `gate.sh` 在失败时返回 0,复核后真实退出码是 1。闸门本身没问题,**是尺子错了** —— 而这个项目的核心教条正是「判定依据是退出码」。教训:量退出码的那一行本身也要验一次,拿一个已知会失败的命令试一遍(比如 `false | cat`),确认尺子会响"
  source: "2026-10-06 重构后复核闸门时实测"
  affects: [branch-and-gate-model]

- time: 2026-10-11T04:05:41
  kind: evidence
  summary: "2026-10-11 实测确认 dev 与 release 混淆程度等同(用户问「开发版也要做混淆」):两边 mapping.txt 几乎等长(8679888/8679952),dex 里残留可读的 dev/hwkbd 类名各 57 个且列表逐一相同 —— keep 规则三渠道共用,dev 的 APK 大 3.7MB 只因为多编 x86_64。残留类名全部承重(Manifest 组件/XML inflater 的 View/JNI/枚举反射)。另发现疑似**白保**三条:-keep StatusRow 的理由「见 ime_root.xml 的 comment」——StatusRow 在布局里只出现在注释中,非 XML 元素,代码无反射;keymap.** 与 UserDict 的理由是「字符串查表」「JSON 字段名」,但那两个包里搜不到任何反射。收紧它们可让类名真被混淆,代价是运行期 ClassNotFound 风险,需真机逐项验证,用户 2026-10-11 定暂不动。判据仍是产物(mapping/dex),不是配置。"
  source: "2026-10-11 用户提问后实测 dev vs release 产物"
  affects: [branch-and-gate-model]
