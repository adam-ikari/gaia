---
id: librime-android-build
title: "librime 交叉编译到 Android:四个坑与它不需要 boost"
category: project
status: active
tags: [librime, android, ndk, cmake, jni, rime]
created: "2026-10-03T11:24:13"
updated: "2026-10-03T11:24:36"
---

<!-- compiled_truth -->
## 结论先行

v0.7.0 把拼音内核从自写的 `PinyinEngine`(查表 + 分节位)换成 **librime**,
直接复用 `adam-ikari/term-ime` 的构建配方。产物 `librime_jni.so`
(arm64-v8a + x86_64),**只依赖 `libdl` / `libm` / `libc`**,无 ICU 无 glog。

**不对比,直接替换** —— 用户明确要求,测试也不需要与以前一致。

## 坑一:`ANDROID_ABI` 必须转发给独立子工程

四个 vendored deps(yaml-cpp / leveldb / marisa-trie / opencc)是**独立的**
CMake 工程(`cmake -S ... -B ...`),不继承外层变量;而 ABI/platform 是
toolchain 文件**从这两个 CMake 变量读**的。

不转发 ⇒ 工具链退回默认 **armeabi-v7a** ⇒ 编出的 `.a` 是 32 位 ARM,
而 librime 本体是 aarch64,报错长得像「依赖坏了」:

```
libyaml-cpp.a(binary.cpp.o) is incompatible with aarch64linux
```

**term-ime 不会踩到,因为它在目标机器上原生编译**,ABI 本来就对。
所以照抄它的 CMakeLists 时,这条得自己补。

## 坑二:`opencc_dict` 要多编一份**主机版**

词典转换发生在 opencc 的 **install** 步骤里,调的是 `opencc_dict` 这个
**可执行程序**。交叉编译出来的是 arm64 的,主机跑不了 ⇒ `Exec format error`。

`luna_pinyin.dict.yaml`(890KB)转成 `.ocd2` 必须经过它。
好消息:`.ocd2` 词典数据**与架构无关**,所以主机版工具的产物就是对的。
于是编两次:(a) 主机版(不带 toolchain)只为拿到能跑的工具,
(b) 目标版(带 toolchain)提供 Android 要用的 `libopencc.a`。

## 坑三:`opencc_dict` 在 `<build>/src/tools/` 且只在 install 步骤需要 PATH

configure 阶段一切正常,只在 `--build --target install` 时找不到。
路径是 `${DEPS_STAGE}/../_deps_build/opencc_host/src/tools`,不是 build 根下。
⇒ PATH 必须在 **build 那一步**就在位(不是 configure),而且放**主机版**那一份。

## 坑四:**不需要 boost**(用户直接说了「不需要boost」)

那份 fork 已经把 boost 清干净了:

- 正则走 `std::regex`(`algo/calculus.cc` 里是 `std::regex_replace` /
  `std::regex_match`)
- `starts_with` / `split` / `trim` / `is_any_of` 这些它**自己**在
  `src/rime/string_utils.hpp` 里用 `namespace boost { ... }` 重写了一份,
  再以 `boost::algorithm::` 导出(有些调用点写的是全限定名)
- 源码里剩下的 `#include <boost/...>` 只有两处,都**编不到**:
  `plugins/plugins_module.cc` 被 `ENABLE_EXTERNAL_PLUGINS`(默认 OFF)挡住,
  `deps/opencc/deps/pybind11-*/tests/test_stl.cpp` 是 pybind11 测试文件

**代价值得记**:CMake 里曾有一段 `find boost/version.hpp` 的检查,于是三条 CI
流水线都在 `apt-get install libboost-dev`。那是**过时的检查**。除了慢,还有一条
「找不到 boost 头文件」的报错 —— 报错本身没错(确实找不到),但**指向 CMake 而不是
CI**,排查多绕一圈(实际栽在这上面一次)。

## `rime_api.h` 的三个形状

librime 1.x 公开的 C API **只有一个符号** `rime_get_api()`,返回 `RimeApi`
函数指针表。`RimeSetup` / `RimeCreateSession` 那些名字在 `rime_api_impl.h` 里是
`namespace rime` 下的 **C++ 符号**,JNI 层不能按 C 名字找。

1. `RimeTraits.data_size` **必填**,不给就是栈上垃圾。
2. `RimeContext.menu.candidates[]` **一次给全页**。用迭代器是每词一次 JNI 往返,
   一页 9 词就是 9 次跨语言边界 —— 没必要。
3. `get_context(session, &ctx)` **两参数** + `RimeContext` 用 `RIME_STRUCT_INIT`
   预置。清空用 `clear_composition()`。

## SCHEMA_ID 必须是 `luna_pinyin`,不是 `luna_pinyin._simp`

写错**不报错**:部署照跑、查询照跑,只是候选永远空。
这比崩掉更难查 —— 症状是「没候选」,而代码看起来完全正常。

## 每次查询以 `state.raw` 为准

`clear_composition()` + 逐字重喂,**不同步维护两份状态**。
两份状态迟早漂移,而漂移出来的 bug 极难定位(症状随机、复现不了)。

## 还没验证的

- **一次都没在真机上跑过**(`adb devices` 空)。librime 能否查出候选、
  翻译栈是否正常、分节行为 —— 全部未验证。
- R8 只有构建验证,没有运行验证。**JNI keep 规则写错就是 `UnsatisfiedLinkError`**,
  而 R8 现在默认开着。
- 引擎侧解码质量无 JVM 覆盖(那段在 C++)。
- 旧 `files/user_dict.json` 学习成果与 Rime 的 LevelDB **无转换路径**
  ⇒ 换引擎后用户已有学习成果会丢。这是 v0.7.0 标 breaking 的原因。


## Timeline

- time: 2026-10-03T11:24:13
  kind: decision
  summary: "Created this page: librime 交叉编译到 Android:四个坑与它不需要 boost"
  source: created via brain create-page
  affects: [librime-android-build]

- time: 2026-10-03T11:24:36
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 v0.7.0 换内核的实战记录(配方同源 adam-ikari/term-ime)"
  affects: [librime-android-build]
