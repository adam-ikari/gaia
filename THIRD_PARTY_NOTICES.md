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
- 版权：The Android Open Source Project
- 许可证全文：https://www.apache.org/licenses/LICENSE-2.0

## 2. Kotlin 标准库与 kotlinx-coroutines — Apache License 2.0（含 Kotlin 例外条款）

- Kotlin 标准库（Kotlin 1.9.24 随 Android Gradle Plugin 引入）
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`（依赖 `kotlinx-coroutines-core:1.8.1`）
- 版权：JetBrains s.r.o. and Kotlin programming language contributors
- 许可证全文：https://www.apache.org/licenses/LICENSE-2.0
- Kotlin 例外条款（Kotlin Exception to the Apache 2.0 License）：https://kotlinlang.org/legal/apache-2.0-exception.html

## 3. 内置词库数据 — MIT License

内置词库 `app/src/main/assets/dict.txt.gz` 由 `tools/dictgen/gen_dict.mjs` 在**构建期**离线生成，
运行时不包含下列项目的代码。

### 3.1 jieba 词频表

- 用途：词条与词频（`dict.txt`，1–6 字 CJK 词条）
- 来源：https://github.com/fxsjy/jieba

```
The MIT License (MIT)

Copyright (c) 2013 Sun Junyi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 3.2 pinyin-pro

- 用途：构建期为词条生成读音（`tools/dictgen/package.json` 的依赖）
- 来源：https://www.npmjs.com/package/pinyin-pro

```
MIT License

Copyright (c) 2022-present zh-lx

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 应用内的对应位置

MIT 要求上述版权与许可声明随副本一同分发，所以署名同时出现在：

- 应用内 `设置 → 许可 → 开源许可`（`res/values/strings.xml` 的 `licenses_body`）
- 应用内 `设置 → 调试 → 关于` 的「词库来源：fxsjy/jieba (MIT)」
