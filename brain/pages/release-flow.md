---
id: release-flow
title: "发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
category: project
status: active
tags: [release, ci, tooling]
created: "2026-09-30T09:50:05"
updated: "2026-10-08T01:17:15"
---

<!-- compiled_truth -->
## 发版前:同一个检查在两个环境跑通,不代表它对

`check-version.sh` 验三件事:格式、tag 与 `versionName` 一致、
`versionCode` 未与任何旧 tag 冲突。它在 release.yml 里排在「Unit tests」之前
—— 版本号错了就不该浪费时间跑测试。

接进 CI 的第一次就挂在 `versionCode 19 已被 tag v0.7.0 用过` ——
**自己跟自己比**。

原因:CI 里跑的时候 **tag 已经存在**(tag 的 push 就是工作流的触发条件),
所以待发布的那个 tag 必然出现在 `git tag` 输出里,而它带的 `versionCode`
恰好等于当前值,`>=` 判定成立。本地跑时看不到,因为那时新 tag 还没打。

**两个入口的初始状态不同**:本地是「tag 未打」,CI 是「tag 已存在」。
所以脚本现在排除待发布的那一个;不给 tag 参数时自动取「指向 HEAD 的 tag」
来排除(对应「本地 `git tag -a` 打完还没推」的状态)。

**教训:接一个检查进 CI 时,要问「两边初始状态一样吗」,而不是「本地过了吗」。**

## Android SDK 许可:三段弯路

1. 裸调 `sdkmanager` → `command not found`(runner 镜像上不在 PATH)
2. 加 `android-actions/setup-android` → 它内部就会跑一次 sdkmanager,
   **许可没接受时它自己就 exit 1**,报的是一屏 SDK Preview 许可条款
3. 试 `android-actions/accept-android-sdk-licenses` →
   `Unable to resolve action ... repository not found`(那个路径不存在)

最终:**不用那些 action**。GitHub 的 ubuntu runner **本来就预装了
cmdline-tools**(`/usr/local/lib/android/sdk/cmdline-tools/16.0`),
只是不在 PATH 上。把 `$ANDROID_HOME/cmdline-tools/latest/bin` 加进 PATH,
然后 `yes | sdkmanager --licenses`(要 `|| true`,`yes` 被 SIGPIPE 杀掉会非零)
+ `sdkmanager --install`。

## 三条流水线的依赖清单要一起改

release.yml 加了 `submodules: recursive` + NDK + Boost 时,ci.yml / e2e.yml
**没跟着改**。两个后果,各自报一个**指不到真正原因**的错:

- 没带 submodules ⇒ librime 的 vendored deps 是空目录 ⇒
  `CMake Error: The source directory .../deps/yaml-cpp does not exist`
  (看着像依赖缺失,其实是没 checkout)
- 没装 NDK/Boost ⇒ `CMakeLists.txt:158「找不到 boost 头文件」`
  (它确实找不到,但指向 CMake 而不是 CI)

**教训:加了「某条流水线才需要的东西」,要么三条都加,要么抽成 reusable workflow。**
只加一条,后面两条会以「看起来像别的问题」的形式挂掉。

顺带一提:`gh run rerun` 重跑的是**原来那次 run 的 commit**,不是最新的 master。
改了东西想验证,得推新 commit(空提交也行)。

## E2E 模拟器:FATAL 藏在第 17 行,前面全是 INFO

2026-10-03:模拟器启动后立刻退出,超时 600s 未 boot。真正的错是:

```
FATAL | Not enough space to create userdata partition.
 Available: 6725.43 MB, need 7372.80 MB
```

**磁盘不够**,不是 APK 的问题,也不是 AVD 配置写错。原因是接了 native 之后
这条流水线多装了 NDK(2.5GB)+ CMake,加上系统镜像与 Gradle 缓存把盘吃掉了。
APK 打完就删 NDK/CMake/`~/.gradle/caches`,并在 AVD 步骤前打 `df -h`。

**教训:action 的输出前面一堆 INFO 时,先去尾部找 FATAL/ERROR,别顺着读。**

## 站点同步:现在是两条手动步骤,判据在脚本退出码里

发版后站点更新要跑**两次**,都是本机脚本、不需要任何 secret:

```bash
tools/release/sync-apk-to-site.sh v0.7.10   # 把包挂到 gaia 的同名 Release
tools/release/publish-site.sh               # 构建站点 + 推产物 + 验线上字节
```

