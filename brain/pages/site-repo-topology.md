---
id: site-repo-topology
title: "站点与产物的仓库拓扑:源码在本仓,产物在公开仓"
category: decision
status: active
tags: [site, monorepo, release, deploy]
created: "2026-10-08T01:16:20"
updated: "2026-10-30T11:19:12"
---

<!-- compiled_truth -->
站点与应用在**同一个仓**(2026-10-27:网站仓 `adam-ikari/gaia` 删除,本仓改名为 `gaia`)。
站点源码在 `web/`,构建脚本 `tools/release/publish-site.sh`,Pages 由本仓的
`.github/workflows/deploy-site.yml` 部署。**产物不进任何 git** —— 脚本只出
`web/docs/.vitepress/dist/`(在 runner 工作区里),workflow 把它交给
`upload-pages-artifact` → `deploy-pages`。

URL 靠**仓名**保住:`https://adam-ikari.github.io/gaia/` 是从仓名推出来的,而
project site 改名**不留 redirect**(`github.io/<旧名>` 直接 404)。所以顺序是:老 gaia
改名腾出名字 → 本仓改名成 gaia → 以 `base=/gaia/` 重新部署并逐字节验 → 才删老仓与本地克隆。
中间有几分钟旧地址 404 的窗口。仓名派生出的三个值(`REPO` / `SITE_URL` / `SITE_BASE`)
一律从 `GITHUB_REPOSITORY` 或 `gh repo view` 解,**不写死** —— 写死的那三处字面量在改名
这一趟会静悄悄指向别的地方。

## 链路(全在本仓的 runner 上)

```
gh workflow run publish-ime.yml -f source_ref=<远端 ref>
  gate.sh --beta → publish-beta.sh → publish-dev.sh      # 包进本仓的 release 资产
  site: job 以 `uses:` 调 deploy-site.yml(复用 workflow 只能当 job,不能当 step)
    publish-site.sh      # 写 manifest → 构建 → 按 manifest 取包核对 size+sha256
    upload-pages-artifact → deploy-pages
    publish-site.sh --verify   # 线上那份与产物逐字节一致,轮询到对为止
```

判据全在脚本里,逻辑只有一份,搬动的只是执行它的那台机器。
前提:**要发的那个 commit 必须先推到 `origin/master`** —— runner 检出的是远端,不是工作区。

## 站点侧的形状:`dist/` 只在 runner 工作区

`dist/` 里是页面(几百 KB)加一份 `_artifact-manifest.json`,两个 APK 由 workflow 按
manifest 从**本仓的 release 资产**取回来、核对字节数与 sha256 后放进 `dist/media/`,
再交给 `upload-pages-artifact` —— Pages 的 artifact 路线不限单文件大小。

manifest 由 `publish-site.sh` 在**构建前**生成(页面注入的数字来自同一份数据):

```json
{"schema": 2, "repo": "<owner>/<仓名>", "source_commit": "<40 位 SHA>",
 "channels": {"beta": {"release": "beta-<hash>", "asset": "app-beta-<hash>.apk",
                       "target": "media/gaia-beta.apk", "version": "<hash>",
                       "size": <字节>, "sha256": "<64 位>"}}}
```

`schema` 1 的 `site_repo`/`source_repo` 两个字段随产物仓一起删掉 —— 只剩一个仓,
「去哪儿取包」这件事不再需要两个坐标。部署侧把 manifest 当**不可信输入**:target 走固定
白名单、sha256 走正则、size 走类型、release/asset 拒空白与 `/`,且只作为引号包住的参数
传命令 —— 一个字节都不进 shell 文本。

「产物进 git」原本免费送的两样东西,由这条链补回来:**线上那份可追溯到某个 commit**
(manifest 的 `source_commit`,部署日志第一行就打它)与**部署出去的字节 == 构建时被核对过
的字节**(同一个 sha256 在构建侧与部署侧各比一次)。代价是中间多了一跳,而那一跳的失败面
是新的:manifest 缺失/字段被换/资产被替换/Pages 部署被 skipped ⇒ 每一面都有判据。

