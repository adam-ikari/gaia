---
id: voice-input
title: "长按空格语音输入:系统 SpeechRecognizer + 设置开关"
category: decision
status: active
tags: [ime, voice, permission]
created: "2026-10-24T14:31:03"
updated: "2026-10-28T14:40:07"
---

<!-- compiled_truth -->
语音输入**只有一条路径:本机离线识别**,不依赖系统 `SpeechRecognizer`(它默认落到 Google 语音服务),也不再有任何云端引擎(2026-10-24 移除,见时间线 reversal)。

| 项 | 决定 |
| --- | --- |
| 引擎 | sherpa-onnx + `paraformer-zh-small`(int8 ONNX),非流式:整段录完一次解码 |
| 语言 | **仅中文**。英文长按空格直接提示不支持,没有云端可换 |
| 配置 | **零配置**。没有引擎选择、没有密钥输入框、没有任何需要填写的东西 |
| 网络 | 不联网,Manifest **不声明 `INTERNET`**;`RECORD_AUDIO` 是应用唯一的敏感权限 |
| 体积 | 模型 zip 约 71MB(**构建期下载**,不在仓库里),dev APK 约 124MB |

## 为什么删掉云端

腾讯云一句话识别与讯飞流式听写的请求本身就带鉴权(TC3-HMAC-SHA256 / `api_key` + HMAC 签名),**不填密钥就没有"云端免鉴权"这条路** —— 服务端在握手阶段就拒绝。而"要用户先去两个平台开账号、填四五个密钥"这个门槛,让语音输入对多数人等于不可用;加上密钥与服务商额度、隐私提示带来的一整块设置 UI 与两个引擎实现,维护成本与收益不成比例。识别质量上 Paraformer 已经够用,所以选择**只留本地**。

删除范围:`TencentEngine` / `XfyunEngine` / `Tc3Signer` / `XfyunSigner` 及其单测、`VoiceSettings` 与 `AsrBackend`、设置页的引擎选择与密钥输入项、`INTERNET` 权限、OkHttp/Okio 依赖、云端失败自动回落逻辑(没有云端可回落)。`VoiceInput` 现在只是"主线程回调桥 + 权限检查 + 一个本地引擎实例"。

## 老密钥要抹掉

已经填过云端密钥的用户,值还留在 SharedPreferences 里。代码不再读它们,但**留着就是白留**(备份到别的设备、日后取证都还在)。`ImeRuntime.init` 启动时一次性 `remove` 掉 `voice_engine` 与五个密钥键。

## 触发契约

- **只在空闲 L1 长按空格**武装;短按仍是空格,300ms 到点才启动识别,松手停止收音。
- 下列情况一律不武装,空格保持原语义:Alt/Sym/Shift 按住或锁定、组词中(空格 = 选首选)、L5 面板开着、密码框、数字类输入框。
- 与字母长按共用同一个 `LongPress` 和同一个 300ms 定时器;`LongPress.voice` 标记落点。武装期间系统连发全吞,避免"按住先漏一串空格再开始听"。
- 短按在 keyUp 补一个空格(提交推迟到 UP);长按启动成功后 keyUp 只 `stopVoiceInput`,不补空格。
- **启动失败退回一个空格**:无权限 / 启动异常都返回 false,控制器补空格并 Toast。
- 识别结果只上屏**最终结果**;没有部分结果可显示(非流式模型)。

## 本地模型

