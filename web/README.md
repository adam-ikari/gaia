# 站点

本站的源码。Pages 从**本仓**发布（`.github/workflows/deploy-site.yml`）——
2026-10-27 之前产物要推到一个叫 `gaia` 的公开仓，那层现在没了：源码、构建、部署
在同一个仓、同一次 run 里。

改站点在这里改，发布是 dispatch 那个 workflow（本地构建仍有用，见下）。

## 本地开发

```bash
cd web
npm install
npm run dev      # http://localhost:5173/gaia/ —— 那是 config.mts 的兜底 base,不是产物用的
npm run build    # 产出 web/docs/.vitepress/dist
npm run preview  # 预览构建结果
```

本地构建拿不到 Beta版 的完整版本号/hash 与 dev 的可用性（它们在构建期注入），
所以按钮上不显示版本号、下载文件名退回 `gaia-ime.apk`、dev 按钮不渲染。这是设计如此，不是坏了。

## 结构

```
docs/
  .vitepress/
    config.mts            站点配置（base 由 publish-site.sh 按当前仓名注入 SITE_BASE）
    download.ts           两条渠道（β / dev）的链接与文件名 —— 唯一来源
    theme/index.ts        扩展默认主题，注册全局组件
    theme/layouts/Home.vue 自定义首页布局（内容全部来自 index.md）
    theme/components/     Downloads（β / dev 两个按钮）/ VerifyLine
    styles/               apple / home / responsive 三个样式表
  index.md                首页（hero + 各 band）
  public/.nojekyll        跳过 Pages 的 Jekyll 处理
```

`docs/index.md` 里的 `::: band` 容器不带颜色，底色由 `config.mts` 按顺序自动轮换
（三个底色、周期 3，相邻两段必然不同色）。加一段不用重排配色。

## 改文案之前

规则不在本仓库，在 `adamblog` 项目的写作 skill 里 —— 见 brain 的
[[site-copy]]。产品名只有一种写法、不提包名/闭源/模仿对象这类清单见 [[site-voice]]。

页面上凡是描述交互的地方，**先读代码再写**。手势的「表面」（在哪个面上滑）
和分区维度（横还是纵）都是能写错且错了看不出来的东西，brain 的 [[site-copy]] 里
记过一次：文案把「键盘表面上滑」写成「按住候选上滑」，页面照发，演示的不是这个功能。

## 发布

发布是 **dispatch 本仓的 workflow**，不是本机跑脚本：

```bash
gh workflow run deploy-site.yml -R "$GITHUB_REPOSITORY"   # 只重发站点（包不变）
gh workflow run publish-ime.yml -f source_ref=master      # 出新包 + 重发站点
```

本机可以做的只有**构建与自检**（判据同一条，只是交不了 Pages）：

```bash
tools/release/publish-site.sh              # 取包 + 构建 + 自检，产物留在 dist/
tools/release/publish-site.sh --verify     # 线上那份字节 == dist/ 里的字节
tools/release/publish-site.sh --skip-fetch # 不联网的 HTML 冒烟构建（没有 media/，这种产物不可部署）
```

### 站点挂在哪个地址，一次构建只认一个

`base` 决定资源与下载链接挂在哪个前缀下，它由 `publish-site.sh` 注入：仓库变量
`PAGES_DOMAIN` 没配 ⇒ `/<仓名>/`；配了 ⇒ 按那个域的根（`base=/`），`--verify` 也跟着去
那个域比字节。为什么必须有开关：项目页配了自定义域之后站点在**域根**服务（`/仓名/` 反而
404），而 `github.io/<仓名>/` 变成 301 指过去 —— 一份产物只能服务一边。

域本身在这里不生效：GitHub 明说「仓库里的 CNAME 文件不会自动加/删自定义域」，要在仓库
设置或 API 里配。构建之后脚本会拿产物里的资源前缀对一遍（`base_prefix` 那条），因为
`config.mts` 里还留着一个兜底 base —— 两边分叉的时候页面照样出、构建照样绿，而用户那边
满屏 404。

为什么本机发不了：开发机到 GitHub 的上传**单个请求持续 ~20 秒就被 408 掐断**
（实测 12MB 失败、2MB 通过），而两条渠道的包合计 ~233MB，git 也无法续传。
搬到 runner 之后，包的走法是 release 资产 → runner → Pages artifact，**全程不进 git**：
GitHub 对单个文件有 100.00MB 的服务端硬上限，带离线语音模型的包（β 103.06MB /
dev 130.07MB）在任何机器上都推不进去。

判据是**公开站点上那份与本次产物逐字节一致**，不是「run 绿了」。
`upload-pages-artifact`、`deploy-pages`、线上生效是三件事，中间还有 CDN 缓存，
所以 `--verify` 轮询到字节对上为止，对不上就非零退出。

站点只发两条渠道：**测试版 β（主下载）** 与 **开发版 dev（可选）**。
β 取不到就不发布（首页按钮会 404）；dev 取不到只让按钮不渲染，不阻断发布。