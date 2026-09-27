# AI 驱动功能测试代理手册(PLAYBOOK)

给 AI 代理(如 ZCode 会话)的测试执行手册:用本目录的 harness 在真机上跑功能场景,
读取证据做判定,必要时用视觉能力人工级复核。**测试会接管手机约 1-2 分钟——先确认用户不在用手机。**

## 前置检查

1. `adb devices` — 设备在线且 authorized(序列号 TTANCN2100002663)
2. APK 已安装:`adb shell pm path dev.hwkbd.ime`;没有则 `./gradlew :app:assembleDebug && adb install -r`
3. 用户确认可以接管设备

## 执行

```bash
node tools/aitest/harness.mjs list        # 场景清单(10 个)
node tools/aitest/harness.mjs run all     # 全量(约 90 秒)
node tools/aitest/harness.mjs run fly-select   # 单场景
```

AI 视觉判卷(可选,需 OpenAI 兼容多模态 API):

```bash
AITEST_API_KEY=xxx node tools/aitest/harness.mjs run all
```

判卷默认走智谱 `glm-4v-flash`(免费);换模型改 `AITEST_BASE_URL/AITEST_MODEL`。

## 代理执行规范

1. **跑套件后先读 `evidence/<时间戳>/report.json`**——确定性断言全部通过只代表文本/日志层 OK。
2. **视觉复核**:candidate-center / layer-chips 两个场景的断言依赖 AI 判卷;无 API Key 时,
   代理应自己 Read 对应截图 PNG,按场景 judge.question 逐条目检并给出 PASS/FAIL 结论。
3. **崩溃零容忍**:report.crashesDuringRun > 0 立即判 FAIL,`adb logcat -b crash` 取堆栈,先修再测。
4. **失败归因顺序**:看 logcat(`fly judge` 行)→ 复现注入坐标 → 检查是否被通知栏/系统对话框抢焦点
   (常见:注入 KEYCODE_SYM 会触发 AGUI 选择器,物理键才走真实路径)→ 阈值/映射调整。
5. **环境还原**:测试结束把前台还给用户之前的 app;失败场景保留 evidence 目录不清理。

## 场景与断言方式一览

| 场景 | 确定性断言 | AI 视觉 |
|---|---|---|
| compose-space | editText 以「你」结尾 | |
| candidate-center | | 「你」在中间槽位 |
| five-key-slots | Shift/Alt/Ctrl 上屏互异的汉字 | |
| page-flip | logcat `fly page: next/prev` | |
| fly-select | logcat `fly select: zone=2` | |
| longpress-delete | 删词后槽0 换词 | |
| layer-chips | | 蓝色实心「符」/灰色「En」 |
| en-mode-typing | editText 以「ab」结尾 | |
| settings-open | 前台 = SettingsActivity | |
| l2-output | editText 以「1」结尾 | |

## 局限(已知、按设计)

- 飞字注入走屏幕坐标系模拟,与真手指的 TOUCHPAD 路径等价但力度感不同;手感仍需人工确认
- 注入 KEYCODE_SYM 必触发 AGUI 选择器 → Sym 槽位与物理 Sym 键测试只能由人工完成
- 长按删词/词频排序依赖设备 user_dict.json 的历史状态,断言用"相对变化"而非绝对词序