`release.yml` 里那个等价的 `publish-site` job 仍然需要 `WEB_RELEASE_TOKEN`,
**没配就一直 skipped 且退出码 0** —— 流水线全绿但站点停在旧版本。
2026-10-02 就这样让 v0.4.2 / v0.4.3 只存在于私有仓库。
现在多一条 `::warning::` 说明这件事,但**判据仍然是脚本的退出码,不是 CI 的状态**。

验收不只看文件名:匿名下载 `releases/latest/download/app-release.apk` 与
release 附件**逐字节**比,再从 APK 里读 manifest 版本号 + 确认
`lib/arm64-v8a/librime_jni.so` 在包里。Pages 那份同样要逐字节核对
(`publish-site.sh` 最后一步自动做)。

站点源码、产物布局、三道判据与「缺 vs 坏」的规则见 [[site-repo-topology]]。


## Timeline

- time: 2026-09-30T09:50:05
  kind: decision
  summary: "Created this page: 发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
  source: "2026-09-30 发布 v0.4.0 实操"
  affects: [release-flow]

- time: 2026-09-30T09:50:22
  kind: decision
  summary: "记录发布链路、签名现状与 runner 上 aliyun 502 导致的 lintVital 失败(重跑可解)"
  source: "2026-09-30 发布 v0.4.0"
  affects: [release-flow]

- time: 2026-09-30T09:57:18
  kind: reversal
  summary: "仓库转私有后,'签名私钥公开'这个当初权衡的代价不再成立(改由 [[project-licensing]] 承载)"
  source: "2026-09-30 用户定案项目闭源/私有"
  affects: [project-licensing]

- time: 2026-09-30T11:10:51
  kind: decision
  summary: "发布变成两步:应用仓库出包 + 传到公开站点仓库 gaia 的同名 Release(匿名可下)"
  source: "2026-09-30 建 Gaia 站点时定"
  affects: [release-flow]

- time: 2026-10-03T11:24:43
  kind: evidence
  summary: "check-version.sh 在 CI 里必然自比较失败(tag 已存在,自己跟自己比);三段 sdkmanager 弯路;E2E 模拟器 FATAL 其实是磁盘不够"
  source: "2026-10-03 v0.7.0 发版,CI 连续失败三轮"
  affects: [release-flow, librime-android-build]

- time: 2026-10-03T11:25:09
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 发版实战"
  affects: [release-flow]

- time: 2026-10-05T13:19:37
  kind: decision
  summary: "分支保护用不了(私有仓+Free 套餐,protection 返回 403 要 Pro)⇒ 规范不靠它:闸门收进 tools/release/gate.sh,判定依据是退出码而非 CI 状态;E2E 与 release 在 tag push 上并行无依赖,硬接需重构成 reusable workflow 且仍验不到产物(debug 包不过 R8),定为发版前人工前置 + commit 引用可查"
  source: "2026-10-05 制定分支与发布规范"
  affects: [release-flow]

- time: 2026-10-05T13:19:37
  kind: evidence
  summary: "Actions 账单失败时 job 一个 step 都不执行、2 秒、conclusion=failure ⇒ fail-open:徽章红但零检查。这是否认「CI 绿=检查过」的直接证据,也是「必须失败就大声响」的来源"
  source: "2026-10-05 实测"
  affects: [release-flow]

- time: 2026-10-05T13:19:37
  kind: evidence
  summary: "本机 CMake 回落到了 PATH 上的 3.31.10($SDK/cmake 不存在),CI 固定 3.22.1 ⇒ 本地包与 CI 包工具链不同。预检因此查「生效的」CMake 并报差异,不查固定路径"
  source: "2026-10-05 gate.sh 预检"
  affects: [release-flow]

- time: 2026-10-06T00:15:29
  kind: decision
  summary: "v0.7.10 是**手工**发布与同步的(私有仓 Actions 账单锁定,job 从未启动;公开仓 gaia 的 Deploy site 正常)。tag/release/站点三处已对齐,Pages 与本地包 sha256 逐字节一致。但因此 CI 出的 v0.7.10 字节会不同 ⇒ 下一个版本必须是 30/0.7.11"
  source: "2026-10-05 v0.7.10 实际发布"
  affects: [release-flow]

- time: 2026-10-06T00:15:29
  kind: evidence
  summary: "公开仓(gaia)的 Actions **不受账单影响**,私有仓(hwkbd_ime)受影响 —— 同样账号,差别只在可见性。站点部署这条路一直可用,被挡的只有发版构建"
  source: "2026-10-05 同步 v0.7.10 实跑"
  affects: [release-flow]

