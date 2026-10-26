# 第三方许可与署名（THIRD PARTY NOTICES）

本文件是盖亚输入法（Gaia IME，工程名 `hwkbd_ime`）对**第三方组件与数据**的署名与许可说明。
本软件自身是专有软件、保留所有权利，其条款见同目录的 [LICENSE](LICENSE)；本文件只覆盖外部组件，
不构成对它们的再许可。

进到 APK 里的第三方东西只有下列这些。构建工具（AGP / lint / Gradle 插件）只在构建期使用，
不随应用分发，因此不在此列。

## 1. AndroidX — Apache License 2.0

- `androidx.core:core-ktx:1.13.1`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.preference:preference:1.2.1`
- `androidx.dynamicanimation:dynamicanimation:1.1.0` —— **手势与动画的物理引擎**
  (所有翻页/键帽/上屏动画都由它的 `SpringAnimation` 积分,见 `ui/SpringBridge.kt`)
- 版权：The Android Open Source Project
- 许可证全文：https://www.apache.org/licenses/LICENSE-2.0

## 2. Kotlin 标准库与 kotlinx-coroutines — Apache License 2.0（含 Kotlin 例外条款）

- Kotlin 标准库（Kotlin 1.9.24 随 Android Gradle Plugin 引入）
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`（依赖 `kotlinx-coroutines-core:1.8.1`）
- 版权：JetBrains s.r.o. and Kotlin programming language contributors
- 许可证全文：https://www.apache.org/licenses/LICENSE-2.0
- Kotlin 例外条款（Kotlin Exception to the Apache 2.0 License）：https://kotlinlang.org/legal/apache-2.0-exception.html

## 3. Rime 输入引擎（librime） — BSD-3-Clause

**2026-10-03 换内核**:此前是自己写的查表引擎,词库由 `tools/dictgen` 在构建期
从 jieba + pinyin-pro 生成 `dict.txt.gz`。那一整套(jieba / pinyin-pro
以及 `dict.txt.gz`)**已经随换内核删除,不再随应用分发**。

- 用途:拼音引擎内核。子模块 `app/src/main/cpp/librime`,编译为 `librime_jni.so`
- 来源:https://github.com/rime/librime
- 版权:Copyright (c) 2014, RIME Developers

```
Copyright (c) 2014, RIME Developers All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
* Neither the name of the RIME Developers nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### 3.1 静态编进 `librime_jni.so` 的第三方组件

librime 自带的 vendored 依赖,全部静态链接进我们的 `.so`:

| 组件 | 许可 | 版权 |
| --- | --- | --- |
| yaml-cpp | MIT | Copyright (c) 2008-2015 Jesse Beder |
| leveldb | BSD-3-Clause | Copyright (c) 2011 The LevelDB Authors |
| marisa-trie | BSD-2-Clause OR LGPL-2.1-or-later | Copyright (c) Suneido Software Corp. |
| OpenCC | Apache License 2.0 | Copyright (c) BYTEDANCE |

其中 OpenCC 是 Apache-2.0,它的许可证全文见
`app/src/main/cpp/librime/deps/opencc/LICENSE.txt`。

**其余组件**:librime 原先把 Boost 换成了 `std::regex`,并自带等价实现
(`src/rime/string_utils.hpp` 里的 `namespace boost { … }` shim),
所以**不依赖 Boost**;`deps/sml` 与 `deps/glog`、`deps/googletest`
不在编译路径上。

### 3.2 内置词库数据

- `assets/rime/luna_pinyin.schema.yaml`、`luna_pinyin.dict.yaml`、`symbols.yaml`
  与 `default.yaml` 来自 librime 的 `data/minimal/`,许可同 librime(BSD-3-Clause)。
- `assets/rime/essay.txt`(词频,3.8MB)来自 `adam-ikari/term-ime-dict`。
  **该文件本身没有附带许可声明,来源仓库也未标明许可** ——
  见下面「待补」。

## 4. 语音识别 — Apache License 2.0

- `com.bihe0832.android:lib-sherpa-onnx:6.25.21`（sherpa-onnx 的 Android 打包，随包提供 `libsherpa-onnx-jni.so`）
  - 上游：https://github.com/k2-fsa/sherpa-onnx
  - 打包来源：https://github.com/bihe0832/AndroidAppFactory
  - 上游项目为 Apache License 2.0。
- `paraformer-zh-small`（内置中文模型，构建期打进 `assets/sherpa/`）
  - 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2
  - 压缩包 sha256：`da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450`（77,920,048 字节）
  - **模型不在版本库里**：`:app:fetchVoiceModel` 按上面这个 URL 下载、校验 sha256，
    解出 `model.int8.onnx` + `tokens.txt` 重打包成 `paraformer-zh-small.zip` 挂进 assets。
    原件缓存 `.cache/voice-model/`（gitignore），离线时用 `-PvoiceModelTar=<路径>` 指给构建。
    钉 sha256 是因为上游 release 资产可被替换：不校验的话「构建成功」只说明拿到过某个 79MB 的包。
  - 权重源自 FunASR / ModelScope 的 Paraformer 中文小模型，经 sherpa-onnx 转为 ONNX 后以 Apache 2.0 分发。
  - 上游项目：https://github.com/modelscope/FunASR（Apache License 2.0）
- 许可证全文：https://www.apache.org/licenses/LICENSE-2.0

**语音识别完全离线**：不请求任何云服务，不发送录音，应用也没有声明 `INTERNET` 权限。
2026-10-24 移除云端引擎（腾讯云 / 讯飞）时，OkHttp / Okio 随之退出依赖。

## 5. 待补:essay.txt 的许可(2026-10-04 记下)

`assets/rime/essay.txt` 有 297,731 行词频,是「越打越顺手」那个功能的依据,
随包分发 3.8MB。它来自 `adam-ikari/term-ime-dict`,但:

- 文件里没有许可头、没有版权行;
- 应用仓库与站点仓库都没有记录它的许可;
- term-ime 与 librime 都用同一份 essay.txt(出自朱虎的博客整理),
  librime 自己的发行包通常也带它,但**不能假定上游的再分发条款自动覆盖我们这份**。

**在补上之前,应用内「开源许可」只署了 Rime 一侧**,这是已知缺口,不是有意省略。
要彻底解决,要么拿到该文件的明确许可,要么换成许可清晰的同规模词频表。

## 应用内的对应位置

BSD-3-Clause 与 MIT 要求上述版权与许可声明随副本一同分发,所以署名同时出现在：

- 应用内 `设置 → 许可 → 开源许可`（`res/values/strings.xml` 的 `licenses_body`）
- 应用内 `设置 → 调试 → 关于`（`res/values/strings.xml` 的 `about_body`）
- 站点页脚 `docs/.vitepress/config.mts` 的 `footer.message`