## 为什么包不进 git:GitHub 对单个文件有 100.00MB 的 push 硬上限

**不是设计偏好变了,是那条从来没被允许过**:β 103,060,018 字节、dev 130,072,818 字节,
都超上限。实测拿一个 110MB 的**稀疏文件**(pack 里≈0 字节,几乎不需要上传)推上去,
**4.1 秒**被 pre-receive hook 拒(`GH001`)⇒ 与机器、与网络、与网速全都无关。以前那份
产物只有 7.8MB + 11.6MB,是「带离线语音模型的包」出现之前的事,所以这条路当时确实走得通。

## 为什么构建在 runner 上:开发机送不出大包(与上一条分开记)

`git push` 12MB 在 20.5 秒整失败、2MB 通过;`gh release upload` 3MB 通过、10MB 在 ~101 秒
超时 —— 单个 HTTP 请求持续 ~20-30 秒就被 408 掐断。一次发版要往 GitHub 送 200MB 级资产,
这台机器送不出去。2026-10-26 那趟把两者混成一条(「224MB 上不了这台机器的网」),那个说法
是错的:**上限只能靠「包不进 git」绕开,窗口只能靠换机器绕开**。

「私有仓 Actions 按分钟计费」曾是换到公开仓 gaia 的理由,源码转 public 之后它消失了 ——
留在的只有网络窗口这一条。

## 为什么是 monorepo:「站点是否与代码同步」原来是个跨仓的人工约定

原来站点源码在 gaia(公开),应用在这里(私有)。发一版要动两个地方:应用仓打 tag →
CI 出包 → `sync-apk-to-site.sh` 传包到 gaia → 推 gaia 触发 Pages。那条链上「推 gaia」
这一步**没有任何验证**,而且失败是静默的:`release.yml` 里等价的 `publish-site` job 要
`WEB_RELEASE_TOKEN`,那个 secret 从来没配 ⇒ job 一直 **skipped 且退出码 0**、流水线全绿,
而站点上的包停在旧版本。`v0.4.2`/`0.4.3`/`0.4.4`/`v0.4.8` 各漏过一次。

**结构上的成因**:能改站点的人不该看见应用源码,能改应用源码的人看不见站点。于是「站点是否
与代码同步」变成人工约定,而不是一个能编译出来的状态。⇒ 源码、构建、部署收进同一个仓,
判据从「我记得推了吗」变成**脚本的退出码**。2026-10-27 连产物仓也删掉:那一层的存在理由
(私有源码 + 公开 runner)已经不在了,留着只是多一个可以静悄悄不同步的地方。

## 三条不是风格问题的约束

1. **`publish-ime.yml` 只有 `workflow_dispatch`。** 这个 job 手里握着签名钥匙与发布凭据,
   加 `pull_request_target` / `issue_comment` / `repository_dispatch` / `push` 的后果不是
   「跑到一半失败」,是外人可以发起一次把包签好并发布出去的操作。
2. **跨仓凭据已删。** secret `IME_SOURCE_PAT`(fine-grained 只读)存在的唯一理由是
   「公开 runner 要检出私有源码」;源码与本仓同处一地之后它只是一个多余的凭据,也是每次
   交接要在浏览器里手配一次的坑。手填的 `source_ref` 先在服务端解析成**完整 commit** 再
   检出:既过一道字符白名单,也让每次发布对应一个确定的 SHA,而不是「master 当时大概指哪儿」。
3. **签名钥匙永不进树。** `hwkbd.keystore` 与口令以 `SIGNING_KEYSTORE_BASE64` 加三个口令
   secret 注入(仓 public ⇒ 历史一旦泄露收不回,而换钥匙的代价是老用户不能原地升级,
   Android 按签名判同包)。`gate.sh` 的 `check_signing` 把「没签」直接判红,所以少配一个
   secret 的症状是流水线失败,不是发出一个装不上的包。

