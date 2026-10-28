---
id: release-flow
title: "发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
category: project
status: active
tags: [release, ci, tooling]
created: "2026-09-30T09:50:05"
updated: "2026-10-28T15:13:14"
---

<!-- compiled_truth -->
## 测试版(beta)不是版本发布:闸门要有专门模式

beta 的身份是 **git hash**(`0.7.11-beta.<hash>`),不占 versionCode、不打 `v*` tag。
所以闸门里「版本号漂移」「版本号 ↔ tag 对齐」那两步对它**不成立**:
master 的版本号本来就该等于已发布版本(「承袭」的定义),
于是「不撞旧 tag」对 master HEAD 永远是假的(那个号已被已发布的 tag 用掉了)。

2026-10-21 实测:直接跑 `gate.sh` 在第 3 步就挂(`check-version.sh(HEAD)没过`),
原因正是这条 —— 不是版本号错了,是**那条检查问错了对象**。

⇒ 加了 `gate.sh --beta`:版本两步跳过、**并且把跳过打出来**(第 2 步显示
「beta 的身份是 git hash,不吃 versionCode」)。闸门最怕的是「看起来跑过了」,
所以「跳过了哪一步」必须可见。剩下四步(单测 / 构建 release+debug+beta /
R8 / JNI)照跑 —— beta 要发到公开站点,产物侧那几步一步都不能少。

另一条同源的规矩:**工作区脏时 beta 版本名会带 `.dirty`**,那种包**不许发布**
(`publish-dev.sh` 明确拒绝,理由是「标识不诚实比不能发布更糟」)。
本地改了闸门脚本再编 beta 就会踩到 ⇒ 改闸门要先提交、再从干净树重编。

## 2026-10-21 第一次发 beta:beta-5dd8fb1 已上线

产物:`dev.hwkbd.ime.beta`,`0.7.11-beta.5dd8fb1`,7,835,928 字节,
sha256 `b36b561dff024645…`;release `beta-5dd8fb1`(prerelease,资产
`app-beta-5dd8fb1.apk`);站点 https://adam-ikari.github.io/gaia/ 的
**测试版按钮已出现**(文件名 `gaia-ime-beta-5dd8fb1.apk`,页面显示 `β 5dd8fb1`)。

走的顺序:`gate.sh --beta` → `publish-beta.sh` → `publish-site.sh`。
站点那步的测试版通道**第一次轮询就没对上**(Pages 还没更新,拿到 2268 字节),
第二次轮询逐字节一致 —— 这正是脚本重试的意义。

**这次踩到的两件事**:
① 直接跑 `gate.sh` 在第 3 步就挂(`check-version.sh(HEAD)没过`)——
   不是版本号错了,是那条检查问错了对象(beta 不占 versionCode、
   不打 v* tag,而 master 承袭已发布版本 ⇒「不撞旧 tag」对 HEAD 永远假);
   ⇒ 加了 `--beta` 模式,版本两步跳过**并打出来**。
② 改闸门脚本后直接编 beta ⇒ 版本名带 `.dirty` ⇒ 那个 hash 不代表实际字节,
   **这种包不许发布**。改闸门要先提交、再从干净树重编 —— 顺序反了就得重编一次。

## beta 渠道怎么发:一个脚本,不是「正式版那条链改几个参数」

`tools/release/publish-beta.sh`(2026-10-21 新增)。它和 `publish-dev.sh` 是**两个
独立应用**:dev 是 `dev.hwkbd.ime.dev`(不过 R8 的旧规则已改,现在也过),
beta 是 `dev.hwkbd.ime.beta`(与正式版并存)。

判据都是一次事故换来的,写在脚本头里:工作区必须干净(否则 `.dirty` 版本名
骗人)、资产名取自本地文件名的 basename(`#` 是 label 不是文件名)、
**先建空 release 再上传**(大包挂在 create 上会 HTTP 408 且全有或全无)、
prerelease 标记必须在 if/else **之外**并**回读确认**(放错过一次 ⇒ hijack
latest ⇒ 正式版按钮整个挂掉)、上传后**回读远端清单**核对大小、
**查询失败 ≠ 东西不在**(重试分开处理)。

