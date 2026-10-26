---
id: site-repo-topology
title: "站点与产物的仓库拓扑:源码在本仓,产物在公开仓"
category: decision
status: active
tags: [site, monorepo, release, deploy]
created: "2026-10-08T01:16:20"
updated: "2026-10-26T15:26:06"
---

<!-- compiled_truth -->
站点源码与构建在本仓库 `web/`,构建脚本是 `tools/release/publish-site.sh`。它把**页面
产物**推到公开仓 `adam-ikari/gaia` 的 `dist/`,Pages 从那个仓发布。**公开仓里没有应用
源码** —— 只有产物,加两条构建产物的 workflow(2026-10-26 起)。

站点从 2026-10-23 起只发两条渠道:**测试版 β(主下载)** 与 **开发版 dev(可选)**。
正式版渠道(旧的 `/media/gaia.apk`)已移除;`release.yml` 的 `publish-site` job
与 `sync-apk-to-site.sh` 一并删除 —— 它们只服务正式版进站点,留着就是一条会失败的死链路。

```
hwkbd_ime/web/                        站点源码(VitePress: md / config / 组件 / 样式)
hwkbd_ime/tools/release/publish-site.sh   取包核对 + 写 manifest + 构建 + 推产物 + 验线上字节
gaia/dist/                            页面产物 + _artifact-manifest.json,**没有 APK**(进不了 git)
gaia/.github/workflows/deploy.yml     按 manifest 从本仓 release 资产取包 → 验自洽 → 交 Pages
gaia/.github/workflows/publish-ime.yml   在公开仓的 runner 上执行发布链(不构建站点本身)
```

## 站点侧的形状(2026-10-26):页面进 git,包不进 git

`dist/` 里只有页面本身(几百 KB)加一份 `_artifact-manifest.json`。两个 APK 由
`deploy.yml` 在部署前按 manifest 从**本仓的 release 资产**取回来,核对字节数与 sha256
之后放进 `dist/media/`,再交给 `upload-pages-artifact` —— Pages 的 artifact 路线不限单文件大小。

manifest 由 `publish-site.sh` 在**构建前**生成(页面注入的数字来自同一份数据):

```json
{"schema": 1, "site_repo": "adam-ikari/gaia", "source_repo": "adam-ikari/hwkbd_ime",
 "source_commit": "<40 位 SHA>",
 "channels": {"beta": {"release": "beta-<hash>", "asset": "app-beta-<hash>.apk",
                       "target": "media/gaia-beta.apk", "version": "<hash>",
                       "size": <字节>, "sha256": "<64 位>"}}}
```

部署侧把 manifest 当**不可信输入**:target 走固定白名单、sha256 走正则、size 走类型、
release/asset 拒空白与 `/`,且只作为引号包住的参数传命令 —— 一个字节都不进 shell 文本。

## 为什么反过来:GitHub 对单个文件有 100.00MB 的 push 硬上限

这一节推翻上面「产物进 git」那条。**不是设计偏好变了,是那条从来没被允许过**:
β 103,060,018 字节、dev 130,072,818 字节,都超上限。实测拿一个 110MB 的**稀疏文件**
(pack 里≈0 字节,几乎不需要上传)推上去,**4.1 秒**被 pre-receive hook 拒(`GH001`)
⇒ 与机器、与网络、与网速全都无关。以前那份产物只有 7.8MB + 11.6MB,是「带离线语音模型的
包」出现之前的事,所以这条路当时确实走得通。

换机器(`publish-ime.yml`)解决的是**私有仓 Actions 按分钟计费**和 runner 在自己网络上
不必受本机那条 ~20-30 秒请求体窗口的约束;它**不解决**上限 —— 上限只能靠「包不进 git」绕开。
两件事分开记,因为一开始把两者混成了一条(「224MB 上不了这台机器的网」),那个说法是错的。

「产物进 git」原本免费送的两样东西,在新形状里由这条补回:
**线上那份可追溯到某个 commit**(manifest 的 `source_commit`,部署日志第一行就打它;
`dist/` 那个 commit 仍然在 git 里)与**部署出去的字节 == 构建时被核对过的字节**
(同一个 sha256 在构建侧与部署侧各比一次)。代价是中间多了一跳(部署时才取包),
而那一跳的失败面是新的:manifest 缺失/字段被换/资产被替换/Pages 部署被 skipped
⇒ 每一面都有判据,见下面判据链。

