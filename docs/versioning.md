# 版本号管理规范

## 唯一来源

版本号只有一个来源：`app/build.gradle.kts` 的 `defaultConfig`。

| 字段 | 含义 | 取值 |
| --- | --- | --- |
| `versionName` | 用户看得见的版本（设置页、APK 详情） | 三段数字，如 `0.5.2` |
| `versionCode` | Android 的升级判据 | 单调递增整数，与发布次数一一对应 |

`v*` git tag 是**发布动作**，不是版本号的来源。tag 只负责触发 CI、给 Release 标题、
以及让站点页面知道该显示哪个版本号。

## 为什么三个地方必须对齐

| 地方 | 取什么 | 谁填 |
| --- | --- | --- |
| `versionName` | 应用自己报的版本 | 改代码时顺手改 |
| `versionCode` | Android 拒装同版/降级 | 改代码时顺手改 |
| `v*` tag | CI 触发条件、Release 标题 | 打 tag 时写 |

三者没有任何天然约束，于是有两种**静默**失败：

1. **Release 说 0.9.9，应用报 0.5.0。** Release 标题与资产名来自 tag，应用版本号来自
   `versionName`。不一致时用户从 Release 下到「0.9.9」、装完应用里写着 0.5.0 —— 不报错，只是骗人。
   （站点从 2026-10-23 起只发 β 与 dev，不再显示正式版号。）
2. **忘了加 `versionCode` ⇒ 装不上。** Android 要求升级时 `versionCode` **严格大于**已装版本，
   否则 PackageInstaller 拒装（`INSTALL_FAILED_VERSION_DOWNGRADE`）。站点是直接发 APK 文件
   （不走商店），所以这一步只在用户安装时才暴露 —— 那时人已经在用旧包了。

两条都只能靠发版前的一次检查拦住。

## 规矩

1. **`versionName` 必须是三段数字。** tag 才有统一格式可对。
2. **tag 必须等于 `v` + 当前 `versionName`。** 例：`versionName = "0.5.2"` ⇒ tag `v0.5.2`。
3. **`versionCode` 严格大于所有已存在 tag 所带的值。** 比「最近一个 tag」更严：任何一个旧 tag 的
   `versionCode` 都不能 ≥ 当前值。这条天然容忍空洞（历史上 `v0.3.0` 没发出去但烧掉了 3 号）。
4. **不发版就不改号。** 中途改动会让下一个版本号跳号，无害但让历史读起来费解。
5. **版本号只在发版那一个 commit 里改，且那个 commit 必须是 master 的 tip。**
   于是 `master 的版本号 == 最新已发布版本的版本号`，恒等式成立。

> 第 5 条由 `tools/release/gate.sh` 检查，判据见 `docs/branching.md` 第三节。
> 它**覆盖**下面「发布链路」第 1 步的「与改动一起提交」——那句是「顺手改」的许可，
> 而 2026-10-05 的版本号漂移正是从那里来的（见下面「一次真实的漂移」）。

## 检查

发版前跑一次（CI 里也会跑）：

```bash
tools/release/check-version.sh            # 用 HEAD 当前版本
tools/release/check-version.sh v0.5.2     # 或指定即将打的 tag
```

它验三件事：格式、tag 与 `versionName` 一致、`versionCode` 未与任何旧 tag 冲突。

`release.yml` 在「Unit tests」之前跑它 —— 版本号错了就不该浪费时间跑测试。

### 「本地过、CI 必挂」的一个坑（2026-10-03）

接进 CI 的第一次就挂在这上面，报 `versionCode 19 已被 tag v0.7.0 用过` ——
**自己跟自己比**。

原因：CI 里跑的时候 **tag 已经存在**（tag 的 push 就是工作流的触发条件），
所以待发布的那个 tag 必然出现在 `git tag` 输出里，而它带的 `versionCode`
恰好等于当前值，`>=` 判定成立。本地跑时看不到这个问题，因为那时新 tag 还没打。

**两个入口的初始状态不同**：本地是「tag 未打」，CI 是「tag 已存在」。
所以脚本现在会**排除待发布的那一个 tag**；不给 tag 参数时还会自动取
「指向 HEAD 的那个 tag」来排除（对应「本地 `git tag -a` 打完还没推」的状态）。

教训：**同一个检查在两个环境跑通，不代表它对**。得问一句「两边的初始状态一样吗」。

## 测试版(beta)不是版本发布