beta 每次构建一个 release `beta-<hash>`(prerelease),资产 `app-beta-<hash>.apk`。
站点按**内容**挑渠道(找 prerelease 里含 `app-beta-*` 资产的那个),
身份从资产名的 hash 尾巴读 —— 不按命名约定挑,也不按时间挑
(dev 也是 prerelease,按时间挑会挑错)。

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

## 站点同步:执行者在本仓的 runner,判据还是脚本的退出码

发版链三步(`publish-beta.sh` → `publish-dev.sh` → `publish-site.sh`)由本仓的
`.github/workflows/publish-ime.yml` 触发,**站点部署是它的下游 job**(`site:` 以 `uses:`
调 `deploy-site.yml` —— reusable workflow 只能当 job 调,当 step 写不合法):

```bash
gh workflow run publish-ime.yml -f source_ref=master     # 2026-10-27 起不带 -R
```

2026-10-26 到 2026-10-27 之间这条链在**公开仓 gaia 的 runner** 上跑,理由已消失(私有仓
Actions 按分钟计费 ⇒ 转 public 后免费;跨仓只读 PAT 存在的唯一理由是「公开 runner 要检出
私有源码」)。**留下的理由是网络**:开发机 `gh release upload` 10MB 就在 ~101 秒超时,
一次发版要送 200MB 级资产。执行位置变了三次,判据一次没变 —— 逻辑只在脚本里有一份。

前提:**要发的那个 commit 先推到 `origin/master`** —— runner 检出的是远端,不是这个工作区。
`gate.sh --beta` 也在链里:执行位置一换,「发之前有人先跑一次闸门」就从结构变成了习惯,
所以它进链而不是靠人记得。签名钥匙不在树里,以 `SIGNING_KEYSTORE_BASE64` 等 4 个 secret
注入;`gate.sh` 的 `check_signing` 把「没签」判红 ⇒ 少配 secret 的症状是流水线失败,
不是发出一个装不上的包。

`sync-apk-to-site.sh` 与 `release.yml` 里那个 `publish-site` job 已在 2026-10-23 随正式版
渠道一起删除。那个 job 的形状值得记住:它需要 `WEB_RELEASE_TOKEN`,**没配就一直
skipped 且退出码 0** —— 流水线全绿而站点停在旧版本(v0.4.2 / v0.4.3 就是这么只存在于
私有仓的)。**判据是脚本的退出码,不是 CI 的状态。**

线上那份怎么算对:`publish-site.sh --verify` 把公开站点的 `/media/gaia-beta.apk`、
`/media/gaia-dev.apk` 与首页 HTML 同产物**逐字节**比,轮询到对为止(窗口约 20 分钟 ——
部署要先取回 ~220MB 再交 Pages)。包本身**不进 git**:两个包都超过 GitHub 单文件
100.00MB 的 push 硬上限。这条 `--verify` 自己踩过「打印了失败但退出 0」——
判据被吞掉的失败与没跑判据等价。细节与判据见 [[site-repo-topology]]。

**「下一步是什么」不能写成「已完成」**(2026-10-11 实测的假话):`publish-dev.sh` 那时打印
「已发,站点会在 CI 跑完后更新」,而它触发的那个 run completed success、线上仍是上一版字节。
发完 dev 之后站点**不会自己更新** —— 这一步只报下一步,判据只在 `publish-site.sh` 里。

验收不看文件名,也不只看大小:sha256 一律从**下载回来的字节**算(资产 API 只给 name/size),
再从 APK 里读 manifest 版本号 + 确认 `lib/arm64-v8a/librime_jni.so` 在包里。


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