## Pages 为什么保留 workflow,而不是纯从分支发布

中间试过纯从分支发布(连 workflow 一起删),能工作,但有两个坏处:构建失败只有推的人看得见、
而且只写在 Pages 设置页里,不在任何一次 run 上 ⇒「推了但站点没更新」与「推了且更新了」在
历史里长得一模一样;以及没有地方放产物自洽的检查。⇒ 保留 workflow。现在它真的构建
(`npm` 与源码都在本仓),外加按 manifest 取包、验自洽、交 Pages。取包用的是**本 job 自带的
`GITHUB_TOKEN`** —— 包就在本仓的 release 资产里,不需要任何 secret。
Pages 需配成 `build_type: workflow`,要求 workflow 有 `pages: write` + `id-token: write`。

## 判据链:指向不同的失效点

| 时机 | 判据 | 抓的是 |
| --- | --- | --- |
| 构建前 | 下回来的包与远端**字节数**相等(重试到对) | 下载静默截断 |
| 构建前 | 每个 APK 是完整 zip 且含 AndroidManifest + dex | 明知故犯地发一份装不上的包 |
| 构建后 | 产物里有 manifest 且与构建前那份 `cmp` 一致 | 「去哪儿取包」这件事丢了 |
| 构建后 | 静态资源里搜得到那份包字节的 sha256 | 数字没渲染出来,用户无从核对 |
| 构建后 | `dist/` 合计 < 900MB | 超出 Pages 已发布站点 ≤ 1GB 的余量 |
| 部署时 | manifest 字段合法 + 取到的字节 size/sha256 都对 | 资产被换过、截断、注入值与字节脱节 |
| 推之后 | 公开站点上那份与产物**逐字节**一致(轮询) | 「run 绿了而 Pages 被 skipped」「CDN 没刷新」 |

**最后一道覆盖前面全部**:它判的是线上那份,所以取包失败、部署被跳过、CDN 没刷新都会在这里
变成非零退出码。这条 `--verify` 自己踩过一次「报了错但退出 0」—— 判据被吞掉的失败与没跑判据
等价,现在写成 `verify_live || die`。**比字节不比 `versionCode`**:字节相同 ⇒ 版本号必然相同
(它就在 APK 里);只比版本号会漏掉「同一版本号配不同字节」—— 安卓会拒装升级。

## 「缺」与「坏」必须分开

- **缺测试版** = 主下载断开 ⇒ **fatal**:首页按钮直接指向 `/media/gaia-beta.apk`。
- **缺开发版** = 这个渠道还没上线 ⇒ **不 fatal**。照抄测试版的检查给它,「这次没有 dev 包」
  会让整个流程失败 ⇒ **测试版也更新不了、站点停在上一次**。一个可选功能能弄挂生产,最坏的形状。
- **坏**(存在但截断)⇒ **fatal,所有已上线的 APK 一视同仁**。不对称不是按渠道重要性分的,
  是按「这字节要不要发出去」分的:明知有坏字节却放它上线,用户点下载只会得到「软件包无效」
  —— 2026-10-06 那次报障的症状。而「缺」时页面上根本没有那个按钮(dev 的可用性由构建期 flag 决定)。

包改由部署侧取之后,「缺」的定义收紧过一次:**「manifest 里列了却没取到」不是「缺」,是「坏」
那一类** —— 按钮已经随构建渲染进页面了,少放包就是留一个点开 404 的按钮。所以部署侧对 manifest
列出的每一渠道都算 fatal,包括 dev。

## 两条渠道身份规则(别混)

