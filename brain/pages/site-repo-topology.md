---
id: site-repo-topology
title: "站点与产物的仓库拓扑:源码在本仓,产物在公开仓"
category: decision
status: active
tags: [site, monorepo, release, deploy]
created: "2026-10-08T01:16:20"
updated: "2026-10-08T01:16:46"
---

<!-- compiled_truth -->
站点源码与构建在本仓库 `web/`,构建产物推到公开仓 `adam-ikari/gaia` 的 `dist/`,
Pages 从那个仓发布。**公开仓里没有源码,只有产物。**

```
hwkbd_ime/web/       站点源码(VitePress: md / config / 组件 / 样式)
hwkbd_ime/tools/release/publish-site.sh   取包 + 构建 + 推产物 + 验
gaia/dist/           构建产物(含 APK),进 git
gaia/.github/workflows/deploy.yml   验产物自洽 + 交 Pages,不构建
```

## 为什么是 monorepo:「站点是否与代码同步」原来是个跨仓的人工约定

原来站点源码在 gaia(公开),应用在这里(私有)。发一版要动两个地方:应用仓打 tag →
CI 出包 → `sync-apk-to-site.sh` 传包到 gaia → 推 gaia 触发 Pages。

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

- **缺**主渠道 = 发布流程断了 ⇒ **fatal**。
- **缺**可选渠道(beta/dev) = 这个渠道还没上线 ⇒ **不 fatal**。
  照抄主渠道的检查给它的话,「还没发过 beta」会让整个流程失败 ⇒
  **主渠道也更新不了、站点停在上一次**。一个可选功能能弄挂生产,最坏的形状。
- **坏**(存在但截断)⇒ **fatal,三条渠道一视同仁**。
  这里的不对称不是按渠道重要性分的,是按「这字节要不要发出去」分的:
  明知有坏字节要发布却放它上线,用户点开发版下载只会得到「软件包无效」
  ——正是 2026-10-06 那次报障的症状。而「缺」时页面上根本没有那个按钮
  (可用性由构建期 flag 决定),不存在「点到一个坏包」这种可能。

## 两条渠道身份规则(别混)

- **主渠道**:身份 = tag(`v0.7.10` → `0.7.10`)。资产名是 `app-release.apk`,
  **没有 hash 尾巴可剥** —— 硬剥会得到字面量 `app-release`。
- **beta/dev**:身份 = **release 资产名里的 hash**(`app-dev-c271426.apk` → `c271426`),
  不是从 tag 推导。资产名是「远端确实存着什么」,是这件事的权威。

三条渠道的 **release 引用一律显式传**,不留「默认取 latest」:
dev 的资产在 `dev-latest` 里而它是 prerelease ⇒ latest 指向 `v*` ⇒
在 `v*` 里找 `app-dev-*` 找不到 ⇒ **dev 渠道静悄悄没上线**,而部署 success。
`dev-latest` 必须标 prerelease,否则它会 hijack GitHub 的 latest ⇒
主渠道去它里面找 `app-release.apk` 找不到 ⇒ **主渠道按钮整个挂掉**(2026-10-06 实测)。

## 站点源码里的两条不可省约束

- **下载链接的 `download` 属性不能省**,URL 固定 `/media/gaia.apk`、版本进文件名。
  少了 `download`,VitePress 的 router 会把 `.apk` 当网页接管 ⇒ 渲染 404 页
  (真机报障,而直接访问同一 URL 却能下)。URL 固定是为了发版不用改 md ——
  漏改的后果是 404 或永远下不到新版。
- **包必须由 Pages 自己发**,不能链 GitHub 的 release 下载地址:
  那条链要跳两次(github.com → release-assets),在 Titan 2 的浏览器上
  直接落到 Chrome 的「网页可能暂时无法连接」错误页(真机复现,同机 curl 是 200)。

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