## 为什么是 monorepo:「站点是否与代码同步」原来是个跨仓的人工约定

原来站点源码在 gaia(公开),应用在这里(私有)。发一版要动两个地方:应用仓打 tag →
CI 出包 → `sync-apk-to-site.sh` 传包到 gaia → 推 gaia 触发 Pages。
那条链在 2026-10-23 随正式版渠道一起删除了,但它的教训仍然是这个仓的由来。

那条链上「推 gaia」这一步**没有任何验证**,而且失败是静默的:
`release.yml` 里等价的 `publish-site` job 要 `WEB_RELEASE_TOKEN`,
那个 secret 从来没配 ⇒ job 一直 **skipped 且退出码 0**、流水线全绿,
而站点上的包停在旧版本。`v0.4.2`/`0.4.3`/`0.4.4`/`v0.4.8` 各漏过一次
(见 `docs/versioning.md` 发版链路第五步)。

**结构上的成因**:能改站点的人不该看见应用源码,能改应用源码的人看不见站点。
于是「站点是否与代码同步」变成了一个人工约定,而不是一个能编译出来的状态。
⇒ 源码与构建收进本仓库一处,判据从「我记得推了吗」变成**脚本的退出码**。

## 发布链的执行者在公开仓 gaia 的 runner(2026-10-26)

⇒ `gaia/.github/workflows/publish-ime.yml`:在公开仓的**免费** runner 上检出私有源码,
调**同一批** `tools/release/publish-*.sh`。判据全在脚本里,逻辑只有一份,
搬走的只是执行它的那台机器。私有仓的 Actions 按分钟计费,这条链一分钟都不占。
前提:**要发的那个 commit 必须先推到 `origin/master`** —— runner 检出的是远端,不是工作区。

三条不是风格问题的约束:

1. **只有 `workflow_dispatch`。** 公开仓里任何人都能造出一个触发事件,而这个 job 手里握着
   能读私有源码的凭据。加 `pull_request_target` / `issue_comment` / `repository_dispatch` / `push`
   的后果不是「跑到一半失败」,是外人可以发起一次把私有源码打进公开日志的操作。
   dispatch 需要本仓 write ⇒ 发起方只有维护者本人。
2. **凭据不对称。** 读私有源码 = secret `IME_SOURCE_PAT`(fine-grained,只授权 `hwkbd_ime` 一个仓、
   Contents **read-only**,配 `persist-credentials: false`);写 release 资产与 `main` = 本 job 自带的
   `GITHUB_TOKEN`。手填的 `source_ref` 先在服务端解析成**完整 commit** 再检出:既过一道字符白名单,
   也让每次发布对应一个确定的 SHA,而不是「master 当时大概指哪儿」。
3. **签名钥匙绝不进公开仓。** `hwkbd.keystore` 和它的口令本来就提交在私有源码仓里,所以这个 workflow
   不需要任何签名 secret —— 但它往 `main` 上写的只可能是 manifest 与页面产物。钥匙一旦进了公开历史就收不回来,
   而换钥匙的代价是老用户不能原地升级(Android 按签名判同包)。

公开那份 workflow 会对外透露私有仓的名字、脚本路径与 NDK 版本。闭源项目的元数据少露一点更好
(见 [[project-licensing]]),但换不掉:不写清这些,下一个改它的人会把免费分钟和凭据范围弄错。
副作用:`deploy.yml` 监听 `push: [main]` ⇒ 推产物这一步顺带把 Pages 触发掉了,不用再手动 dispatch。

## Pages 为什么保留 workflow,而不是纯从分支发布

中间试过纯从分支发布(连 workflow 一起删),能工作,但有两个坏处:

1. **构建失败只有推的人看得见,而且看的地方很远** —— 只写在 Pages 设置页里,
   不在任何一次 workflow run 上 ⇒「推了但站点没更新」与「推了且更新了」
   在 git 历史里长得一模一样。
2. **没有地方放产物自洽的检查。**