- time: 2026-10-08T18:15:57
  kind: evidence
  summary: "2026-10-08 实测:gradlew.bat 不能直接透传含点的 -P 属性(-Pandroid.injected.build.abi=arm64-v8a 被拆成任务名,报 Task '.injected.build.abi=arm64-v8a' not found),要加引号。而它对「只编一个 ABI 省时间」这条正是致命的:buildType 里的 abiFilters 仍写着两个 ABI,只编一个 ⇒ packageDebug 打印 'There are no .so files available to package in the APK for x86_64' 并**跳过整个打包**,而构建报 BUILD SUCCESSFUL 且退出码 0。⇒ 本机只编一个 ABI 时必须同时收窄 abiFilters,否则是静默无产物。"
  source: 2026-10-08 assembleDebug
  affects: [release-flow]

- time: 2026-10-10T08:48:44
  kind: reversal
  summary: "publish-site.sh 的「盘上已有包」那条路**从没比过字节**,只跳过下载、拿盘上旧文件算 sha256(所以校验值自洽)。2026-10-10 发 v0.7.11 实测:web/docs/public/media/gaia.apk 还留着 10-08 那版(6931568 字节),旧字节被贴上 0.7.11 标签推上线,站点主按钮「测试版 0.7.11」下到的是上一版,整条链无一处报错。修法:复用前先比盘上字节数 == 远端该资产字节数,不等就当盘上没有、删掉重下(判据与下载那条路同源)。"
  source: "2026-10-10 发 v0.7.11 实测(站点 gaia.apk 曾是 6931568,修后 6939708)"
  affects: [release-flow, site-repo-topology]

- time: 2026-10-11T02:08:36
  kind: reversal
  summary: "2026-10-10 定下的『盘上已有包』复用判据(字节数 == 远端资产字节数)被推翻:字节数比不了内容。发 9582d85 时实测,b9b34d8 与 9582d85 两个包都是 10699199 字节(只差一个 shell 脚本,versionName 后缀 b9b34d8→9582d85 等长),盘上旧包通过大小检查被复用,页面显示 dev 9582d85 配的 sha256 却是上一版字节 —— 旧内容贴新标签上线。连 publish-site.sh 最后的『线上与本地产物逐字节一致』都是真的:那个本地产物就是从盘上旧包复制来的,判据链每一环自洽,但没有一环碰到 publish-dev.sh 真正上传的包。GitHub 资产 API 只有 name/size、不给 sha256 ⇒ 捷径删掉,每次都下载,从下载到的字节算 sha256;想用盘上的包只有显式 --skip-fetch。同一个形状的第三次:存在(10-08)→ 大小(10-10)→ 字节(10-11)。判据要比的是字节,而只有字节能比字节。"
  source: "2026-10-11 发布 dev 9582d85 实测 + 修复 d47559b"
  affects: [release-flow]

- time: 2026-10-11T03:17:09
  kind: evidence
  summary: "publish-site.sh 两处查询补重试(2026-10-11 实测,同一个形状当天第三、四次):①权威大小只查一次,gh api 抖动 → want=0 → [ want -gt 0 ] 恒假 → 大小校验被静默禁用,下载其实 5 次全部完整成功(10699195)却全被判截断,dev 渠道放弃上线、按钮从站点消失;②资产名查询 read < <(gh … || true),抖动 → asset 空 → 脚本断言『v0.7.11 上没有 app-release 资产』并致命退出,而资产好好在那儿。两次危害不同(一个静默丢渠道、一个中断发布),根因同一个:查询失败与结果为空在写法上不可区分,且下载有重试、查询没有。publish-dev.sh 的 remote_asset_size/dev_assets 早有正确形状(退出码区分 + 重试),publish-site.sh 的两处查询没照抄。判据:凡是『远端有什么』的查询,失败必须与空结果分流,且重试次数不能少于它所护卫的那个动作。"
  source: "2026-10-11 发 dev 77f6720 时 publish-site.sh 连挂两次"
  affects: [release-flow]