- zip 由 `:app:fetchVoiceModel` 在构建期产出到 `build/generated/voiceAssets/sherpa/`(作为 main assets 的一个 srcDir),模型**不在版本库里**;`noCompress += "zip"`。首次使用解压到 `filesDir/sherpa/paraformer-zh-small/`:先解到 `.tmp`,校验 zip-slip,写完 `.ready` 再改名;中断不会留下半份模型。
- 源包:`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`(77,920,048 字节,sha256 `da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450`)。任务只取 `model.int8.onnx` + `tokens.txt`,按固定条目时间与顺序重打包 —— 同一个源包编出同一个 zip,否则「按 hash 复现」被一个随机器时间变的二进制破坏。
- **为什么不提交进仓库(2026-10-26 推翻「直接内置」)**:这台机器到 GitHub 的**上传**在 8MB 就被 HTTP 408 掐断(实测 2MB 过、8MB/20MB 挂;release 资产 3MB 过、10MB 挂)。74MB 的模型进 git 等于**整个仓库推不上去** —— 源码那几 MB 被一个二进制绑死。
- 缓存与截断:`<root>/.cache/voice-model/paraformer/<版本>/` 只存原始 tar.bz2(gitignore,`./gradlew clean` 不清它);解包放 `build/tmp/`(上游包里白带着 fp32 模型与测试音频,400MB 上下)。这条网络**连下载也会静默截断**(实测只拿到 46,872,955/77,920,048 字节而连接正常结束),所以下载按 `.tmp` 断点续传、**先对字节数再对 sha256**;字节数完整而 sha 不符才判定为上游换包。
- 离线逃生口:`-PvoiceModelTar=/path/to/那个tar.bz2`,用本地那份,依旧校验 sha256。

- 开关一打开就 `prepare()` 预热,别让用户长按时等解压;松手到出字在 Titan 2 上约 0.3–0.7s。
- R8 要 keep `com.k2fsa.sherpa.onnx.**`:config/recognizer 由 JNI 按类名与字段名读取,混淆后症状是模型路径传进去、第一次 `decode` 才崩。

## 权限归属

- `RECORD_AUDIO` 在设置页申请(`SettingsActivity` + `RequestPermission`),IME 是 Service,不能自己弹权限框。
- 开关默认关。设置页开门对账:开关为真但权限已被系统撤销 ⇒ 落回 false,不留"开着但长按没反应"的假状态。
- 运行期再兜一层:`VoiceInput.start()` 复查权限,失败返回枚举给控制器。

## 生命周期

- `resetLayers()`(收窗口/换框复位)取消待判定并 `cancelVoiceInput`;`onFinishInput` 取消;服务 `onDestroy` 销毁;开关关掉时立即取消。原则:**窗口收起后不继续占麦克风**。
- 拾音不设 60 秒上限(那是云端接口的约束),但长按期间的连发全吞。

## UI

- 状态显示是独立 HUD 气泡(`voice_hud`):悬浮在候选条上方,含麦克风图标、12 格音量表与状态文字;不占 `contentTopInsets`,不影响应用布局。
- 音量由 `PcmRecorder` 按 PCM16 RMS 换算 dBFS(-50dB 底)映射到 0..1,主线程送入 `VoiceLevelView`(快起慢落)。
- 图标与音量条颜色跟随主题 accent。错误用 Toast。

## 验证状态

- 触发器 JVM 单测覆盖 10 种情形;`testDebugUnitTest` 与 `compileDebugKotlin` 通过。
- `assembleDev`(R8)通过,dev APK 124MB;`aapt2 dump permissions` 确认只剩 `RECORD_AUDIO`;mapping 中 `com.k2fsa.sherpa.onnx.**` 未改名。
- **尚未真机验证**:Titan 2 上的拾音、Paraformer 解压/加载耗时与识别质量、音量表观感需装机确认。


## Timeline

- time: 2026-10-24T14:31:03
  kind: decision
  summary: "Created this page: 长按空格语音输入:系统 SpeechRecognizer + 设置开关"
  source: created via brain create-page
  affects: [voice-input]

- time: 2026-10-24T14:31:11
  kind: decision
  summary: "语音输入定为:设置开关默认关、空闲 L1 长按空格才武装、按住说话松手上屏;权限只能由设置页申请,IME 启动失败退回一个空格"
  source: "2026-10-24 用户「添加语音输入方式 / 触发方式是长按空格键 设置里增加一个开关」"
  affects: [voice-input]