⇒ 保留 workflow。它**不跑 npm**(这个仓里没有源码可构建),它做三件事:
按 manifest 取包、验产物自洽、把产物交给 Pages。取包用的还是**本仓 job 自带的
`GITHUB_TOKEN`** —— 包就在本仓的 release 资产里,不需要任何 secret;读私有源码那份
凭据只属于 `publish-ime.yml`。

## 判据链:指向不同的失效点

| 时机 | 判据 | 抓的是 |
| --- | --- | --- |
| 构建前 | 下回来的包与远端**字节数**相等(重试到对) | 下载静默截断 |
| 构建后 | 产物里有 manifest 且与构建前那份 `cmp` 一致 | 「去哪儿取包」这件事丢了 |
| 构建后 | `dist/` 里没有 >90MB 的文件 | 推不上去(GH001),症状离原因隔一整条链 |
| 构建后 | 静态资源里搜得到那份包字节的 sha256 | 数字没渲染出来,用户无从核对 |
| 部署时 | manifest 字段合法 + 取到的字节 size/sha256 都对 | 资产被换过、截断、注入值与字节脱节 |
| 部署时 | 每个 APK 是完整 zip 且含 manifest+dex | 明知故犯地发一份装不上的包 |
| 推之后 | 公开站点上那份与本地产物**逐字节**一致(轮询) | 「推了但站点没更新」「run 绿而 Pages 被 skipped」 |

**最后一道覆盖前面全部**:它判的是线上那份,所以取包失败、部署被跳过、CDN 没刷新
都会在这里变成非零退出码。前六道只是让它少花 20 分钟才明白。

**比字节不比 `versionCode`**:字节相同 ⇒ 版本号必然相同(它就在 APK 里);
只比版本号会漏掉「同一版本号配不同字节」—— 安卓会拒装升级。

## 「缺」与「坏」必须分开

- **缺测试版** = 主下载断开 ⇒ **fatal**:首页按钮直接指向 `/media/gaia-beta.apk`。
- **缺开发版** = 这个渠道还没上线 ⇒ **不 fatal**。
  照抄测试版的检查给它的话,「这次没有 dev 包」会让整个流程失败 ⇒
  **测试版也更新不了、站点停在上一次**。一个可选功能能弄挂生产,最坏的形状。
- **坏**(存在但截断)⇒ **fatal,所有已上线的 APK 一视同仁**。
  这里的不对称不是按渠道重要性分的,是按「这字节要不要发出去」分的:
  明知有坏字节要发布却放它上线,用户点下载只会得到「软件包无效」
  ——正是 2026-10-06 那次报障的症状。而「缺」时页面上根本没有那个按钮
  (dev 的可用性由构建期 flag 决定),不存在「点到一个坏包」这种可能。

包改由部署侧取之后,「缺」的定义要收紧一次:**「manifest 里列了却没取到」不是「缺」,
是「坏」那一类** —— 按钮已经随构建渲染进页面了,少放包就是留一个点开 404 的按钮。
所以 `deploy.yml` 对 manifest 列出的每一渠道都算 fatal,包括 dev。

## 两条渠道身份规则(别混)

β/dev 的身份都是 **release 资产名里的 hash**(`app-beta-a2f69d8.apk` → `a2f69d8`),
不是从 tag 推导。资产名是「远端确实存着什么」,是这件事的权威。

两条渠道的 **release 引用一律显式传**,不留「默认取 latest」:
dev 的资产在 `dev-latest` 里而它是 prerelease ⇒ latest 指向 `v*` ⇒
在 `v*` 里找 `app-dev-*` 找不到 ⇒ **dev 渠道静悄悄没上线**,而部署 success。
`dev-latest` 必须标 prerelease,否则会 hijack GitHub 的 latest;
站点不再消费 latest,但 latest 指向开发版仍是错误的发布状态(2026-10-06 实测过)。
manifest 里写死 release + 资产名,正是为了让部署侧**没有可猜的余地**。

## 站点源码里的不可省约束

- **下载链接的 `download` 属性不能省**,URL 固定 `/media/gaia-beta.apk`、版本进文件名。
  少了 `download`,VitePress 的 router 会把 `.apk` 当网页接管 ⇒ 渲染 404 页
  (真机报障,而直接访问同一 URL 却能下)。URL 固定是为了发版不用改 md ——
  漏改的后果是 404 或永远下不到新版。