β/dev 的身份都是 **release 资产名里的 hash**(`app-beta-a2f69d8.apk` → `a2f69d8`),不是从 tag
推导。资产名是「远端确实存着什么」,是这件事的权威。两条渠道的 **release 引用一律显式传**,
不留「默认取 latest」:dev 的资产在 `dev-latest` 里而它是 prerelease ⇒ latest 指向 `v*` ⇒
在 `v*` 里找 `app-dev-*` 找不到 ⇒ **dev 渠道静悄悄没上线**,而部署 success。`dev-latest` 必须
标 prerelease,否则会 hijack GitHub 的 latest。manifest 里写死 release + 资产名,正是为了让
部署侧**没有可猜的余地**。

## 站点源码里的不可省约束

- **下载链接的 `download` 属性不能省**,URL 固定 `/media/gaia-beta.apk`、版本进文件名。
  少了 `download`,VitePress 的 router 会把 `.apk` 当网页接管 ⇒ 渲染 404 页(真机报障,而
  直接访问同一 URL 却能下)。URL 固定是为了发版不用改 md —— 漏改的后果是 404 或永远下不到新版。
- **包必须由 Pages 自己发**,不能链 GitHub 的 release 下载地址:那条链要跳两次(github.com →
  release-assets),在 Titan 2 的浏览器上直接落到 Chrome 的「网页可能暂时无法连接」错误页
  (真机复现,同机 curl 是 200)。


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

- time: 2026-10-26T16:16:19
  kind: decision
  summary: "源码仓转 public 之后,发布链的执行者收回 hwkbd_ime 自己的 CI。理由是当初换机器的两个理由都消失了:私有仓 Actions 不免费(公开后免费)、408 送不出去大包(包已不进 git,manifest 那条链解决了)。连带作废:gaia 的 .github/workflows/publish-ime.yml 与 secret IME_SOURCE_PAT —— 那个只读 fine-grained PAT 存在的唯一理由是「公开 runner 要检出私有源码」,源码公开后它只是一个多余的凭据,也是每次交接都要在浏览器里手配一次的坑(它至今没被创建过)。gaia 保留 Pages 与 deploy.yml 的按 manifest 取包"
  source: "2026-10-26 用户定案(执行待落地)"
  affects: [site-repo-topology]

- time: 2026-10-27T08:58:11
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-27 用户定案「删除网站仓库,本仓库使用 monorepo 模式」+ 落地(新增本仓 deploy-site.yml / publish-ime.yml,删 site remote 与产物提交这一步)"
  affects: [site-repo-topology]

- time: 2026-10-27T08:58:31
  kind: reversal
  summary: "作废「产物推到公开仓 adam-ikari/gaia 的 dist/、Pages 从那个仓发布」以及本页面标题里的「产物在公开仓」。2026-10-27 定 monorepo:网站仓删除,本仓改名为 gaia 保住 https://adam-ikari.github.io/gaia/(project site 改名不留 redirect,旧地址直接 404,所以只能保名字)。产物改由本仓 deploy-site.yml 直接 upload-pages-artifact,不进任何 git;跨仓 secret IME_SOURCE_PAT 与 gaia 的 publish-ime.yml/deploy.yml 一并拆掉。构建仍必须在 runner:开发机 gh release upload 10MB 就在 ~101 秒超时"
  source: "2026-10-27 用户「删除网站仓库 本仓库使用monorepo模式」+ 选择「先落地再删」「仓库换名」"
  affects: [site-repo-topology, release-flow]