- time: 2026-10-16T00:38:56
  kind: decision
  summary: "2026-10-16 发 dev 4c26536(含站点)的四个发现:(1) gate.sh 在「master 承袭已发布版本、不发新正式 tag」状态必须传 --tag <当前 tag>——check-version.sh 无参模式假设 HEAD 即将打 tag,HEAD 上无 tag 指向时排不出自己,把 v0.7.11 的 versionCode 30 跟自己比必挂(本地无 tag=CI 状态不同,同一个坑的第三种形态)。(2) 本应用仓不该有 dev-latest tag:2026-10-06 误打的那个指向 587df89(树里没 app/build.gradle.kts),git show dev-latest:app/build.gradle.kts exit 128,在 set -uo pipefail 下把 check-version 整个脚本拖崩(128 伪装成 fail,容易被当成环境问题)。publish-dev.sh 的 dev-latest 是 gaia 仓的 release tag,与本应用仓的 git tag 无关。(3) STABLE_STATUS 默认 withdrawn 是过时的:它带 0.7.10 的 NOTE,但 cd04b5a(拼音部分消费后剩余拼音无候选)已进 v0.7.11 发布,主渠道当前 APK 已修——用默认值发会让站点对用户说谎(项目判据第一要义);本次切 official + 空 NOTE,以后默认值要随最新已发布版本更新。(4) rebase 合流(L6 分支 + 远端长按三选一)暴露 tryArmLongPress 不查 l6Open:面板开着敲字母被武装、DOWN 被吞、不组词、300ms 还出符号;已补并移植到三选一的 lp.arm 新架构。"
  source: "2026-10-16 发 dev 4c26536"
  affects: [release-flow]

- time: 2026-10-21T12:13:20
  kind: decision
  summary: "beta 不是版本发布:闸门加 --beta 模式(版本漂移/tag 对齐两步跳过且打出来,产物四步照跑);脏工作区编出的 .dirty beta 不许发布"
  source: "2026-10-21 发 beta 实操"
  affects: [release-flow]

- time: 2026-10-21T12:18:06
  kind: decision
  summary: "新增 tools/release/publish-beta.sh:beta 渠道独立脚本(工作区干净/资产名 basename/先建空 release/prerelease 回读/上传后回读清单/查询失败≠不存在),每次构建一个 beta-<hash> prerelease"
  source: "2026-10-21 发 beta 实操"
  affects: [release-flow]

- time: 2026-10-21T12:22:30
  kind: decision
  summary: "2026-10-21 首个 beta 已上线:beta-5dd8fb1(0.7.11-beta.5dd8fb1,7.8MB),站点测试版按钮出现;踩到 gate 版本检查问错对象(加 --beta 模式)与 .dirty 包不许发两条"
  source: "2026-10-21 发 beta 实跑"
  affects: [release-flow]

- time: 2026-10-23T12:04:22
  kind: reversal
  summary: "站点只保留 β + dev(2026-10-23):移除正式版主渠道 gaia.apk;β 成为主下载(fatal),dev 可选;删除 release.yml 的 publish-site job 与 sync-apk-to-site.sh;gaia deploy.yml 对产物做渠道白名单"
  source: "用户「统一网站 只有beta版本和dev版本 删除其他的版」"
  affects: [release-flow, site-repo-topology, site-analytics]

- time: 2026-10-26T11:31:37
  kind: decision
  summary: "β/dev/站点三步发布链今后在公开仓 gaia 的 publish-ime.yml 里执行(workflow_dispatch 手动触发),不在开发机上跑、也不占私有仓的计费分钟。执行位置和判据分离:判据仍在 tools/release/publish-*.sh 里(工作区必须干净、versionName == HEAD 短 hash、带 .dirty 的包拒发、先建空 release 再上传、上传后回读资产清单核对字节、下载侧比期望字节数)。gate.sh --beta 那一步仍在本地跑;要发的那一版必须先提交并推到 origin/master,否则 runner 检出不到。首次运行前需要在 gaia 配 secret IME_SOURCE_PAT(fine-grained,只读 hwkbd_ime 的 Contents)"
  source: 2026-10-26
  affects: [.github/workflows/publish-ime.yml]

- time: 2026-10-26T14:32:38
  kind: decision
  summary: "站点同步一节重写:三步链在 gaia runner 上跑(gate.sh --beta 进链),sync-apk-to-site.sh 与 publish-site job 已删,判据仍是脚本退出码;线上验收是逐字节轮询,窗口约 20 分钟;包不进 git(单文件 100.00MB 上限),由 deploy.yml 按 manifest 取。顺带修掉 compiled_truth 开头一个孤立的 ok 与丢了 ## 的标题"
  source: "2026-10-26 站点侧掉头(包不进 git)+ 修掉 truth 开头一个孤立 ok"
  affects: [release-flow]

