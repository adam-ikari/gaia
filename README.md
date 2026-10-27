# hwkbd_ime — 盖亚输入法

为 Unihertz Titan 2 全键盘(TT2)设计的硬件键盘拼音输入法。Android 8.0+(minSdk 26)。

官网与下载(公开站点 Gaia):<https://adam-ikari.github.io/gaia/>

## 功能

- 三层键盘:L1 字母 / L2 符号数字(Alt,厂商 TitanKey.kcm 键帽)/ L3 编程(Sym,可可扩展字符表)
- Alt/Sym:单击一次性、双击锁定、三击循环;**长按进层(按住期间生效),松开取消**
- Shift:单击大写一次、双击 Caps 大写锁定、长按按住期间大写(松手取消),状态行有大写芯片提示
- 候选首选居中(空格位),**Shift/Sym/空格/Ctrl/Alt 五键选字**(左→右对应候选 2/5/1/4/3),Ctrl+字母组合只转发给应用
- 飞字:键盘表面上滑按 5 区选字,词随手指缓慢起升、越过阈值飞完(其余候选同步渐隐),左右滑翻页
- 候选栏**点选上屏**(上滑选字已移除,2026-10-12 用户定);左右滑翻页(动画),长按候选删除用户词;
  两侧翻页箭头贴屏幕边缘(视觉 10dp、触摸区 40px),候选词五槽统一内缩(分栏仍五等分,
  槽心与键盘面飞字区一一对应),长词槽内截断尾补 `…`
- **拼音显示自动加分隔符**:以候选栏中央(首选)候选的音节切分为准(`nihao` 首选是「你好」
  ⇒ 显示 `ni'hao`),首选变、翻页时实时跟随;App 内 composing 文本与
  Termux 类 App 的拼音气泡(preedit_row,16sp)两条显示路径一致(2026-10-12 用户定)。
  手动 `'` 走物理键盘(Alt 层 L 键;组合注入会被五键选字吃掉,手打同样几乎不可达)
  —— 分节主要靠引擎自动切音节(`xian` 就能出西安)
