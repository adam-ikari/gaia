---
id: librime-android-build
title: "librime 交叉编译到 Android:四个坑与它不需要 boost"
category: project
status: active
tags: [librime, android, ndk, cmake, jni, rime]
created: "2026-10-03T11:24:13"
updated: "2026-10-11T05:13:07"
---

<!-- compiled_truth -->
## 结论先行

v0.7.0 把拼音内核从自写的 `PinyinEngine`(查表 + 分节位)换成 **librime**,
直接复用 `adam-ikari/term-ime` 的构建配方。产物 `librime_jni.so`
(arm64-v8a + x86_64),**只依赖 `libdl` / `libm` / `libc`**,无 ICU 无 glog。

**不对比,直接替换** —— 用户明确要求,测试也不需要与以前一致。

## ⭐ 坑零:`page_size` 默认 5 ⇒ **翻页结构性失效**(E2E 才暴露)

这是换引擎带进来的**功能缺失**,而且是这一轮最重要的教训。

librime 的 `RimeContext.menu` 给的是**当前页**候选,最多 `page_size` 个。
schema 不写 `menu:` 段时,librime 默认 **`page_size = 5`**
(见 `librime/src/rime/schema.cc`,只兜 `page_size < 1`)。

而本项目的分页是**自己**做的:

```
ImeState.totalPages = ceil(candidates.size / pageSize)   // pageSize = 5
CandidateBarView.pageNextSlots = state.pageSlotsAt(page + 1)
```

于是 `candidates.size ≤ 5` ⇒ `totalPages` 恒等于 1 ⇒ `pageNextSlots` /
`pagePrevSlots` **恒空** ⇒ `takeOverPageDrag` 每次都走「到头,橡皮筋」分支。
**翻页在结构上就不可能发生。**

修法:schema 加 `menu: page_size: 45`,与 `ImeController.CANDIDATE_LIMIT`
一致,一次 `ctx.menu.candidates[]` 给全,`candidates` 就是完整列表,
现有 `totalPages` / `pageSlotsAt` 逻辑原样可用。

**为什么不用 `change_page` 逐页取**:本项目 `query()` 是**无状态**的
——每次以 `state.raw` 为准 `clear_composition()` + 逐字重喂。
翻 librime 的内部页码等于引入第二份状态,正是那条约定要避免的。

### 为什么这个 bug 躲过了所有静态检查

- **单测全绿**:`FakePinyinInputMethod` 要多少候选给多少,永远有第 2 页。
- **构建通过**:schema 是合法的 YAML,librime 也不校验 `page_size` 是否合理。
- **界面看着完全正常**:候选、飞字、空格上屏全对,**只有翻页不发生**。
- **E2E 的 page-flip 一开始红的是截图**,不是翻页 —— 所以没人怀疑它。

**教训:换引擎/换库时,要逐个核对「新库用自己方式表达的量」在新架构里
还成不成立。** `page_size` 在 librime 里是「一页几个」,在本项目里分页是自己
做的、librime 那个值就成了一个**没人注意的静默上限**。
单测替身掩盖了它 —— **替身越方便,接口契约的漏洞越藏得深**。

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
2. `RimeContext.menu.candidates[]` **一次给一页**(不是全部!见坑零),
   `page_size` / `is_last_page` 同在这个结构里。迭代器
   (`candidate_iterator_*`)在这份 fork 里**已被移除**,不存在。
3. `get_context(session, &ctx)` **两参数** + `RimeContext` 用 `RIME_STRUCT_INIT`
   预置。清空用 `clear_composition()`。

还有两处**静默截断**:JNI 里 `limit` 与硬顶 100。改一头不换另一头,
症状是「候选莫名少一截」而不是报错 —— 已补 WARN 日志。

## SCHEMA_ID 必须是 `luna_pinyin`,不是 `luna_pinyin._simp`

写错**不报错**:部署照跑、查询照跑,只是候选永远空。
这比崩掉更难查 —— 症状是「没候选」,而代码看起来完全正常。

## 每次查询以 `state.raw` 为准

