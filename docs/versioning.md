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

## 发布链路

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
#    —— 这一步**本身就是发版 commit**,不是夹在别的改动里(见上面规矩第 5 条)
# 2. 本地校验(闸门,不是单个脚本)
tools/release/gate.sh --release    # 七步,含单测 / 构建 / JNI 闸门 / E2E 引用
# 3. 打 tag 并推 —— 这一推才真的发版
git tag -a v0.5.2 -m "v0.5.2" && git push origin v0.5.2
# 4. 等流水线：单测 → assembleRelease/Debug → GitHub Release
# 5. 同步到站点(把包挂到公开仓 gaia 的 release)
tools/release/sync-apk-to-site.sh v0.5.2
# 6. 构建站点并推产物。判据是**公开站点上那份与本地产物逐字节一致**
tools/release/publish-site.sh
```

第 5 步的 `publish-site` 任务在没配 `WEB_RELEASE_TOKEN` 时是 **skipped 且退出码 0** ——
会伪装成成功。`v0.4.2`/`0.4.3`/`0.4.4`/`0.4.8` 都漏过这一步,站点停在旧版本而没人发现。

第 6 步(`publish-site.sh`)是 2026-10-08 站点 monorepo 化之后的一步:站点源码在
本仓库 `web/`,构建在这里,产物推进公开仓 `adam-ikari/gaia`,Pages 从那个仓的
`main` 分支发布。**公开仓里没有源码,只有产物**。

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
`publish-site.sh` 自己做完:

| 验什么 | 判据 |
| --- | --- |
| 公开站点上的 `media/gaia.apk` 是这次的包 | 与本地产物**逐字节**一致（轮询到对为止） |
| 公开站点的首页是这次的产物 | 与本地产物的 `index.html` 逐字节一致 |
| 页面上给用户的 sha256 对得上 | 产物里 APK 的 sha256 与注入值一致 |
| 产物里没有指向 404 的下载链接 | 三条渠道的 APK 要么在产物里、要么按钮不渲染 |

关键区分:**release 资产、Pages 产物、公开站点上是三个东西**。
上传成功就能过 `releases/latest/download/…` 那道 200,而站点按钮发的是站点上那份,
它只在产物推送之后才刷新 —— 所以「200」验不到它。

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