- time: 2026-10-27T10:28:01
  kind: evidence
  summary: "站点地址有了第二个来源,一次构建只认一个:仓库变量 PAGES_DOMAIN(经 deploy-site.yml 以 SITE_DOMAIN 传给构建步与 --verify 步)。没配=仓名地址+base=/<仓名>/;配了=域根+base=/。实测依据(2026-10-27,本机 DNS 在 sinkhole,curl 结果不可信 ⇒ 走 DoH 解析 + --resolve 打 Pages 边缘 185.199.108.153):① 项目页配自定义域后站点在**域根**服务,/<仓名>/ 是 404(样本 saizuo.is-a.dev↔Saizuo/Portfolio-Saizuo、win11.notaperson535.is-a.dev↔notAperson535/Win11-HTML);② 同一趟 <owner>.github.io/<仓名>/ 变成 **301** 指向该域,不是 404 ⇒ 挂域与换仓名是同一件事的两个选项,别都做;③ GitHub 明说仓库里的 CNAME 文件不会自动加删自定义域,只能设置/API ⇒ 脚本不产 CNAME 文件,只改 base 与验证地址;④ 免费子域服务 is-a.bot/is-a.dev 的 TXT 域验证是推荐非强制(he.is-a.bot 无 TXT 仍 200),但刚合并还没在 GitHub 侧配域的域名 TLS 直接连不上(docconvert.is-a.bot)。新增产物自检:资源前缀必须等于本次 SITE_BASE,否则 die —— base 分叉的症状是页面出、构建绿、用户满屏 404"
  source: "2026-10-27 用户「申请 is-a.bot 域名」→ 定为站点地址、PR 由用户自提;实测走 DoH+--resolve 绕本机 DNS sinkhole"
  affects: [site-repo-topology, release-flow]

- time: 2026-10-30T11:18:12
  kind: reversal
  summary: "拓扑收拢成**一个仓**,名字叫 gaia(2026-10-30)

原来两个仓的形状(源码在私有仓、产物与 Pages 在公开 gaia)整个作废:
· `adam-ikari/hwkbd_ime` 改名成 `adam-ikari/gaia`(源码、release 资产、Pages 同一个仓);
· 老的公开产物仓改名成 `adam-ikari/gaia-legacy`,待删(它的 Pages 现在挂在 /gaia-legacy/,还 200,里面是 2026-10-26 的 0.7.11 β/dev —— 删仓或关掉 Pages 之前,网上存在两份站,旧的没有下载价值);
· 站点地址 **没有变**:https://adam-ikari.github.io/gaia/ 从老仓的 Pages 交接到本仓的 Pages。GitHub 的 Pages 地址跟着仓名走、**不跟随改名留跳转**,所以改名那一瞬间 /gaia/ 服务的是本仓上一份按 base=/hwkbd_ime/ 构建的产物 —— 版本号对、静态资源全 404,是个无样式的站。必须紧接着用新仓名重跑一次 deploy-site.yml(publish-site.sh 的 SITE_URL 与 VitePress 的 base 都从**当前仓名**派生,没有字面量可改),这一步不是可选的收尾。

改名后核对过的三件事:manifest 里 repo=adam-ikari/gaia、source_commit=当时 master tip;/gaia/assets/* 路径前缀换过来了;两份包 content-length 仍是 86,897,758 与 96,260,036(β 87MB / dev 96MB 同值,sha256 未变)。旧路径 https://github.com/adam-ikari/hwkbd_ime 给 301 到新名 —— 但那是**平台的好心,不是承诺**:这个名字一旦被占走,301 就断,所以 GPL 在应用内承诺的取源码地址(strings.xml)必须写成 gaia,并随下一个包生效。"
  source: "user order 2026-10-30「改为项目仓库 gaia,github page 保持和之前的 url 不变」"
  affects: [site-repo-topology]

- time: 2026-10-30T11:19:12
  kind: evidence
  summary: "改名核对那条把 dev 包大小写错了:线上与本地的 app-dev-a77b6fb.apk 都是 **96,260,037** 字节,不是 96,260,036(β 的 86,897,758 与两个 sha256 不变)。顺带记下 `brain append-timeline --kind` 只吃 decision / evidence / reversal / note,写 correction 会被拒 —— 而被拒的那条正好是「更正」,所以更正归到 evidence 里。数字抄错和抄漏一样会进 durable record,「写后读回」不是形式动作。"
  source: "2026-10-30 自查(写后读回抓到的)"
  affects: [site-repo-topology]
