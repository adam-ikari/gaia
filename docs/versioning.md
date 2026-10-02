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
| `v*` tag | CI 触发条件、Release 标题、**站点页面显示的版本号** | 打 tag 时写 |

三者没有任何天然约束，于是有两种**静默**失败：

1. **站点说 0.9.9，应用报 0.5.0。** 站点版本号来自 tag
   （站点仓库的 `deploy.yml` 把 tag 剥掉 `v` 注入 `VITE_APP_VERSION`），应用版本号来自
   `versionName`。不一致时用户从站点下到「0.9.9」、装完应用里写着 0.5.0 —— 不报错，只是骗人。
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

## 检查

发版前跑一次（CI 里也会跑）：

```bash
tools/release/check-version.sh            # 用 HEAD 当前版本
tools/release/check-version.sh v0.5.2     # 或指定即将打的 tag
```

它验三件事：格式、tag 与 `versionName` 一致、`versionCode` 未与任何旧 tag 冲突。

> **2026-10-02 现状**：`release.yml` 还没接这个检查。目前是本地跑，人会忘。
> 接进 CI 的第一步是把它插在「Unit tests」之前 —— 版本号错了就不该浪费时间跑测试。

## 发布链路

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName，与改动一起提交
# 2. 本地校验
tools/release/check-version.sh v0.5.2
# 3. 打 tag 并推
git tag -a v0.5.2 -m "..." && git push origin v0.5.2
# 4. 等流水线：单测 → assembleRelease/Debug → GitHub Release
# 5. 同步到站点（CI 的 publish-site 需要 WEB_RELEASE_TOKEN，未配则手动）
tools/release/sync-apk-to-site.sh v0.5.2
# 6. 验：匿名下载的字节与 release 里的逐字节一致
```

第 5 步的 `publish-site` 任务在没配 `WEB_RELEASE_TOKEN` 时是 **skipped 且退出码 0** ——
会伪装成成功。`v0.4.2`/`0.4.3`/`0.4.4`/`0.4.8` 都漏过这一步，站点停在旧版本而没人发现。
所以第 6 步不能省。

## 历史

| tag | versionCode | versionName |
| --- | --- | --- |
| v0.4.9 | 13 | 0.4.9 |
| v0.5.0 | 14 | 0.5.0 |
| v0.5.1 | 15 | 0.5.1 |

`v0.3.0` 没有 tag，但 3 号被烧掉了 —— 所以 `versionCode` 与版本号**没有**换算关系，
它是发布次数的计数器。
