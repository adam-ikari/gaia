---
id: voice-input
title: "长按空格语音输入:系统 SpeechRecognizer + 设置开关"
category: decision
status: active
tags: [ime, voice, permission]
created: "2026-10-24T14:31:03"
updated: "2026-10-24T16:17:55"
---

<!-- compiled_truth -->
语音输入**不再依赖系统 `SpeechRecognizer`**(它默认落到 Google 语音服务),改为应用内可切换的三个引擎:

| 引擎 | 形态 | 中文/英文 | 费用与条件 |
| --- | --- | --- | --- |
| **本地 Vosk**(默认) | 内置 `vosk-model-small-cn-0.22` 模型,完全离线 | 仅中文 | Apache 2.0,无账号;代价是 APK 体积与识别质量 |
| **腾讯云一句话识别** | 按住期间本地攒 PCM,松手整段 POST | 中/英 | 需用户自己的 SecretId/SecretKey;每月免费额度以腾讯云当期政策为准 |
| **讯飞流式听写** | WebSocket 边说边传,部分结果进 HUD | 中/英 | 需用户自己的 AppID/APIKey/APISecret;免费额度以讯飞当期政策为准 |

## 触发契约(与引擎无关,沿用原设计)

- **只在空闲 L1 长按空格**武装;短按仍是空格,300ms 到点才启动识别,松手停止收音。
- 下列情况一律不武装,空格保持原语义:Alt/Sym/Shift 按住或锁定、组词中(空格 = 选首选)、L5 面板开着、密码框、数字类输入框。
- 与字母长按共用同一个 `LongPress` 和同一个 300ms 定时器;`LongPress.voice` 标记落点。武装期间系统连发全吞,避免"按住先漏一串空格再开始听"。
- 短按在 keyUp 补一个空格(提交推迟到 UP);长按启动成功后 keyUp 只 `stopVoiceInput`,不补空格。
- **启动失败退回一个空格**:无权限 / 引擎未配置 / 启动异常都返回 false,控制器补空格并 Toast。
- 识别结果只上屏**最终结果**;部分结果只进 HUD 气泡,不写输入框。

## 引擎选择与本地模型

- 设置项 `voice_engine`: `LOCAL`(默认) / `TENCENT` / `XFYUN`,见 `VoiceSettings` 与 `AsrBackend`。
- 本地模型是官方 zip,放在 `app/src/main/assets/vosk/`,`noCompress += "zip"` 避免打包时被压缩。首次启用时解压到 `filesDir/vosk/<model>/`:先解到 `.tmp`,校验 zip-slip,写完 `.ready` 再改名;中断不会留下半份模型。
- 本地引擎只支持 `zh`;键盘语言是英文时返回 `UNSUPPORTED_LANGUAGE`,提示改用云端。
- 引擎实例按 `VoiceSettings.engineKey` 复用:本地只看 backend(改云端密钥不丢已加载的 Vosk 模型);云端把凭据算进去,换账号即重建。

## 云端密钥与隐私

- 密钥**只能由用户在设置页填写**,只存本机 SharedPreferences,**绝不打进 APK**(否则反编译即可拿走)。
- 设置页把密钥字段做成密码输入框,摘要只显示"已设置/未设置",不回显内容;选本地时整块云端设置收起。
- 选云端时录音会发给对应服务商,设置页有明确提示;Manifest 因此声明 `INTERNET`(本地引擎不联网)。

## 权限归属

- `RECORD_AUDIO` 在设置页申请(`SettingsActivity` + `RequestPermission`),IME 是 Service,不能自己弹权限框。
- 开关默认关。设置页开门对账:开关为真但权限已被系统撤销 ⇒ 落回 false,不留"开着但长按没反应"的假状态。
- 打开开关前检查所选引擎的 `configured`:本地永远可用,云端缺密钥则拒绝开启并提示先配置。
- 运行期再兜一层:`VoiceInput.start()` 复查权限、配置与语言支持,失败返回枚举给控制器。

## 语言与生命周期

- 语言跟随键盘当前语言:本地 `zh`;腾讯 `16k_zh`/`16k_en`;讯飞 `zh_cn`/`en_us`。
- `resetLayers()`(收窗口/换框复位)取消待判定并 `cancelVoiceInput`;`onFinishInput` 取消;服务 `onDestroy` 销毁;开关关掉时立即取消。原则:**窗口收起后不继续占麦克风**。
- 拾音上限 60 秒(腾讯/讯飞),到点自动停;讯飞停止收音后 15 秒无最终结果超时释放;腾讯 HTTP 读超时 20 秒。

## UI

不新增布局:复用候选条上方的拼音气泡显示「正在准备语音模型… / 正在连接… / 正在听… / 识别中… / 听:<部分结果>」;错误用 Toast,服务商细节只放进带 `%s` 的模板,不泄露密钥。语音只在空闲态启动,与组词互斥,所以不会和拼音抢气泡。

## 验证状态

- 触发器 JVM 单测覆盖 10 种情形(开关关闭、长按/短按、连发、启动失败、Shift/Sym+空格、组词、密码框/数字框、语言跟随、复位取消);两个云端签名有固定向量单测;`testDebugUnitTest`、`compileDebugKotlin` 通过。
- **尚未真机验证**:Titan 2 上的拾音、Vosk 模型解压/加载耗时、腾讯与讯飞的真实鉴权与返回都需要装机确认。本地模型 43.9MB zip 会让 APK 明显变大,也需在真机上确认首次解压时间可接受。


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