- **包必须由 Pages 自己发**,不能链 GitHub 的 release 下载地址:
  那条链要跳两次(github.com → release-assets),在 Titan 2 的浏览器上
  直接落到 Chrome 的「网页可能暂时无法连接」错误页(真机复现,同机 curl 是 200)。
- **产物渠道白名单**:`dist/media/` 里只允许 `gaia-beta.apk` 与 `gaia-dev.apk`。
  别的 APK(包括旧的 `gaia.apk`)会让 deploy.yml 直接失败 ——
  防止「页面上删了按钮,文件却仍然能下载」。取包前会先把 `dist/media/*.apk` 清空:
  渠道下线而旧字节仍挂在同一个 URL 上,正是这条白名单想抓、光靠白名单抓不到的那种错。

## 这个仓里还有哪些别处

- 文案规则不在这里,在 `adamblog` 的写作 skill(见 [[site-copy]])。
- 页面上凡是描述交互的地方,**先读代码再写**(见 [[site-copy]] 里那次纠正)。
- 命名与不写什么的清单见 [[site-voice]]。
- 版式与视口的实测判据见 [[site-mobile]];视觉语言见 [[site-style]]。


## Timeline

- time: 2026-10-08T01:16:20
  kind: decision
  summary: "Created this page: 站点与产物的仓库拓扑:源码在本仓,产物在公开仓"
  source: "2026-10-08 monorepo 化"
  affects: [site-repo-topology]

- time: 2026-10-08T01:16:46
  kind: decision
  summary: "建立:站点源码与构建在本仓 web/,产物进公开仓 gaia 的 dist/,Pages 保留一个不构建的验产物 workflow"
  source: "2026-10-08 monorepo 化,用户定方向"
  affects: [site-repo-topology]

- time: 2026-10-08T01:16:46
  kind: reversal
  summary: "Pages 部署方式试过纯从分支直接发布,又改回保留 workflow:从分支发布时构建失败只写在 Pages 设置页、不在任何一次 run 上,「推了但没更新」与「推了且更新了」在 git 历史里长得一样;且没有地方放产物自洽的检查"
  source: 2026-10-08
  affects: [site-repo-topology, release-flow]

- time: 2026-10-23T12:04:16
  kind: decision
  summary: "站点只发 β + dev(2026-10-23):移除正式版主渠道与同步链路;β 是主下载(fatal),dev 可选;产物渠道白名单"
  source: "2026-10-23 用户「统一网站 只有beta版本和dev版本 删除其他的版」"
  affects: [site-repo-topology]

- time: 2026-10-26T11:14:13
  kind: evidence
  summary: "2026-10-26 源码侧的上传墙解除:模型出仓库后,未推送的 9 个 commit 里 112.5MB 是死掉的 vosk/sherpa blob、源码只 1.05MB。在临时克隆里跑 filter-branch 丢这两个路径,回活仓只做一次 git update-ref CAS(备份 tag backup/pre-rewrite-20261026),af244e7..2ad3515 推送成功。**产物侧仍未解除**:gaia/dist/media 现在那份是 7.8MB+11.6MB,下次发版要塞进 git 的是 β 99MB + dev 124MB ≈ 224MB,而这台机器单个 git 请求超过 ~20 秒就被 408 掐断(实测 8MB 挂、2MB 过;release 资产 10MB 在 ~101s 超时)。私有源仓的 Actions 按分钟计费,公开 gaia 的 deploy.yml 免费但按设计不构建、不取外物"
  affects: [tools/release/publish-site.sh, tools/release/publish-beta.sh, tools/release/publish-dev.sh]

- time: 2026-10-26T11:17:21
  kind: evidence
  summary: "2026-10-26 复测修正上一条的形状判断:单个 12MB 随机数据的 git push 在 20.5 秒整被 HTTP 408 掐断(未留下远端分支)。所以墙不是「体积 >8MB」而是「单个 HTTP 请求持续 >~20 秒」,本机吞吐现在达不到推 12MB 所需的速度 ⇒ 224MB 走本机正式排除。gaia 上现存那个 11.6MB blob 是网络状态不同时的产物,不能当作今天的依据"
  affects: [site-repo-topology]

