# 站点

公开站点 <https://adam-ikari.github.io/gaia/> 的源码。构建产物推到公开仓
`adam-ikari/gaia`，Pages 从那个仓的 `main` 分支发布 —— **公开仓里没有源码，只有产物。**

改站点在这里改，改完跑 `tools/release/publish-site.sh`。

## 本地开发

```bash
cd web
npm install
npm run dev      # http://localhost:5173/gaia/
npm run build    # 产出 web/docs/.vitepress/dist
npm run preview  # 预览构建结果
```

本地构建拿不到 β 的 hash 与 dev 的可用性（它们在构建期注入），
所以下载文件名退回 `gaia-ime.apk`、dev 按钮不渲染。这是设计如此，不是坏了。

## 结构

```
docs/
  .vitepress/
    config.mts            站点配置（base 必须是 /gaia/）
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

```bash
tools/release/publish-site.sh              # 取包 + 构建 + 推产物 + 验
tools/release/publish-site.sh --dry-run    # 只构建，打印会推什么
tools/release/publish-site.sh --skip-fetch # 用 media/ 里已有的包（离线验证构建）
```

判据是**公开站点上那份与本地产物逐字节一致**，不是「push 成功了」。
Pages 从分支发布，`push` 成功与站点已更新是两件事；CDN 还会缓存刚推的那一版。
脚本轮询到字节对上为止，对不上就非零退出。

站点只发两条渠道：**测试版 β（主下载）** 与 **开发版 dev（可选）**。
β 取不到就不发布（首页按钮会 404）；dev 取不到只让按钮不渲染，不阻断发布。