- time: 2026-10-26T16:16:08
  kind: decision
  summary: "签名钥匙与口令挪出被跟踪文件:build.gradle.kts 只留一条 signingConfigs,取值顺序 local.properties → SIGNING_*(两处同一路径,不再分 project/ci 两套);四个 buildType 的选取改成 findByName(\"project\")?.let,没钥匙时什么都不设(debug 退回 AGP 默认密钥,陌生人 clone 也能装)。钥匙本体挪到仓外一份,并加进 .gitignore。不换证书:线上那份是 26bb762c…18dcd,换了老用户不能原地升级"
  source: "2026-10-26 仓库转公开的配套改造"
  affects: [release-flow]

- time: 2026-10-26T16:16:08
  kind: evidence
  summary: "挪出钥匙让「未签名」第一次成为一个走得通的状态:gradle 成功、包产出、sha256 算好、站点更新,只是装不上 —— 而全链路没有任何一步会红。实测:把 local.properties 移走后 signingReport 对 release/beta 报 Config: null。⇒ 闸门新增第 5c 步 check_signing(TOTAL 8→9,beta 6→7),拿 apksigner 读产物里的证书 SHA-256 与发布那把对账,一条判据同时抓「未签名」与「签错钥匙」。未签名分支用真包剥掉 META-INF 后模拟验证过"
  source: "2026-10-26 实测"
  affects: [release-flow]

- time: 2026-10-26T16:16:08
  kind: evidence
  summary: "开源前必须死在清洗里的东西,查全是三条:① hwkbd.keystore 全历史只有一个 blob(e182bd90,2580B,从未被改) ⇒ 剔一次即彻底消失,且它已随 origin/master 在 GitHub 上可达;② 口令 REDACTED-SIGNING-PASSWORD 在 40 个 app/build.gradle.kts 历史版本里 + 1 个不在可达图的本地残留 —— 不是「一个 commit」,git log -S 只给 1 是因为各版本命中数没变;③ 其它凭据零命中(PAT/私钥/AK/JWT 全无;VoiceSettings 的命中是 SharedPreferences 键名,SignerTest 用 AKIDEXAMPLE 占位)。教训:统计「有多少个版本含某串」不能用 git log -S,要按 blob 去重后逐个 cat-file"
  source: "2026-10-26 全历史清点(git cat-file --batch-all-objects --batch 扫 1788 个 blob)"
  affects: [release-flow]

- time: 2026-10-26T16:16:08
  kind: evidence
  summary: "两次假数据都来自 shell/zsh 差异而不是仓库:zsh 没有 mapfile ⇒ 「distinct blobs=0」;handle() 里拿 sha 去 startswith(\"blob\") 判类型 ⇒ 「blobs scanned: 0」。凡是「查出来是 0」的清点结果,先证伪工具再相信结论 —— 这次 0 与 HEAD 明确含该串直接矛盾,矛盾本身就是信号"
  source: "2026-10-26 踩坑"
  affects: [release-flow]

- time: 2026-10-27T08:59:19
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-27 monorepo 落地:发布链三步的执行者从公开仓 gaia 的 runner 收回本仓(gh workflow run publish-ime.yml,不带 -R)"
  affects: [release-flow]

- time: 2026-10-27T10:37:00
  kind: evidence
  summary: "本机构建站点时两条判据翻车,都不是代码问题,是同一个 dist/ 被两个进程写:① 一份完整构建报「产物里没有 _artifact-manifest.json」(退出码 1),同时跑的 --skip-fetch 把 dist/ 清掉了 —— 判据读固定路径,后一个进程换掉了前一个的产物,于是它报的是对方的错;② 之前那次「base 不对」也是并行所致,同一个原因。CI 里这条由 deploy-site.yml 的 concurrency: pages 挡着(排队不打断),本机没有那道闸 ⇒ 一次只跑一个,已在 web/README.md 写明。顺带确认两件事:GITHUB_REPOSITORY=adam-ikari/gaia 模拟换名后的仓,完整构建 11 条判据全过且产物前缀是 /gaia/(#24 未跑就先别动代码,base 由仓名派生这件事已经验过);dev 渠道 5 次下载失败是「本仓没有 dev-latest 这个 release」(gh release view dev-latest → release not found),命中「缺渠道不 fatal」那条分支,属 #23 未落地而不是 bug"
  source: "2026-10-27 本机并行跑两份 publish-site.sh 实测"
  affects: [release-flow, site-repo-topology]