beta 的身份是 **git hash**(`0.7.11-beta.<hash>`),不占 versionCode、不打 `v*` tag。
所以下面这条链里的「版本号 ↔ tag 对齐」那一步对它不成立 ——
master 的版本号本来就该等于已发布版本(那是「承袭」的定义),
于是「不撞旧 tag」对 master HEAD 永远是假的(那个号已被已发布的 tag 用掉了)。

⇒ 闸门有专门的模式:

```bash
tools/release/gate.sh --beta    # 单测 / 构建 / R8 / JNI 照跑,版本两步跳过并打出来
```

跳过哪一步必须**打出来**:闸门最怕的是「看起来跑过了」。

工作区脏时 beta 的版本名会带 `.dirty`(那个 hash 不代表实际字节)——
**这种包不许发布**:标识不诚实比不能发布更糟。

发布测试版走自己的脚本(不是正式版那条链):

```bash
tools/release/gate.sh --beta          # 产物侧闸门(单测/构建/R8/JNI)—— 本地跑
# 下面三步在**本仓的 runner** 上跑,不在开发机上:
#   gh workflow run publish-ime.yml -f source_ref=master
#   它按序执行 publish-beta.sh → publish-dev.sh → publish-site.sh,再以 job 调 deploy-site.yml
tools/release/publish-beta.sh         # 编 + 发到本仓的 beta-<hash> prerelease
tools/release/publish-site.sh         # 构建站点 + 组装产物(交给 deploy-site.yml 上传 Pages)
```

**为什么那三步换机器**(2026-10-26。这一条的理由当天纠正过一次,记在这里是因为
错过的那版本听起来同样有道理):一次发版要塞进 GitHub 的是 β 103,060,018 +
dev 130,072,818 字节,再加站点产物。

- 开发机的单个 HTTP 请求持续 ~20 秒就被 408 掐断(实测 12MB 的 `git push` 在 20.5 秒整
  失败、2MB 通过;`gh release upload` 3MB 通过、10MB 在 ~101 秒超时)。**这条才是
  「谁来编」的理由**:发版要往 GitHub 送的是 200MB 级的 release 资产,这台机器送不出去。
- 另一条封死的是 GitHub 对**单文件 100.00MB** 的 push 硬上限(pre-receive hook 报
  `GH001`),与机器、与网络都无关:本地拿一个 110MB 的**稀疏文件**(pack 里≈0 字节,
  几乎不需要上传)推上去,4.1 秒被拒。所以包**根本不进 git**(见下面「站点侧的形状」)
  —— 这条是数据形状问题,换机器解决不了它。
- 于是换的是「谁来编、谁来传」。runner 在 GitHub 自己的网络上把包传进**本仓的 release
  资产**、把产物交给 Pages,既不过那道 ~30 秒的窗口,也不受 push 上限的约束。

执行位置变了,判据没变 —— workflow 只是调同一批脚本,逻辑只有一份(详见本仓的
`.github/workflows/publish-ime.yml`)。
前提:**要发的那个 commit 必须先推到 `origin/master`**,runner 检出的是远端,不是这个工作区。

beta 的 release 是**每次构建一个** `beta-<hash>`(prerelease),
站点按**内容**挑渠道:找 prerelease 里**含 `app-beta-*` 资产**的那个,
身份从资产名的 hash 尾巴读 —— 不是从 tag 推导,也不是「最新 prerelease」
(dev 也是 prerelease,按时间挑会挑错)。