- time: 2026-10-26T11:31:27
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-26 发布链执行者移到公开仓 runner"
  affects: [site-repo-topology]

- time: 2026-10-26T11:31:27
  kind: decision
  summary: "发布链的执行者移到公开仓 gaia:新增 .github/workflows/publish-ime.yml(仅 workflow_dispatch),在免费 runner 上用只读 fine-grained PAT 检出私有源码、调同一批 tools/release/publish-*.sh、把 β/dev/站点产物提交进 main。理由是 224MB 从开发机送不出去(单个 HTTP 请求 ~20s 被 408 掐断),不是分钟钱的问题。安全形状三条:只有 workflow_dispatch(公开仓任何人可造触发事件,而这个 job 握有读私有源码的凭据);凭据不对称(读源码 IME_SOURCE_PAT read-only 单仓 + persist-credentials:false,写产物用本 job 的 GITHUB_TOKEN);签名钥匙 hwkbd.keystore 永不进公开仓。source_ref 先解析成完整 commit 再检出。副作用:推 main 顺带触发 deploy.yml"
  source: "2026-10-26 用户「转到公开仓库」→ 澄清为「只把构建搬到 gaia」"
  affects: [.github/workflows/publish-ime.yml, tools/release/publish-beta.sh, tools/release/publish-dev.sh, tools/release/publish-site.sh]

- time: 2026-10-26T11:58:57
  kind: evidence
  summary: "「产物进 git」这条路已被 GitHub 的服务端规则封死,与机器和网络无关:用一个 110MB 的稀疏文件测试(pack 约 0 字节,网络瞬间完成),远端 4.1 秒后回 GH001 —— File big.bin is 110.00 MB; this exceeds GitHub's file size limit of 100.00 MB。**单位是十进制 MB**,所以现在的 beta APK 103,060,018 B = 103.06MB 与 dev 130,072,818 B = 130.07MB **两个都超限**;上一条 evidence 里我按 MiB 估成「beta 98.3MiB 能过」是错的。⇒ publish-site.sh 把 APK 提交进 gaia/dist/media/ 在任何机器上都会 pre-receive declined,包括刚建的 runner 链:它会在最后一步挂。GitHub Pages 侧的限制只有「已发布站点 ≤ 1GB」「源仓建议 ≤ 1GB」,没有单文件 100MB 上限 —— 也就是说同一个包**可以由 workflow 作为 Pages artifact 发布**,只是不能进 git。另一测:release 资产(单资产可到 2GB)的墙是服务端等 body 的窗口 —— 1MB 请求 3.6 秒通过(201),5MB 请求 40.5 秒后 408 Upload body timed out due to inactivity;git 增量 push 的边际吞吐约 0.45MB/s(4MB 块各约 9.5 秒)。分块续传的「先 POST 建立 asset(声明总长、空 body)」这一步在这条链上每次都 408,没打通 ⇒ 「本地能传 100MB 级资产」是未证实,不是已证伪"
  source: "2026-10-26 实测(探针分支/探针 release 均已删除)"
  affects: [tools/release/publish-site.sh, gaia/.github/workflows/deploy.yml]

- time: 2026-10-26T14:30:22
  kind: decision
  summary: "公开仓 gaia 的 main 只承载页面加一份 manifest;两个 APK 由 deploy.yml 在部署时按 manifest 从本仓 release 资产取回。旧结论「产物进 git」被平台单文件上限封死,已作废"
  source: "2026-10-26 站点侧掉头:APK 不进 git,deploy.yml 按 manifest 取包"
  affects: [site-repo-topology]

- time: 2026-10-26T14:30:41
  kind: reversal
  summary: "「产物进 git(含 APK)」作废:GitHub 对单文件有 100.00MB 的 push 硬上限(beta 103,060,018 / dev 130,072,818 字节),110MB 稀疏文件 4.1 秒被 GH001 拒 ⇒ 与机器无关。新形状:gaia 的 dist/ 只有页面加 _artifact-manifest.json,两个包由 deploy.yml 在部署时按 manifest 从本仓 release 资产取回、核对 size 与 sha256 后交给 upload-pages-artifact"
  source: "2026-10-26 用户问「本地真的无法上传吗」→ 实测推翻原前提"
  affects: [site-repo-topology, tools/release/publish-site.sh, gaia/.github/workflows/deploy.yml]