- **首字母输入**:全拼三路都匹配不上时按音节首字母查(`sm` → 什么/生命/说明,2026-09-29)
- 用户词频加权、34.8 万条内置词库(jieba + pinyin-pro 生成,`tools/dictgen/`)
- **L5 浮层面板**:`Sym+Space` / 状态行键盘图标开关(**锁定 Sym 不算按下**,锁着时空格照常上屏;锁着时再按住 Sym 仍可开关);有溢出时第一个 tab「场景」装 L4 一行放不下的尾巴,
  另有 标点 / 符号 / 数学 / 制表 / 希腊 / 剪贴板（字符盘只留 L1-L4 都敲不出的字；**按语义分 tab**,
  2026-10-20 用户「tab 的分类和内容需要调整」——原来混在一起的「技术」拆成
  「符号」(数学/货币/代码符号 `{}[]()<>|\`)与「希腊」）；**面板高度固定两行**,tab 内上下翻页、左右切 tab（2026-10-19 与原 L6 虚拟键盘合并,2026-10-20 定固定两行）。
  所有键格共用 **12 列网格**,单字符只占 1 格(约 40dp)、长域名/短语按内容估宽跨 2–3 格,
  键宽随内容不同但左缘都落在同一套格线上;分页按格算,长内容因此不会被裁字。
  键高 40dp、键帽字 15sp(2026-10-20 用户「tab 中按键太小」:16 列那版单格只有 ~30dp,
  改成 12 列 + 大字)。
  **没有可显示键的 tab 整个隐藏**(场景/剪贴板为空时不再有占位页);密码框只留可打印 ASCII,
  数字/电话框只留数字与该类型合法字符,滤完为空的 tab 同样隐藏(2026-10-19)。
- 48dp 紧凑单行两态 UI(组词中候选行整行,空闲时状态行),状态行分三区:
  - **左=状态芯片**——**只显示已触发的状态**:语言芯片 `拼/En` 恒显;层芯片与大写状态
    **没触发就不出现**(此前 `ALT`/`SYM` 两颗常显灰芯片、大写常显 `ab`)。
    **芯片不可触摸**:切层/切中英/大写一律走物理键(`Alt` 单击=一次性、双击=锁定;
    `Sym` 同;`Shift` 单击=大写一次、双击=Caps 锁定;`Shift+Space`=中英切换)。
    **锁定统一下划线表示**:一次性/按住=无下划线,锁定=底部一根 2dp 实线;
    大写状态是 **Shift 上箭头图标**(`ic_shift`,锁定叠 `ic_shift_lock` 下划线),
    48dp 见方无背景,和右区三键同一套线稿;数字框里 `alt` 那颗文字是实际可输字符集(`0-9`、`0-9+*#`…)
  - **左右统一风格**(2026-09-29):右区三键(键盘/齿轮/收起)全部 **48dp 见方**、
    `scaleType=center` 原尺寸画(收起三角不再用 wrap_content 窄盒),与左区同为方块同行高。
    左右两个盒子**等权重 0.4**(此前 0.6)——权重相等是 L4 到屏幕左右距离相等的前提,
    中区因此拿到约 60% 宽度,一行能多放一把键
  - **中=L4 虚拟按键**——**语言补差键排最左**(拼音没有——分节符走 `v` 键)+ 字段核心键
    (出厂 `；` `$`,**按输入框取合法子集**:网址 `； $` 全留、邮箱只留合法的 `$`
    ——2026-09-29 同一把尺「合法的留、非法的去」;`\` 2026-09-29 已从固定键删除:
    出厂 L3 的 `Sym+z` 就能敲出它,按「L1-L3 已有的不进 L4」不占中区)
    + 输入类型键位套(网址 → `http://` 等,邮件 → **显示优先级排序**:短域名后缀
      `.com .net .org .cn .edu` 在前优先占 L4,完整域名 `@gmail.com @hotmail.com @outlook.com`
      排后、L4 一行装不下(等宽槽按前缀最长标签宽)的尾巴才进 L5 面板的「场景」tab;
    数字类聚焦即锁数字层:0-9/签名 `-`/小数 `.`/电话 `+*#`/日期时间 `-/:.` 各按合法字符放行,
    芯片按实际字符集标注(`0-9`、`0-9.`、`0-9+*#`…)、**L4 不隐藏**;
    这些合法单字符 L2 上都有键帽、按下一把尺不上 L4,所以数字态 L4 只能放**短语组合**:
    出厂**只有电话**有(`+86 +1 +81 +44` 国际区号),其余数字类型没有组合可放故为空
    (可在 `[L4 datetime]` 等段自写短语))
    + **L1-L3 已有的按键一律不进 L4 / L5 字符盘**——按本机 `keymap.txt` 三层输出、叠加当前语言层表与全角开关后筛
      (`ImeRegistry.producible(...)`,**运行时**筛,换手机/换键位表/切全角自动跟随);没有动态键
      (编辑入口已按「L1-L4 不可编辑」移除,出厂键位即最终);
    输入条背景**纯黑**;
    单行不滚动,**键间距固定 4dp**(间距不许被拉大),
    **等宽槽**:所有可见键同宽,槽宽 = min(填充值, 72dp 封顶,不低于最长标签宽)——
    键多时恰好铺满中区 = **两端对齐**;键少时封顶收窄、**整组居中**;等宽+等距 ⇒
    中线恒过中心键心(奇数把)或中缝(偶数把),**左右边距对称 6dp**,
    超出则**尾巴进 L5 面板的「场景」tab**(2026-10-19 起不再有独立的溢出行)
  - **右=L5 面板开关/设置/收起按钮**(48dp 见方,宽 ≥ 高;齿轮按 24dp 原尺寸绘制,不放大;
    **键盘图标在齿轮左侧且常驻**,点按 / `Sym+Space` 开关**条上方的 L5 面板**(开时高亮);
    面板 tabs:场景(原 L4 溢出键,有键时才出现)/ 标点 / 符号 / 数学 / 制表 / 希腊 / 剪贴板,字符盘按 L1-L4 已能敲出的字去重,tab 内上下翻页、左右切 tab;
    密码框/数字类按合法字符集过滤,滤空的 tab 隐藏)

## 构建

```bash
./gradlew :app:assembleDebug        # 调试 APK
./gradlew :app:testDebugUnitTest    # JVM 单测
```

要求 JDK 17,Android SDK(compileSdk 36 缺失时 AGP 自动下载)。

**第一次构建需要网络**:离线语音模型(Paraformer-zh-small,压缩包 78MB)**不在版本库里**,
由 `:app:fetchVoiceModel` 按钉死的 URL 下载并校验 sha256,解出 `model.int8.onnx` + `tokens.txt`
重打包后作为 assets 挂进 APK。原件缓存 `.cache/voice-model/`,`./gradlew clean` 不清它。
离线构建:`./gradlew :app:assembleDev -PvoiceModelTar=/path/to/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`
(依旧校验 sha256)。理由与实现见 `app/build.gradle.kts` 的 `FetchVoiceModel`。

## CI/CD

- **CI**(GitHub Actions,`.github/workflows/ci.yml`):push/PR → 单测 + assembleDebug,上传 APK 构件
- **native**(librime 拼音引擎,2026-10-03 起):`app/src/main/cpp/librime` 是 submodule,
  用 NDK 交叉编译成 `librime_jni.so`(仅 arm64-v8a)。**克隆时必须带子模块**:
  `git clone --recursive`,或 `git submodule update --init --recursive`。
  构建只需要 **NDK 27.1.12297006**(不需要 boost:那份 librime fork 已经把它全替掉了,
  见 `docs/rime.md`)。

- **Release**(`.github/workflows/release.yml`):推送 `v*` tag → 测试 + 构建 release/debug APK → 创建 GitHub Release
  - 可选签名:仓库 Secrets 配置 `SIGNING_KEYSTORE_BASE64`(jks 的 base64)、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`,release 包自动签名;未配置则出未签名包

- **发布 β / dev / 站点**(`.github/workflows/publish-ime.yml`,手动 `workflow_dispatch`)
  按序调 `tools/release/gate.sh --beta` → `publish-beta.sh` → `publish-dev.sh`,
  再调用 `.github/workflows/deploy-site.yml` 构建站点并交给 Pages。站点也可以单独发
  (`deploy-site.yml` 自己能 dispatch,用于只改文案的时候)。
  - **为什么在 runner 上跑,不在本机**:开发机到 GitHub 的上传,单个请求持续约 20 秒
    就被 408 掐断(实测 12MB 失败、2MB 通过)。β 与 dev 各 103,060,018 / 130,072,818
    字节,不是一个能切小的请求,git 也无法续传。
  - **为什么产物不进 git**:GitHub 对单个文件有 100.00MB 的服务端硬上限(`GH001`,
    与机器、带宽、协议都无关)。包的走法是 release 资产 → runner → Pages artifact。
  - **站点挂在哪个地址**:默认 `https://<owner>.github.io/<仓名>/`;仓库变量
    `PAGES_DOMAIN` 一设,产物就改按那个域的根产出(`base=/`)、`--verify` 也去那个域比字节。
    两者只能选一个 —— 项目页配了自定义域之后站点在域根服务,而 `github.io/<仓名>/`
    变成 301 指过去。取舍与实测见 `docs/versioning.md` 的「站点挂在哪个地址」。
  - 2026-10-27 之前这条链的执行者在公开仓 `adam-ikari/gaia`,靠一枚只读本仓的 PAT 跨仓
    取源码;那个仓现在并进了本仓(源码、站点、产物、构建同一处),跨仓凭据随之删除。
  - 签名:配 `SIGNING_KEYSTORE_BASE64` + `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS`
    / `SIGNING_KEY_PASSWORD` 四个 Secrets。少配任何一个,`gate.sh` 第 5c 步读产物证书
    指纹时直接判红 —— 未签名的包发不出去,而不是悄悄发出去。
  背景与判据见 `docs/versioning.md`。

安装:`adb install app-debug.apk` 或下载 Release 包手动安装,然后系统设置里启用并选择本输入法。首次打开任意输入框会后台加载词库(约 1-2 秒)。

## 许可

本软件以 **GPL-3.0-only** 提供(`Copyright (c) 2026 adam-ikari`),完整条款见 [LICENSE](LICENSE)。
第三方组件(AndroidX、Kotlin、librime 及其依赖、jieba 词表、pinyin-pro)各自受其原始许可证约束,
署名与许可证全文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md),应用内 `设置 → 许可 → 开源许可` 是同一份的简版。

再分发时须一并给出源码或获取方式,并保留全部版权声明与这份第三方署名。

## 真机调试

- 设置 → 实验场:干净输入框试输入/飞字/层切换
- 设置 → 按键探测:抓物理键真实 keycode,可一键设为 Sym 键
- 日志:`adb logcat -s ImeService:*`
