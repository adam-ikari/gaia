# gaia

盖亚输入法公开站点的**构建产物仓**：<https://adam-ikari.github.io/gaia/>

## 这个仓里没有站点源码

站点源码、文案、样式与构建脚本都在私有仓 `adam-ikari/hwkbd_ime` 的 `web/`。
由那里的 `tools/release/publish-site.sh` 构建本仓的 `dist/` 并推送。

```
本仓 dist/          ← 构建产物(这个仓的全部内容)
本仓 .github/       ← 验产物 + 部署 Pages,不构建
私有仓 web/         ← 站点源码(VitePress)
```

要改站点，去私有仓改 `web/`，不要在这里改 `dist/` —— 这里改的任何字节
都不会被任何构建复现出来。

## 为什么产物进 git

原来这个仓自己跑 `npm ci` + `npm run build`，从 release 取 APK 再部署。
那条路的代价是：**「站点更新了没有」这件事只存在于某次 CI run 的状态里**。
应用仓那个等价的 `publish-site` job 因为缺 `WEB_RELEASE_TOKEN` 一直
skipped 且退出码 0 —— 流水线全绿，而站点上的包停在旧版本，
`v0.4.2`/`0.4.3`/`0.4.4`/`v0.4.8` 各漏过一次。

产物进 git 之后，线上那一份**可追溯到某个 commit**，且部署这一步有一个
可见的、每次推送都会发生的记录。

代价是仓库随发版单调变大（每个 APK 6.6–10.7MB）。这是刻意的取舍。

## 部署流水线验什么

`Deploy site` 不构建，只验产物自洽后交给 Pages：

| 验什么 | 致命性 | 抓的是 |
| --- | --- | --- |
| `dist/index.html` 与主渠道 APK 在 | 致命 | 站点空白 / 按钮 404 |
| 每个 APK 是完整 zip 且含 manifest + dex | 致命 | 下载静默截断 |
| 页面上的 sha256 对得上产物字节 | 致命 | 注入值与产物脱节，用户只能「猜」 |
| 可选渠道在不在 | **不致命** | 「这个渠道还没上线」不是故障 |

**缺**与**坏**分开：缺一个可选渠道只是它没上线，不该让主渠道也更新不了；
而明知有坏字节要发布却放它上线，三条渠道一视同仁都该 fail。

## 本地核对产物

```bash
git clone https://github.com/adam-ikari/gaia.git && cd gaia
gh workflow run deploy.yml   # 手动触发一次部署
```

或者直接看线上那份与本仓是否一致：

```bash
curl -sL -o /tmp/gaia.apk https://adam-ikari.github.io/gaia/media/gaia.apk
cmp /tmp/gaia.apk dist/media/gaia.apk && echo 一致
```