- time: 2026-10-26T14:30:41
  kind: evidence
  summary: "把三件事分开:①平台硬上限 = 单文件 100.00MB(push),与网络无关;②本机的墙 = 单个 HTTP 请求体 ~20-30 秒不完成就 408(12MB git push 在 20.5 秒失败、1MB 分块 3.6 秒通过、5MB 分块 40.5 秒 408 Upload body timed out due to inactivity);③git 增量 push 边际吞吐约 0.45MB/s。所以「224MB 从这台机器送不出去」不是必须换形状的理由:换机器解决计费与窗口,进不了 git 才是上限本身封的。gh release download 无续传,本网络会 unexpected EOF 留下截断文件"
  source: "2026-10-26 本机探测(uploads.github.com 分块 PATCH、git push 定时)"
  affects: [site-repo-topology]

- time: 2026-10-26T14:31:05
  kind: decision
  summary: "部署侧取包的四条判据:①manifest 列了哪个渠道,那个渠道的包取不到就是 fatal(按钮已随构建渲染,少放包=线上一个点开 404 的按钮),这与「缺 dev 不 fatal」不冲突 —— 那条讲的「缺」是页面没渲染;②先比 size(截断)再比 sha256(同名资产被换过),任一步红就没有产物,站点停在上一次自洽的那份;③manifest 字段一律当不可信输入:target 固定白名单、sha 正则、size 类型、release/asset 拒空白与斜杠,值只作引号参数传 gh;④每次下载包 timeout 600,卡住的下载既不红也不绿而默认 job 超时要 6 小时。取包前先清 dist/media/*.apk:渠道下线而旧字节仍挂在同一 URL,是白名单抓不到的那种错。保留「没有 manifest 就照旧部署」的迁移分支,因为推这个文件本身就是一次 push"
  source: "2026-10-26 gaia commit f68787f(deploy.yml 按 manifest 取包)"
  affects: [gaia/.github/workflows/deploy.yml, tools/release/publish-site.sh]

- time: 2026-10-26T14:31:05
  kind: evidence
  summary: "新部署步在推之前跑过 18 个分支:happy(两渠道落地)、下载失败三次、截断、同长度换字节、资产名匹配到两个、文件改名、target 越界/非白名单、sha 非十六进制、size 字符串或负数、release 含空格或斜杠、version 含空白、schema=2、site_repo 不匹配、缺 beta、source_commit 非 SHA —— 失败路径全部 rc=1 且 dist/media 为空(不放任何东西上去)。自己写的第一版有个真缺陷:python 的诊断走 stdout 被命令替换吸进变量,失败时只剩一句「解析 manifest 失败」,看不出哪个字段不对 ⇒ 改成数据进文件、诊断进 stderr。真 run 只验到迁移分支(旧形状照旧部署、deploy-pages success、线上首页与两个 apk URL 全 200);manifest 分支在真 runner 上取 100MB 级资产、以及 upload-pages-artifact 承载约 222MB 仍未跑过"
  source: "2026-10-26 本地仿真(stub gh + 真旧产物)+ 真 run 40279394840"
  affects: [gaia/.github/workflows/deploy.yml]

- time: 2026-10-26T15:26:06
  kind: evidence
  summary: "站点侧唯一未验的物理假设实测通过:upload-pages-artifact 能承载 233MB。dist/media 里放 103,060,018 + 130,072,818 = 233,132,836 字节的 /dev/urandom 数据(不是 truncate —— 稀疏文件是 0 字节而 artifact 流式压缩上传,全零会被压没,那会验出假绿),抽头 8MB gzip 后 ≈100% 当场自证压不动。存下来的 Final size = 233,204,557 字节(差 71,721 是压缩容器),上传 6.4 秒,整个 job 16 秒 ⇒ runner 侧吞吐约 36MB/s,与本机那条 ~20-30 秒的窗口无关。没有 deploy-pages 那一步,线上一个字节未动 ⇒ 发布链不再有已知未知,只剩第一次真跑的工程风险"
  source: "2026-10-26 gaia run 40285493319(一次性探针 workflow,已从 main 删除、artifact 已删)"
  affects: [gaia/.github/workflows/deploy.yml, tools/release/publish-site.sh]