- time: 2026-10-28T10:41:24
  kind: evidence
  summary: "「钥匙已移出跟踪」这个说法活了两个月是假的:b0d360c 标题写着移出,实际只改了 .gitignore + build.gradle.kts + gate.sh,**没跑 git rm --cached**。gitignore 对已在索引里的路径不生效,所以 hwkbd.keystore 到 HEAD 上仍被跟踪、blob e182bd90 仍随 origin/master 可达。教训按形态记:**凡「意图是脱管」的 commit 只改 ignore 规则而不改索引,等于没做**;判据因此不落在 commit 日志(日志里那两条都长得像做了)而落在 `git ls-files` —— 一个值就戳穿。顺带把 HEAD 工作树再扫一遍:除 brain/pages/release-flow.md 里那条引用口令的 timeline 外,凭据字面量零命中(AKIA/ghp_/github_pat_/PRIVATE KEY/sk-/slack 六种样式全空,storePassword 只剩变量名)。仓库内那份 keystore 是纯冗余(与仓库外权威副本字节相同),脱管不影响本机签名 —— local.properties 指向 /home/gem/.gaia-signing/"
  source: "2026-10-28 发布链审计:git ls-files -s hwkbd.keystore 仍返回 e182bd90;sha256 比对仓库内副本与 /home/gem/.gaia-signing/hwkbd.keystore 完全相同(4389fa65…)"
  affects: [release-flow]

- time: 2026-10-28T10:41:34
  kind: decision
  summary: "v0.7.12 走**完整发布链**,顺序定死:① 本地可逆那几刀(git rm --cached + 闸门红线 + 全链产物验证)→ ② 镜像备份仓外 → ③ git filter-repo 剔 hwkbd.keystore 这个 path 并做 replace-text 抹历史口令(会同时改掉 brain/pages/release-flow.md 那条引用口令的 timeline 与 40 个历史 build.gradle.kts)→ ④ **停下要一次明确 go** → force-push(不可逆,哈希全变)→ ⑤ **再停下要一次明确 go** → 转 public → ⑥ 用户配 4 个 SIGNING_* → ⑦ 才做 bump commit(versionCode 31 / versionName 0.7.12)并打 tag。"
  source: "2026-10-28 用户三选:完整发布链 / 打正式 tag v0.7.12 / SIGNING_* 由用户本人在仓库 Secrets 配置(助手不接触钥匙与口令)"
  affects: [release-flow]

- time: 2026-10-28T11:45:33
  kind: evidence
  summary: "重写历史之后往 GitHub 推,卡的是**单个 HTTPS 请求约 20 秒被 408 掐断**,而 payload 之所以是 11M 有一个隐蔽原因:**filter-repo 会把旧对象 repack 掉(reflog 也清空,实测 git reflog 行数=0),于是 send-pack 做 common 协商时本地根本找不到远端 advertise 的那些 commit**,不能把「远端已有的对象」排除掉 ⇒ 它把整条链的所有对象当新的发一遍。修法不是重试,是**先把旧对象捞回来**:`git fetch <清洗前的 mirror 备份> +refs/heads/*:refs/pre-rewrite/heads/* +refs/tags/*:refs/pre-rewrite/tags/*`,让旧 commit 在本地重新存在,协商才有 have 可用;然后**按 ref 分批推**(tag 按 creatordate 顺序 28 条 + 两条分支),每批 payload 自然小于 ceiling。实测:整批 11M 失败,单批最大 4.7M(master 剩余)5s 通过,3.1M(feat 分支)7s 通过 ⇒ 这台机器对 GitHub 的上行约 450KB/s,安全线上限估 8~9M。推完再删 refs/pre-rewrite/* + reflog expire + gc --prune=now,本地也不留钥匙 blob"
  source: "2026-10-28 实测:首次整批 push HTTP 408;把 delta 用 git bundle 量出来是 11M;分 28 tag + 2 分支逐条 push 后每条 1~7s 全过"
  affects: [release-flow]