- time: 2026-10-24T16:12:17
  kind: decision
  summary: "语音输入改用可切换引擎:默认内置 Vosk 离线,云端可选腾讯云一句话识别或讯飞流式听写;彻底移除系统 SpeechRecognizer"
  source: "2026-10-24 用户「不能依赖google服务 选择其他的方法」「先做本地 云端使用腾讯云和讯飞两个服务」「直接内置」"
  affects: [voice-input]

- time: 2026-10-24T16:17:55
  kind: evidence
  summary: "构建验证:testDebugUnitTest(含两个签名固定向量)通过;assembleDebug 与 assembleBeta(R8)通过;beta APK 60MB、debug 86MB(双 ABI),均含 43.9MB 模型 zip;Vosk/JNA keep 规则生效(mapping 中 org.vosk 未改名)。真机拾音与模型解压仍未验证"
  source: "2026-10-24 本地构建"
  affects: [voice-input]

- time: 2026-10-24T18:10:49
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-24 用户「本地语音识别效果很差 换一个模型」→ 选 Paraformer-zh-small;同时加入云端失败自动回落与语音 HUD"
  affects: [voice-input]

- time: 2026-10-24T18:10:49
  kind: decision
  summary: "本地引擎从 Vosk small 换成 sherpa-onnx + Paraformer-zh-small(int8,71MB zip);云端失败自动用本地重识别,5 分钟冷却;语音 HUD 显示麦克风+音量表;无云端密钥也可开启语音开关"
  source: "2026-10-24 用户「本地语音识别效果很差 换一个模型」「云端api不可调用或者额度耗尽的时候使用本地模型」「语音识别的时候需要显示一个悬浮的麦克风图标和音量表」"
  affects: [voice-input]

- time: 2026-10-24T22:05:07
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-24 用户「删除云端API和配置页面项」"
  affects: [voice-input]

- time: 2026-10-24T22:05:08
  kind: reversal
  summary: "推翻「云端可选 + 失败自动回落本地」:整体删除腾讯云/讯飞引擎、签名实现与单测、引擎选择与密钥设置项、云端回落逻辑、INTERNET 权限与 OkHttp 依赖。语音输入只剩本地 sherpa-onnx Paraformer,仅中文、零配置"
  source: "2026-10-24 用户「删除云端API和配置页面项」;此前确认云端不填 key 无法使用(TC3/HMAC 鉴权在请求本体内)"
  affects: [voice-input, stack]

- time: 2026-10-24T22:05:17
  kind: note
  summary: "老用户本机填过的云端密钥要抹掉:ImeRuntime.init 一次性 remove voice_engine 与五个密钥键 —— 代码不读不等于不存在,备份/取证仍会带走"
  source: "2026-10-24 移除云端时的隐私自查"
  affects: [voice-input]


## Timeline

- time: 2026-10-24T14:31:03
  kind: decision
  summary: "Created this page: 长按空格语音输入:系统 SpeechRecognizer + 设置开关"
  source: created via brain create-page
  affects: [voice-input]

- time: 2026-10-24T14:31:11
  kind: decision
  summary: "语音输入定为:设置开关默认关、空闲 L1 长按空格才武装、按住说话松手上屏;权限只能由设置页申请,IME 启动失败退回一个空格"
  source: "2026-10-24 用户「添加语音输入方式 / 触发方式是长按空格键 设置里增加一个开关」"
  affects: [voice-input]

- time: 2026-10-24T16:12:17
  kind: decision
  summary: "语音输入改用可切换引擎:默认内置 Vosk 离线,云端可选腾讯云一句话识别或讯飞流式听写;彻底移除系统 SpeechRecognizer"
  source: "2026-10-24 用户「不能依赖google服务 选择其他的方法」「先做本地 云端使用腾讯云和讯飞两个服务」「直接内置」"
  affects: [voice-input]

- time: 2026-10-24T16:17:55
  kind: evidence
  summary: "构建验证:testDebugUnitTest(含两个签名固定向量)通过;assembleDebug 与 assembleBeta(R8)通过;beta APK 60MB、debug 86MB(双 ABI),均含 43.9MB 模型 zip;Vosk/JNA keep 规则生效(mapping 中 org.vosk 未改名)。真机拾音与模型解压仍未验证"
  source: "2026-10-24 本地构建"
  affects: [voice-input]

