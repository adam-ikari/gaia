---
id: release-flow
title: "发布流程:版本号/tag/CI 与已知 lint 镜像 flake"
category: project
status: active
tags: [release, ci, tooling]
created: "2026-09-30T09:50:05"
updated: "2026-10-23T12:04:22"
---

<!-- compiled_truth -->
ok
测试版(beta)不是版本发布:闸门要有专门模式

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