- time: 2026-10-28T11:45:47
  kind: note
  summary: "给 brain CLI 传长文本时,**双引号里不许有反引号**:那串本该写「而落在 git ls-files」的 summary 里的反引号被 zsh 当命令替换执行了,于是 216 行文件清单被插进 brain/pages/release-flow.md 的一条 summary 中间(YAML 结构破坏),而我没读回来就 commit + push 了。特征不是报错,是**内容里出现你不认识的清单** ⇒ 每次 CLI 写完必须把那一行读回来看一眼。修法用 heredoc 或单引号传参;全 brain/ 扫同类污染(独立成行的裸文件名)零命中"
  source: "2026-10-28 自己踩的,当场修回"
  affects: [release-flow]

- time: 2026-10-28T11:50:30
  kind: evidence
  summary: "清洗 + force-push 之后**公开仓的 ref 和 clone 是干净的,但数据没被撤销**:GitHub 仍按 SHA 直接提供旧对象,匿名(不带 token)就能把 hwkbd.keystore 那 2580 字节整把私钥下载走。更要命的是**旧 SHA 根本不需要谁写在文本里** —— repos/*/events 的 PushEvent.head 里就躺着 ef9e513a(公开事件流,保留约 90 天),任何人都能自己枚举。⇒ 两条推论,以后别再走弯路:① 「把 blob SHA 从文档里删掉」不是缓解措施,只是心理安慰;② 「历史已清洗」与「凭据未泄露」是两件事,清洗的保证只有「新克隆拿不到」,**已经发出去的字节要算作公开**。 私钥的处置只剩两条硬路:找 GitHub Support 走 expire cached objects(让那些 SHA 变 404,清的是数据),或者换签名钥匙(清的是**授权**,代价是老用户不能原地升级)。对 APK 签名钥匙来说只有第二条真正闭环:拿到钥匙的人能签出「能被所有已装用户原地升级」的包,事件流能不能枚举 SHA 已经不重要了"
  source: "2026-10-28 转 public 后匿名验证:curl 无 token 取 git/blobs/e182bd90 得 HTTP 200、2580 字节,与 /home/gem/.gaia-signing/hwkbd.keystore cmp 完全相同;同时匿名 clone(23M)里没有该 blob、HEAD 文本里没有口令"
  affects: [release-flow]

- time: 2026-10-28T12:36:59
  kind: decision
  summary: "私钥仍可被匿名按 SHA 下载这件事,用户判定不影响发布计划:不换钥匙、不开 GitHub 工单、不再把「摘钥匙」当发版前置条件。据此 v0.7.12 直接走 CI 发版。硬约束不变:keystore 本体与口令仍不进公开仓、不进 agent 上下文,CI 只由用户自己在仓库 Secrets 里配 4 个 SIGNING_*;gate.sh 的「私钥不许被跟踪」红线继续拦仓库内副本。"
  source: "2026-10-28 用户裁决:签名不重要"
  affects: [release-flow]

- time: 2026-10-28T14:44:56
  kind: evidence
  summary: "发布这一跳的设备侧纪律:GitHub 令牌一律不进进程 argv。手写 curl -H \"Authorization: bearer $TOKEN\" 会把令牌写进 /proc/<pid>/cmdline,任何 pgrep -af / ps aux 都会原样打印出来,本次事故就是这样把 gho_ 前缀的 OAuth token 泄进了会话记录。要带鉴权头就用 curl -K - 从 stdin 喂配置,或者干脆用 gh release upload 让 gh 自己从 keyring 取。查进度的正确姿势:读 gh release view --json assets 的 size,不读进程表。"
  source: "2026-10-28 v0.7.12 资产上传重试时 pgrep -af 泄露令牌的事故"
  affects: [release-flow]

