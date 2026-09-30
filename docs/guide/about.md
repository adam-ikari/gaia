# 关于与致谢

## 这是什么

**Gaia（盖亚输入法）** 是为 Unihertz Titan 2 这类**物理全键盘 Android 手机**做的拼音输入法，交互模型参考「可可拼音输入法」，但按 Titan 2 的系统限制重新设计了可行通道：屏幕只承担候选展示与手势，绝大多数输入路径只用物理按键完成。

包名 `dev.hwkbd.ime`，Android 8.0+。

## 许可

Gaia 是**闭源软件，保留所有权利**：未经作者书面许可，不得复制、再分发、修改、反编译或用于商业目的。本站点仓库只有站点内容与部署流水线，**不包含应用源码**。

## 第三方许可

应用内 `设置 → 许可 → 开源许可` 列有随包分发的第三方组件与数据，这里一并致谢：

| 组件 / 数据 | 用途 | 许可 |
| --- | --- | --- |
| AndroidX（core-ktx / appcompat / preference） | 应用框架基础 | Apache License 2.0 |
| Kotlin 标准库、kotlinx-coroutines | 运行时与协程 | Apache License 2.0（含 Kotlin 例外条款） |
| [fxsjy/jieba](https://github.com/fxsjy/jieba) 的词表 | 内置词库的词与词频 | MIT License, Copyright (c) 2013 Sun Junyi |
| [pinyin-pro](https://www.npmjs.com/package/pinyin-pro) | 构建期为词条生成读音 | MIT License, Copyright (c) 2022-present zh-lx |

MIT 许可要求保留原始版权与许可声明，因此应用内关于页与许可页都保留了上述署名。pinyin-pro 只在构建期使用，运行时不含其代码。

## 隐私

应用不申请任何系统权限（含 `INTERNET`），不联网、不上传数据，词库与用户词全部保存在本机。词库首次加载是读内置 assets，之后由用户词库文件补充。

## 反馈

公开反馈渠道尚未开设。本站不含任何统计与追踪代码，页面不收集访客信息。