## 发布链路

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
#    —— 这一步**本身就是发版 commit**,不是夹在别的改动里(见上面规矩第 5 条)
# 2. 本地校验(闸门,不是单个脚本)
tools/release/gate.sh --release    # 七步,含单测 / 构建 / JNI 闸门 / E2E 引用
# 3. 打 tag 并推 —— 这一推才真的发版
git tag -a v0.5.2 -m "v0.5.2" && git push origin v0.5.2
# 4. 等流水线：单测 → assembleRelease/Debug → GitHub Release
```

正式版**不再同步到公开站点**(2026-10-23):站点只发测试版 β 与开发版 dev,
各自走 `publish-beta.sh` / `publish-dev.sh`,再用 `publish-site.sh` 推产物。
判据是**公开站点上那份与本地产物逐字节一致**。

站点源码在本仓库 `web/`,构建脚本是这里的 `publish-site.sh`,Pages 由本仓的
`.github/workflows/deploy-site.yml` 部署(2026-10-27 起收回本仓;之前产物要推到一个
叫 `gaia` 的独立公开仓,那层没了)。

### 站点侧的形状

`publish-site.sh` 的构建输出 `web/docs/.vitepress/dist/` 里**只有页面 + 一份
`_artifact-manifest.json`**(几百 KB)加上 runner 现取的两个 APK。两个包**不在 git 里**,
由 workflow 按 manifest 从**本仓的 release 资产**取回来,放进 `dist/media/` 再交给
`upload-pages-artifact`(Pages 走 artifact 路线不限单文件大小)。

manifest 长这样(`publish-site.sh` 构建前生成,页面注入的那些数字来自同一份数据):

```json
{"schema": 2, "repo": "<owner>/<仓名>", "source_commit": "<40 位 SHA>",
 "channels": {"beta": {"release": "beta-<hash>", "asset": "app-beta-<hash>.apk",
                       "target": "media/gaia-beta.apk", "version": "<hash>",
                       "size": <字节>, "sha256": "<64 位>"}}}
```

部署侧对每一渠道核对**字节数**(截断)与 **sha256**(同名资产被换过),再落文件;
任一条不对就让 run 红,而 `upload-pages-artifact` 在它后面 ⇒ 拿不到成功的 step
就没有产物 ⇒ 站点停在上一次那一份自洽的东西上。取包失败也算错:manifest 里列了某渠道,
就意味着页面**已经渲染了那个按钮**,少放一个包线上就是个点开 404 的按钮。

「产物进 git」原本免费送的两样东西,由这条链补回来:
**线上那份能追溯到某个 commit**(manifest 的 `source_commit`,部署日志第一行就打它),
以及**部署出去的字节 == 构建时被核对过的字节**(同一个 sha256 在两处比)。

### 站点挂在哪个地址:`base` 与验证地址必须来自同一个值

仓库变量 `PAGES_DOMAIN` 一处决定,`publish-site.sh` 据此派生 `SITE_BASE`(产物的资源前缀)
与 `SITE_URL`(`--verify` 去哪儿比字节):

| `PAGES_DOMAIN` | 站点地址 | `base` |
| --- | --- | --- |
| 没配 | `https://<owner>.github.io/<仓名>/` | `/<仓名>/` |
| 配了 | `https://<域>/` | `/` |

为什么必须有这个开关,两条都是 2026-10-27 实测的:**项目页配了自定义域之后站点在域根服务**
(拿两个真实样本量的:`/` 是 200 而 `/<仓名>/` 是 404),而同一个
`<owner>.github.io/<仓名>/` 从此变成 **301** 指向那个域 —— 是 301,不是 404。
一份产物只能服务一边(base 是构建期常量),所以这个变量决定「这份产物是给哪个地址的」;
构建步与 `--verify` 步读到不同值,判据就变成「按 A 渲染、去 B 验字节」——看着绿而什么都没验。
产物自检里那条 `base_prefix` 检查抓的就是这个分叉:base 没进到构建里时页面照样能出、构建
照样全绿,而样式与下载链接满屏 404。

域本身不在这个脚本里生效:GitHub 明说「仓库里的 CNAME 文件不会自动加/删自定义域」,
必须在仓库设置或 API 里配。这条与「换仓名」是**同一件事的两个选项** —— 换名保住的是
`github.io/<仓名>`,挂域把地址搬到 `https://<域>/` 并由 301 兜住旧地址,两个都做没意义。

## 一次真实的漂移(2026-10-05,写这条规则的原因)

现场:`3eac2f1` —— 一个补埋点、修「在飞的动画没人管」、加 E2E 的功能 commit ——
顺手把 `versionCode` 提到 29、`versionName` 提到 0.7.10。

于是 master 停在 **0.7.10 整整 14 个 commit**,而最新已发布的是 v0.7.9,
**没有对应 tag**。

代价不是历史难看,是两句具体的话都定位不到东西:

- 用户报 bug 说「我用的 0.7.10」—— 这句话指向一个**从不存在的产物**。
- 从 master 编出的包自称 0.7.10,装到真机上设置页也写 0.7.10。

而 `check-version.sh` **分不清**「master 正在开发 0.7.10」和「0.7.10 即将发布」——
它验的是「号有没有撞」和「tag 对不对」,没有「master 该自称什么」这一问。