- time: 2026-10-28T14:47:55
  kind: evidence
  summary: "实测「切 legacy packaging 能把正式包压到多小」:v0.7.12 的 app-release.apk 103,060,178 字节里,大 STORED 条目合计 98,909,603。三个 .so 用 deflate(level 6/9 取小)后的尺寸:libonnxruntime.so 16,035,904→5,534,614(省 10,501,290)、libsherpa-onnx-jni.so 4,896,992→1,694,441(省 3,202,551)、librime_jni.so 3,750,960→1,290,741(省 2,460,219)。模型 zip 本身再 deflate 只省 24,805(已压过)。⇒ packaging.jniLibs.useLegacyPackaging=true 估计净省约 16.16MB,包落到 ~86.9MB。代价:extractNativeLibs=true,安装时解压、多占设备存储。判据来自直接读 APK 内条目,不是 AGP 文档的估计值。"
  source: "2026-10-28 本机 python zipfile+zlib 读 app-release.apk 逐条目实测"
  affects: [app/build.gradle.kts]

- time: 2026-10-28T14:54:46
  kind: evidence
  summary: "v0.7.12 正式包(103,060,178 字节)在本机传不上 GitHub release 资产,两种客户端各 6 次全部失败,失败形状与体积无关:gh release upload 6 次全是 HTTP 408 Upload body timed out due to inactivity,且死亡时间均匀到 68 秒;curl 流式 PUT(-T,鉴权走 stdin)6 次全是 curl(56) SSL_read unexpected eof,5 秒内被切。同一只包此前有一次 curl 跑到 190 秒/94MB(91%)才被切断,另两次死在 7s/3MB 与 31s/18MB。⇒ 结论:单请求能活的时长是随机的、没有固定字节上限,压小包(legacy packaging 省 16MB)只把需要的窗口缩短 16%,不改变失败形状。真正绕开这条路的只有让 runner 在 GitHub 自己的网络里上传(workflow_dispatch + SIGNING_* Secrets)。"
  source: "2026-10-28 本机 gh/curl 各 6 次重试的完整日志"
  affects: [release-flow]

- time: 2026-10-28T15:09:22
  kind: evidence
  summary: "PATCH repos/{owner}/{repo}/releases/{id} 必须同时带 tag_name:只发 target_commitish 会让 GitHub 把 draft 的 tag_name 换成 untagged-<slug> 占位符,之后 gh release view v0.7.12 直接 release not found、gh release upload 全失败,而 API 返回 200 不报错。改 draft 的目标 commit 时正确写法是 gh api -X PATCH …/releases/{id} -f tag_name=v0.7.12 -f target_commitish=<sha>,改完必须 gh release view <tag> 读回确认 tagName 还在。"
  source: "2026-10-28 自己踩的:把 draft 426334441 改指 e8f65ae 时漏带 tag_name"
  affects: [release-flow]

- time: 2026-10-28T15:13:14
  kind: evidence
  summary: "压包这条路已实测无效(2026-10-28):useLegacyPackaging=true 后 app-release.apk 从 103,060,178 降到 86,897,718(省 16,162,460,与逐条目 deflate 实测吻合;闸门 10 步全过,JNI 闸门在 compress_type=8 的 .so 上照样通过)。但同一时段重试仍全灭:gh 两次都是 68 秒整 HTTP 408,curl -T 两次 8–10 秒 SSL_read unexpected eof,连 limit-rate=400k 的限速 PUT 也在 10 秒内被切。⇒ 失败与体积无关、与客户端发多慢无关,是链路按时间随机断 TLS。剩下只有两个变量:跨时间稀疏重试(等一个能活 200 秒的窗口)与让 runner 在 GitHub 网络里上传(需 SIGNING_* Secrets)。"
  source: "2026-10-28 e8f65ae 构建 + /tmp/upload4.log /tmp/upload5.log 完整日志"
  affects: [release-flow, app/build.gradle.kts]