`clear_composition()` + 逐字重喂,**不同步维护两份状态**。
两份状态迟早漂移,而漂移出来的 bug 极难定位(症状随机、复现不了)。

## 还没验证的

- **一次都没在真机上跑过**(`adb devices` 空)。模拟器上已验证:librime 查出候选、
  JNI 加载、R8 未炸(debug 包;**release 包的 R8 仍未运行验证**,
  keep 规则写错就是 `UnsatisfiedLinkError`)。
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

- time: 2026-10-03T14:00:16
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-03 E2E 实测:page_size 默认 5 ⇒ 翻页结构性失效"
  affects: [librime-android-build]

- time: 2026-10-04T16:50:00
  kind: decision
  summary: "拼音消费按实际选词消费:靠选完后反查 composition.preedit 拿剩余;librime 的 RimeCandidate 不暴露覆盖范围"
  source: "2026-10-04 用户「拼音的消费也要根据实际选词消费,不能一次清空」"
  affects: [librime-android-build, sensitivity-tiers]

- time: 2026-10-05T00:25:24
  kind: decision
  summary: "输出简体要同时过三关:schema 开关 reset:1、OpenCC 转换表进 assets、install() 真的比对内容"
  source: "2026-10-04 用户「自從換過輸入法一直是繁體中文」"
  affects: [librime-android-build]

- time: 2026-10-05T00:25:24
  kind: reversal
  summary: "「按实际选词消费」的初版实现从未生效:select_candidate 之后 get_commit 永远是 null"
  source: "2026-10-04 读 librime 源码发现 OnSelect 在段尾走 Forward() 而非 Commit(),commit 队列里什么都没有"
  affects: [librime-android-build]

- time: 2026-10-09T15:07:29
  kind: decision
  summary: "2026-10-09 引擎仓从 adam-ikari/librime 改为 adam-ikari/librime-stl(引擎+词库同仓的 monorepo fork)。子模块 .gitmodules URL 换掉,pin bdbd5764。engine src/ 与 dict 数据在 c32d2e9e..bdbd5764 无变化,编译产物不变。词库随 engine 同 tag 发布,不再有『新词库配旧引擎』。"
  affects: [librime-android-build]

- time: 2026-10-10T08:48:49
  kind: evidence
  summary: "CMakeLists.txt 两处检查 opencc/Opencc.h(大写),而 opencc submodule 里实际是 opencc.h(小写)。Linux 区分大小写 ⇒ **任何全新构建目录 configure 必挂**;靠复用旧 .cxx 缓存才侥幸通过(一次 CI 从没编过的新版因此暴露)。这检查是 9d740aa(10-08)引入的。2026-10-10 发 v0.7.11 时全新建 worktree 编 release 才撞到,修成小写。"
  source: "2026-10-10 tag worktree 全新 configure 报 opencc 头文件没进 stage"
  affects: [librime-android-build]

- time: 2026-10-11T05:13:07
  kind: decision
  summary: "2026-10-11 更新 librime 两件一起做(用户「更新librime」):①子模块 bdbd576 → d6006f73(fork 主线 8 提交:并入上游 67 提交、regex 走 PCRE 翻译层、插件模块注册从 Boost.PP 编译定义改为 configure 期生成 rime_extra_modules.h);②词典资产同步 —— 应用的 assets/rime/{luna_pinyin.dict.yaml,essay.txt} 此前冻结在 64eda4c7,本次覆盖为 fork dict/ 当前状态(简体化 3fbacf6c + 错音订正 fa3744e3 + 文言读音清理 d6006f73)。同步边界:opencc/ 与 luna_pinyin.schema.yaml 是应用自己维护的,不随子模块走。验证(无真机):NDK 配方编过、JNI 15/15、单测全过、rimeprobe 探针对同一组查询在引擎更新前后逐字节一致、词典同步后 shenme→什么/sheme→色么(文言行已删)/排序随新 essay 变化、consumed 与分段结构不变。dev APK 10699195 → 11633451(+0.93MB,essay raw +2.05MB 但 APK 压缩存储)。"
  source: "2026-10-11 用户要求更新 librime,范围经确认含词典"
  affects: [librime-android-build]