修法:版本号退回 28/0.7.9,把 bump 留给发版 commit。
判据进 `tools/release/gate.sh`(比**值**,不是比「谁碰过这个文件」)。

> 顺带一个实现上的坑:第一版判据查的是「`$top..HEAD` 里哪个 commit 碰过
> `build.gradle.kts`」,结果把「顺手修了个 typo」也算成一次 bump。
> 正确做法是解出 HEAD 与最高 tag 各自的版本号**比值**。

### 第 6 步:验(2026-10-05 写,2026-10-08 换了脚本)

原先是「打印一句提醒人去查」——**提醒不算验证**,所以它被漏过。
`publish-site.sh` 自己做完,加上 `deploy-site.yml` 那半场:

| 验什么 | 在哪验 | 判据 |
| --- | --- | --- |
| 公开站点上的 `media/gaia-beta.apk` 是这次的包 | publish-site `--verify` | 与产物里那份**逐字节**一致(轮询到对为止) |
| 公开站点的首页是这次的产物 | publish-site `--verify` | 与产物的 `index.html` 逐字节一致 |
| 页面上给用户的 sha256 对得上 | 两边 | 产物里的静态资源里搜得到那份包字节的 sha256;部署侧再对一次 |
| manifest 在产物里、且没人动过它 | publish-site | 与构建前写的那份 `cmp` 一致 |
| 产物部署得上去 | publish-site | `dist/` 合计 < 900MB(Pages 上限是已发布站点 ≤ 1GB,与 git 的 100MB 单文件上限是两回事) |
| 站点没有指向 404 的下载链接 | deploy-site.yml | manifest 列了哪个渠道,那个渠道的包就必须取到并放进 `dist/` |
| 部署出去的字节 == 构建时核对过的字节 | deploy-site.yml | 先比 `size`(截断)再比 `sha256`(被换过) |

关键区分:**release 资产、部署时组装的 artifact、公开站点上是三个东西**(2026-10-27 前
还有第四个「git 里那份产物」,那层随 monorepo 一起删掉了)。
上传成功就能过 `releases/latest/download/…` 那道 200;artifact 里那份只证明「交给 Pages 了」;
Pages 只在 `deploy-pages` 跑完之后才刷新 —— 所以「200」和「run 绿了」都验不到线上那份。
中间这一跳发生在**部署时**:所以判据里必须有「按 manifest 取回来的字节对不对」。

**比字节而不是比 `versionCode`**:字节相同 ⇒ 版本号必然相同(它就在 APK 里);
只比版本号会漏掉「同一版本号配不同字节」—— 安卓会拒装升级,
且同一下载链接在不同时刻给出不同的包。

## 什么时候升 minor

`0.x` 时代 **minor 位就是 breaking 的信号**。判据不是「改动大不大」，而是
**用户手上的东西会不会失效**：

| 判据 | 升什么 |
| --- | --- |
| 手感/动画引擎换了、档位口径变了、状态语义变了 | **minor**（0.5.x → 0.6.0） |
| 只加功能、只修 bug、只改文案 | patch（0.6.0 → 0.6.1） |

反例（别这么升）：为了「看起来改动多」而升 minor —— 那会让 minor 失去信号，
下次真 breaking 时用户不再当回事。

> 实例：0.5.1 → **0.6.0**。这一版把手势动画的引擎整个换掉了
> （`ValueAnimator` 固定时长 → 官方 `SpringAnimation`），并重设了三档灵敏度的口径。
> 手感与旧版不可直接比较，属于 minor。

`versionCode` **只增不减，与 minor/patch 无关**，它是发布次数的计数器。

## 历史

| tag | versionCode | versionName |
| --- | --- | --- |
| v0.4.9 | 13 | 0.4.9 |
| v0.5.0 | 14 | 0.5.0 |
| v0.5.1 | 15 | 0.5.1 |
| v0.6.0 | 17 | 0.6.0 |

两个空洞都是有意的：

- `versionCode = 3` 被 `v0.3.0` 烧掉了，那个版本没发出去；
- `versionCode = 16` 属于 `v0.5.2` —— 它发布后数分钟内被撤回重发为 `v0.6.0`
  （上面那条 minor 判据是补写的，漏判了一次）。
  号空着不回收：撤回操作万一失败，就会出现两个 APK 顶着同一个 `versionCode`，
  而站点只按 latest 给一个，用户装到哪个全看时序。

`v0.3.0` 没有 tag，所以 `versionCode` 与版本号**没有**换算关系。