- time: 2026-10-24T18:10:49
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-24 用户「本地语音识别效果很差 换一个模型」→ 选 Paraformer-zh-small;同时加入云端失败自动回落与语音 HUD"
  affects: [voice-input]

- time: 2026-10-24T18:10:49
  kind: decision
  summary: "本地引擎从 Vosk small 换成 sherpa-onnx + Paraformer-zh-small(int8,71MB zip);云端失败自动用本地重识别,5 分钟冷却;语音 HUD 显示麦克风+音量表;无云端密钥也可开启语音开关"
  source: "2026-10-24 用户「本地语音识别效果很差 换一个模型」「云端api不可调用或者额度耗尽的时候使用本地模型」「语音识别的时候需要显示一个悬浮的麦克风图标和音量表」"
  affects: [voice-input]

- time: 2026-10-24T22:05:07
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-24 用户「删除云端API和配置页面项」"
  affects: [voice-input]

- time: 2026-10-24T22:05:08
  kind: reversal
  summary: "推翻「云端可选 + 失败自动回落本地」:整体删除腾讯云/讯飞引擎、签名实现与单测、引擎选择与密钥设置项、云端回落逻辑、INTERNET 权限与 OkHttp 依赖。语音输入只剩本地 sherpa-onnx Paraformer,仅中文、零配置"
  source: "2026-10-24 用户「删除云端API和配置页面项」;此前确认云端不填 key 无法使用(TC3/HMAC 鉴权在请求本体内)"
  affects: [voice-input, stack]

- time: 2026-10-24T22:05:17
  kind: note
  summary: "老用户本机填过的云端密钥要抹掉:ImeRuntime.init 一次性 remove voice_engine 与五个密钥键 —— 代码不读不等于不存在,备份/取证仍会带走"
  source: "2026-10-24 移除云端时的隐私自查"
  affects: [voice-input]

- time: 2026-10-26T10:49:03
  kind: decision
  summary: "语音模型改为构建期下载+缓存(不在仓库):钉 URL/sha256、断点续传、-PvoiceModelTar 逃生口;理由是这台机器 GitHub 上传 8MB 即 408"
  source: "用户 2026-10-26「模型构建时下载缓存」"
  affects: [voice-input]

- time: 2026-10-26T10:49:15
  kind: reversal
  summary: "推翻「模型 zip 直接内置进仓库(app/src/main/assets/sherpa/paraformer-zh-small.zip)」:改为 :app:fetchVoiceModel 构建期按固定 URL+sha256 下载、解包重打包到 build/generated/voiceAssets 并挂进 main assets。理由不是洁癖,是这台机器 git push 到 8MB 就 HTTP 408 —— 带着 74MB 的仓库根本推不上去,连源码一起锁死"
  affects: [app/build.gradle.kts, app/src/main/assets/sherpa, .gitignore, THIRD_PARTY_NOTICES.md, README.md]

- time: 2026-10-28T14:40:07
  kind: reversal
  summary: "推翻「模型改按需下载」这一整轮方案:语音模型继续构建期由 :app:fetchVoiceModel 抓上游、解包重打成确定性 zip、挂进 main assets、首次使用解到 filesDir;不声明 INTERNET、零配置、站点文案「不联网」全部保持不变。设备侧不写下载器,不引 commons-compress,发布链不新增 publish-model.yml。留一手实测数据免得日后再查:作者本人的 HF 仓库 csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09 里 model.int8.onnx 与内置那份逐字节相同(sha256 3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec,81828675 字节),tokens.txt sha256 4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa(75352),URL 可钉 commit 63ddc3cd0f2810b68289a7b3876e62ef5d53d6df;hf-mirror.com 实测 8MB/3.4s=2.43MB/s 且 GET Range 回 206,huggingface.co 1.57MB/s 同样回 206。"
  source: "2026-10-28 用户裁决:「改回内置」(此前一轮为「语音模型改为在线安装」)"
  affects: [voice-input, release-flow]
