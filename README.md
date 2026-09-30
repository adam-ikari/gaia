# gaia

Gaia（盖亚输入法）的公开站点（VitePress），部署到 GitHub Pages：
<https://adam-ikari.github.io/gaia/>

应用本体闭源，源码不在这里——本仓库只有站点内容与 Pages 部署流水线。下载包挂在本仓库的
Release 上（应用仓库 `adam-ikari/hwkbd_ime` 是私有的，那边的下载链接需要登录态）。

## 本地开发

```bash
npm install
npm run dev      # http://localhost:5173/gaia/
npm run build    # 产出 docs/.vitepress/dist
npm run preview  # 预览构建结果
```

## 结构

```
docs/
  .vitepress/
    config.mts            站点配置（base 必须是 /gaia/）
    theme/index.ts        扩展默认主题，注册全局组件
    theme/components/     Downloads.vue：读本仓库 latest Release 列包
  index.md                首页（hero + 功能卡片）
  guide/                  功能详解 / 下载与安装 / 常见问题 / 关于与致谢
  public/.nojekyll        跳过 Pages 的 Jekyll 处理
.github/workflows/deploy.yml   build → upload-pages-artifact → deploy-pages
```

## 发布下载包

`Downloads.vue` 取的是**本仓库**的 `releases/latest`，文件名里的 `release` / `debug` 决定页面上的
说明文案。新版本发布的两步：

1. 应用仓库 `adam-ikari/hwkbd_ime` 打 `v*` 标签，走它自己的 CI 出 APK；
2. 把两个包传到本仓库的同名 Release：

```bash
gh release create v0.4.1 \
  app/build/outputs/apk/release/app-release.apk#app-release.apk \
  app/build/outputs/apk/debug/app-debug.apk#app-debug.apk \
  --repo adam-ikari/gaia
```

站点会在下一次访问时自动显示新版本；想让改动立刻上线，往 `main` 推一次即可触发 Pages 部署。
