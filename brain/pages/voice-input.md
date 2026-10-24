---
id: voice-input
title: "长按空格语音输入:系统 SpeechRecognizer + 设置开关"
category: decision
status: active
tags: [ime, voice, permission]
created: "2026-10-24T14:31:03"
updated: "2026-10-24T18:10:49"
---

<!-- compiled_truth -->
语音输入**不再依赖系统 `SpeechRecognizer`**(它默认落到 Google 语音服务),改为应用内可切换的三个引擎:

| 引擎 | 形态 | 中文/英文 | 费用与条件 |
| --- | --- | --- | --- |
| **本地 sherpa-onnx + Paraformer-zh-small**(默认) | 内置 int8 ONNX 模型,整段录完再解码,完全离线 | 仅中文 | Apache 2.0,无账号;模型 zip 约 71MB,识别质量明显优于原 Vosk small |
| **腾讯云一句话识别** | 按住期间本地攒 PCM,松手整段 POST | 中/英 | 需用户自己的 SecretId/SecretKey;每月免费额度以腾讯云当期政策为准 |
| **讯飞流式听写** | WebSocket 边说边传,部分结果进 HUD | 中/英 | 需用户自己的 AppID/APIKey/APISecret;免费额度以讯飞当期政策为准 |

本地引擎原先用 Vosk `vosk-model-small-cn-0.22`,真机识别效果差,已整体替换为 sherpa-onnx AAR + Paraformer 模型(2026-10-24)。

## 触发契约(与引擎无关,沿用原设计)

- **只在空闲 L1 长按空格**武装;短按仍是空格,300ms 到点才启动识别,松手停止收音。
- 下列情况一律不武装,空格保持原语义:Alt/Sym/Shift 按住或锁定、组词中(空格 = 选首选)、L5 面板开着、密码框、数字类输入框。
- 与字母长按共用同一个 `LongPress` 和同一个 300ms 定时器;`LongPress.voice` 标记落点。武装期间系统连发全吞,避免"按住先漏一串空格再开始听"。
- 短按在 keyUp 补一个空格(提交推迟到 UP);长按启动成功后 keyUp 只 `stopVoiceInput`,不补空格。
- **启动失败退回一个空格**:无权限 / 引擎启动异常都返回 false,控制器补空格并 Toast。
- 识别结果只上屏**最终结果**;部分结果只进 HUD,不写输入框。

## 云端失败自动回落本地

- 选云端时,引擎保留已录 PCM;遇到 `NETWORK / AUTH / BUSY / PROVIDER / GENERIC` 错误,把整段录音交给 `VoiceCallbacks.fallbackAudio`,由 `VoiceInput` 用本地模型再识别一次,不白丢这次按压。
- 回落成功后 Toast「云端识别不可用,已改用本地模型」。
- 回落触发后 5 分钟内直接用本地,避免每次等云端超时;到期再试云端。
- 选了云端但**没填密钥**时同样直接走本地(仅中文);英文仍提示改用云端,因为本地模型只认中文。
- 设置页因此不再要求云端配置才能打开语音开关:本地永远是可用兜底。

## 引擎选择与本地模型

- 设置项 `voice_engine`: `LOCAL`(默认) / `TENCENT` / `XFYUN`,见 `VoiceSettings` 与 `AsrBackend`。
- 本地模型 zip 放在 `app/src/main/assets/sherpa/paraformer-zh-small.zip`,`noCompress += "zip"`。首次使用解压到 `filesDir/sherpa/<model>/`:先解到 `.tmp`,校验 zip-slip,写完 `.ready` 再改名;中断不会留下半份模型。
- 本地引擎只支持 `zh`;键盘语言是英文时返回 `UNSUPPORTED_LANGUAGE`,提示改用云端。
- 本地模型在启动时预加载;云端回落时若模型尚未就绪,先把 PCM 暂存,加载完再解码。
- 引擎实例按 `VoiceSettings.engineKey` 复用:本地只看 backend(改云端密钥不丢已加载的 Paraformer 模型);云端把凭据算进去,换账号即重建。

## 云端密钥与隐私

- 密钥**只能由用户在设置页填写**,只存本机 SharedPreferences,**绝不打进 APK**(否则反编译即可拿走)。
- 设置页把密钥字段做成密码输入框,摘要只显示"已设置/未设置",不回显内容;选本地时整块云端设置收起。
- 选云端时录音会发给对应服务商,设置页有明确提示;Manifest 因此声明 `INTERNET`(本地引擎不联网)。

## 权限归属

- `RECORD_AUDIO` 在设置页申请(`SettingsActivity` + `RequestPermission`),IME 是 Service,不能自己弹权限框。
- 开关默认关。设置页开门对账:开关为真但权限已被系统撤销 ⇒ 落回 false,不留"开着但长按没反应"的假状态。
- 运行期再兜一层:`VoiceInput.start()` 复查权限、配置与语言支持,失败返回枚举给控制器。

## 语言与生命周期

- 语言跟随键盘当前语言:本地 `zh`;腾讯 `16k_zh`/`16k_en`;讯飞 `zh_cn`/`en_us`。
- `resetLayers()`(收窗口/换框复位)取消待判定并 `cancelVoiceInput`;`onFinishInput` 取消;服务 `onDestroy` 销毁;开关关掉时立即取消。原则:**窗口收起后不继续占麦克风**。
- 拾音上限 60 秒(腾讯/讯飞),到点自动停;讯飞停止收音后 15 秒无最终结果超时释放;腾讯 HTTP 读超时 20 秒。

## UI

- 状态显示改为独立 HUD 气泡(`voice_hud`):悬浮在候选条上方,含麦克风图标、12 格音量表和状态/部分结果文字;不占 `contentTopInsets`,不影响应用布局。
- 音量由 `PcmRecorder` 按 PCM16 RMS 换算 dBFS(-50dB 底)映射到 0..1,主线程送入 `VoiceLevelView`(快起慢落)。
- 图标与音量条颜色跟随主题 accent。错误仍用 Toast,服务商细节只放进带 `%s` 的模板,不泄露密钥。

## 验证状态

- 触发器 JVM 单测覆盖 10 种情形;两个云端签名有固定向量单测;`testDebugUnitTest` 与 `compileDebugKotlin` 通过。
- sherpa-onnx 版本:`assembleDev`(R8)通过,dev APK 约 125MB;mapping 中 `com.k2fsa.sherpa.onnx.**` 未改名,keep 规则生效。
- **尚未真机验证**:Titan 2 上的拾音、Paraformer 解压/加载耗时与识别质量、音量表观感、云端回落、腾讯与讯飞真实鉴权都需装机确认。


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
