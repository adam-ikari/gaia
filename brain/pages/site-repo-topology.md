---
id: site-repo-topology
title: "站点与产物的仓库拓扑:源码在本仓,产物在公开仓"
category: decision
status: active
tags: [site, monorepo, release, deploy]
created: "2026-10-08T01:16:20"
updated: "2026-10-26T11:31:27"
---

<!-- compiled_truth -->
站点源码与构建在本仓库 `web/`,构建产物推到公开仓 `adam-ikari/gaia` 的 `dist/`,
Pages 从那个仓发布。**公开仓里没有应用源码** —— 只有产物,加一个
**构建产物的 workflow**(2026-10-26 起,见下面「发布链的执行者」)。

站点从 2026-10-23 起只发两条渠道:**测试版 β(主下载)** 与 **开发版 dev(可选)**。
正式版渠道(旧的 `/media/gaia.apk`)已移除;`release.yml` 的 `publish-site` job
与 `sync-apk-to-site.sh` 一并删除 —— 它们只服务正式版进站点,留着就是一条会失败的死链路。

```
hwkbd_ime/web/       站点源码(VitePress: md / config / 组件 / 样式)
hwkbd_ime/tools/release/publish-site.sh   取 β/dev + 构建 + 推产物 + 验
gaia/dist/           构建产物(含两个 APK),进 git
gaia/.github/workflows/deploy.yml   验产物自洽 + 交 Pages,不构建
gaia/.github/workflows/publish-ime.yml   在公开仓的 runner 上执行发布链(不构建站点本身)
```

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

## 产物进 git:宁可仓库大,也不要「谁构建了、构建成功没有」成为可能静默失败的事实

原来 gaia 仓自己跑 `npm ci` + `npm run build`,而 `.gitignore` 里明确写着
「不把 APK 提交进 git,会让仓库历史每次发版胖 6.2MB」。

这次**刻意反过来了**,代价是 gaia 仓随发版单调变大(每版 +7~28MB)。
理由:走流水线时,线上那一份对应哪次构建、那次构建有没有失败,
只存在于某次 CI run 的状态里 —— 而那个状态可以 skipped 且退出码 0。
产物进 git 之后,线上那一份**可追溯到某个 commit**。

## 发布链的执行者在公开仓:224MB 上不了这台机器的网(2026-10-26)

发一版要塞进 GitHub 的是 β(99MB)+ dev(124MB)+ 站点产物,合计约 224MB。开发机的墙
不是体积阈值,是**单个 HTTP 请求持续 ~20 秒就被 408 掐断**:实测 12MB 的 `git push`
在 20.5 秒整失败、2MB 通过;`gh release upload` 3MB 通过、10MB 在 ~101 秒超时。
git 不续传、也不能把那一个请求切小 ⇒ 这 224MB 从这台机器送不出去。
源码那一侧同一堵墙已经用「模型出仓库」拆掉了(见 [[voice-input]]);产物这一侧拆不掉,只能换执行机器。

⇒ `gaia/.github/workflows/publish-ime.yml`:在公开仓的**免费** runner 上检出私有源码,
调**同一批** `tools/release/publish-*.sh`,把产物提交进 `main`。判据全在脚本里,
逻辑只有一份,搬走的只是执行它的那台机器。私有仓的 Actions 按分钟计费,这条链一分钟都不占。

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
   不需要任何签名 secret —— 但它往 `main` 上写的只可能是 APK 与站点产物。钥匙一旦进了公开历史就收不回来,
   而换钥匙的代价是老用户不能原地升级(Android 按签名判同包)。

公开那份 workflow 会对外透露私有仓的名字、脚本路径与 NDK 版本。闭源项目的元数据少露一点更好
(见 [[project-licensing]]),但换不掉:不写清这些,下一个改它的人会把免费分钟和凭据范围弄错。
副作用:`deploy.yml` 监听 `push: [main]` ⇒ 推产物这一步顺带把 Pages 触发掉了,不用再手动 dispatch。

## Pages 为什么保留 workflow,而不是纯从分支发布

中间试过纯从分支发布(连 workflow 一起删),能工作,但有两个坏处:

1. **构建失败只有推的人看得见,而且看的地方很远** —— 只写在 Pages 设置页里,
   不在任何一次 workflow run 上 ⇒「推了但站点没更新」与「推了且站点更新了」
   在 git 历史里长得一模一样。
2. **没有地方放产物自洽的检查。**

⇒ 保留 workflow,但它**不跑 npm**:产物是提交进来的事实,不是待办。
它只做两件事 —— 验产物自洽、把产物交给 Pages。它拿的是**本仓 GITHUB_TOKEN**,
不需要任何 secret(产物是提交进来的,不需要凭据去别处取)。

## 判据链:三道,指向不同的失效点

| 时机 | 判据 | 抓的是 |
| --- | --- | --- |
| 推之前 | 下回来的包与远端**字节数**相等 | 下载静默截断 |
| 推之前 | 公开站点上那份与本地产物**逐字节**一致 | 「推了但站点没更新」 |
| CI 里 | 每个 APK 是完整 zip 且含 manifest+dex;页面 sha256 对得上字节 | 产物本身自相矛盾 |

**三道都要,因为它们判的不是同一件事**。第一道判「下载时没被截断」,
第二道判「线上是不是这次的」,第三道判「即将上线的那份本身是不是能装」——
第三道还覆盖「推产物这步自己出错」这种本地没察觉的情形。

**比字节不比 `versionCode`**:字节相同 ⇒ 版本号必然相同(它就在 APK 里);
只比版本号会漏掉「同一版本号配不同字节」—— 安卓会拒装升级。

## 「缺」与「坏」必须分开

三处(取包、构建、CI 验)都是同一条规则:

- **缺测试版** = 主下载断开 ⇒ **fatal**:首页按钮直接指向 `/media/gaia-beta.apk`。
- **缺开发版** = 这个渠道还没上线 ⇒ **不 fatal**。
  照抄测试版的检查给它的话,「这次没有 dev 包」会让整个流程失败 ⇒
  **测试版也更新不了、站点停在上一次**。一个可选功能能弄挂生产,最坏的形状。
- **坏**(存在但截断)⇒ **fatal,所有已上线的 APK 一视同仁**。
  这里的不对称不是按渠道重要性分的,是按「这字节要不要发出去」分的:
  明知有坏字节要发布却放它上线,用户点下载只会得到「软件包无效」
  ——正是 2026-10-06 那次报障的症状。而「缺」时页面上根本没有那个按钮
  (dev 的可用性由构建期 flag 决定),不存在「点到一个坏包」这种可能。

## 两条渠道身份规则(别混)

β/dev 的身份都是 **release 资产名里的 hash**(`app-beta-a2f69d8.apk` → `a2f69d8`),
不是从 tag 推导。资产名是「远端确实存着什么」,是这件事的权威。

两条渠道的 **release 引用一律显式传**,不留「默认取 latest」:
dev 的资产在 `dev-latest` 里而它是 prerelease ⇒ latest 指向 `v*` ⇒
在 `v*` 里找 `app-dev-*` 找不到 ⇒ **dev 渠道静悄悄没上线**,而部署 success。
`dev-latest` 必须标 prerelease,否则会 hijack GitHub 的 latest;
站点不再消费 latest,但 latest 指向开发版仍是错误的发布状态(2026-10-06 实测过)。

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
  防止「页面上删了按钮,文件却仍然能下载」。

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