- time: 2026-10-06T00:15:29
  kind: evidence
  summary: "两处 fail-closed 误判:非致命资产缺失被 return 1 + set -e 掐死脚本;一次 API 抖动(unknown)被当成部署失败并 exit,挡住了后面的权威字节比对。判据:便宜的信号负责路由,贵的权威检查负责下结论;「拿不到」与「它是坏的」代价不对称时,方向要反过来"
  source: "2026-10-05 同步 v0.7.10 实跑"
  affects: [release-flow]

- time: 2026-10-06T04:17:39
  kind: decision
  summary: "发布改成三条并行渠道,各自是**独立应用**:正式版 dev.hwkbd.ime(语义版本)、测试版 dev.hwkbd.ime.beta(过 R8)、开发版 dev.hwkbd.ime.dev(不过 R8,版本名只有 git hash)。站点三个固定 URL。beta 按「release 里有没有 app-beta-* 资产」挑而不是「最新 prerelease」—— 按内容挑而非命名约定挑"
  source: "2026-10-06 三渠道并行"
  affects: [release-flow]

- time: 2026-10-06T04:17:39
  kind: evidence
  summary: "三个结构性陷阱,全部实测撞到:①滚动 release 若不是 prerelease 会**hijack GitHub 的 latest**,正式版按 latest 取包 ⇒ 部署失败、正式版下载按钮整个挂掉;②gh release upload 的 <path>#<label> 里 # 是 **label 不是文件名**,资产名取自 basename,而 deploy 按资产名取 hash ⇒ 静默跳过;③可选渠道必须非致命,缺 beta 只 warning —— 但正因非致命,「查错 release 导致 dev 没上线」也只表现为一行 warning + 部署 success"
  source: "2026-10-06 发布 dev 渠道实跑"
  affects: [release-flow]

- time: 2026-10-06T09:44:43
  kind: decision
  summary: "渠道名一律用**测试版**:这个项目从来没有正式版。我一度把主下载标成「正式版·已撤回」,用户指出「已撤回」预设了它曾经是正式版 —— 那是凭空给它造的一段合法性,而且用一个更醒目的标记盖住了更该说的「这个测试版有已知缺陷」。状态默认值是 withdrawn 而非 official,且缺文案不等于没问题(注入漏了只是没提示,默认 official 则是明知道有问题却不说)"
  source: "2026-10-06 用户「一直以来都是测试版」"
  affects: [release-flow]

- time: 2026-10-06T09:44:43
  kind: evidence
  summary: "我把「xian 在词典里是合法音节所以西安打不出来」当成取消分隔符的**代价**写进注释,用户当场指出 xian→西安本来就支持。那只是推理没验过 —— 与前三例(下载截断被当成功、initWith 不继承、v 当分隔符)同形。判据:凡是陈述「为什么」的注释,必须能指到一个源码行、一次产物检查或一条真机日志;指不到的是推理,得标出来或删掉"
  source: "2026-10-06 同一个形状的第四个实例"
  affects: [release-flow]

- time: 2026-10-06T10:21:45
  kind: evidence
  summary: "2026-10-06 发 c271426 时实测:publish-dev.sh 删旧资产**失败**(旧的 app-dev-43af8e8 留在远端),而脚本把 stderr 吞掉、只打 warning、**照样触发了站点部署** —— 拿一个有两份 dev 资产的 release 去构建。这次站点碰巧取对了(`take_optional` 用 `| last`,GitHub 按创建序返回,last=最新那份),但那是**依赖顺序的巧合**不是判据;手动重删即成功。已改成:删除重试 5 次、错误不再吞、**收敛不到恰好一份就不触发部署并 exit 1**。判据的不对称:「渠道缺失」非致命(它可能真还没上线,`take_optional` 本就设计成非致命),「渠道歧义」致命(站点会挑错字节且没人会响)—— 与 sync-apk-to-site 那条不矛盾,那是缺失,这是歧义"
  source: "2026-10-06 发 c271426 实测 + 手动重删成功"
  affects: [release-flow]

- time: 2026-10-08T01:17:15
  kind: decision
  summary: "站点同步改为两条本地脚本;判据是脚本退出码,不是 CI 状态"
  source: "2026-10-08 monorepo 化"
  affects: [release-flow]
