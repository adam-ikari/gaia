# 拼音引擎:librime

2026-10-03 起,拼音内核从自研 [PinyinEngine](旧,已删)换成 **librime**(`adam-ikari/librime`,
子模块 `app/src/main/cpp/librime`)。词库来自 `adam-ikari/term-ime-dict`。

## 为什么换

`term-ime`(TTY 中文拼音输入法)已经把 librime 在无桌面环境下的三处硬骨头解决了:
**librime 被改成用 `std::regex` 而非 `boost::regex`**(不需要交叉编译 boost_regex,也没有 ICU),
vendored deps 分两段编,leveldb 不带 snappy。直接复用它的构建配方。

## 架构:换的只有一层

```
ImeController ── InputMethod(接口)── PinyinInputMethod ── RimeEngine(实现)
     ↑                ↑                    ↑                    │
  状态机/手势      归一(去杂质)        只做归一          librime(native)
```

`InputMethod` 当初就定得够窄 —— `query(raw, limit)` 吃的是**累积串**而不是按键序列,
于是「librime 是有状态机」这件事被挡在实现里。`ImeController`、候选栏、飞字手势
**一行都没改**。

## 状态:每次查询都以 raw 为准重建

librime 的 session 是喂键驱动的状态机,上层的状态是 `state.raw`(累积拼音串)。两者会不同步
(退格、切语言、从九处入口选词都会让 librime 偏离)。

所以 `RimeEngine.query` 每次都:

```
clear_composition()          ← 丢掉 librime 内部状态
for (c in raw) process_key(c) ← 按 raw 重喂(跳过 `'`)
```

不去同步两份状态 —— 漏一处就是「界面显示 A、引擎算的是 B」。代价是每次查询 O(n) 重喂
(n ≤ 约 20 个字母,每个按键才走一次)。

## 只做简体

`schema_id` 固定 `luna_pinyin`(它本身即输出简体),**没有 schema 切换**。
`_simp` 后缀属于完整的 `rime-luna-pinyin` 包,而这里用的是 `luna_pinyin_simp` 之外的
`data/minimal/` + `term-ime-dict`。

> **写错的后果极隐蔽**:`schema_id` 不存在时 librime **不报错**,部署照跑,
> 只是没有 translator 生效 ⇒ 查询永远空。

`assets/rime/luna_pinyin.schema.yaml` 删掉了 `cangjie` / `cangjie_lookup` /
`reverse_lookup` 三段**及其 4 处引用**(engine.translators、recognizer 正则、
`default.yaml` 的 schema_list)。它们引用 `cangjie5` 词典而 assets 里没有,
只删块不够,librime 部署时找不到组件照样失败。改动原因写在文件头。

## 体积

| 内容 | 大小 |
| --- | --- |
| `librime_jni.so`(arm64-v8a,strip 后) | 3.5 MB |
| `assets/rime/`(dict 0.89MB + essay 3.8MB + schema) | 4.6 MB |

release APK 6.07 → **约 9.5 MB**。旧的那份 `assets/dict.txt.gz`(3.1MB)已删。

## 构建:三处 term-ime 不会遇到的坑

Android 交叉编译比原生编译多三道坎,都在 `app/src/main/cpp/CMakeLists.txt` 注释里:

1. **`ANDROID_ABI` 必须转发给独立子工程**。四个 deps 是独立 CMake 工程,ABI 从
   `ANDROID_ABI` 变量读(不是从 toolchain 文件读)。不转发就退回 armeabi-v7a,
   报错长得像"依赖坏了":`libyaml-cpp.a(...) is incompatible with aarch64linux`。
2. **`opencc_dict` 要编主机版**。install 阶段用它把 JSON 词典转成 `.ocd2`,
   但交叉编译出来的是 arm64 的,主机跑不了 → `Exec format error`。
   词典数据与架构无关,所以编两次:主机版只为拿到能跑的工具,目标版提供 `libopencc.a`。
3. **`opencc_dict` 不在 PATH**。它在 `<build>/src/tools/` 而不是 build 根下,
   而且**只在 install 那一步需要在 PATH**(configure 阶段一切正常)。

依赖只有 `libdl` / `libm` / `libc` —— boost 是纯头文件,无 ICU 无 glog。

## JNI:为什么走 `rime_get_api()`

librime 1.x 公开的 C API **只有一个符号** `rime_get_api()`,返回 `RimeApi` 函数指针表。
`RimeSetup` / `RimeCreateSession` 那些名字在 `rime_api_impl.h` 里是 `namespace rime`
下的 C++ 函数(符号形如 `_Z12RimeFinalizev`),不是 C 链接 —— 直接调连链接都过不去。

**表项必须判空**:函数指针表允许不同版本增删成员(`get_status` 就是后加的)。
`RimeNative.apiProbe()` 报告表里我们关心的几项有几个在位,给 E2E 日志一眼看出是不是版本对不上。

三个容易踩的形状(`rime_api.h`):

- `RimeTraits.data_size` **必填** —— 不填 `initialize` 会当成 v0.8 老结构体,后面字段全部错位
- `RimeContext` 里 `menu.candidates[]` + `num_candidates` 一次给全页;
  用迭代器(`candidate_list_begin/next`)是**每词一次 JNI 往返**,而这是每个按键都走的路径
- `get_context(session, &ctx)` 要两个参数,ctx 必须 `RIME_STRUCT_INIT` 初始化;
  清空用 `clear_composition()`,`ctx->clear()` 不存在

## 与旧引擎的差异(能力上的**减法**)

| 能力 | 旧 PinyinEngine | RimeEngine |
| --- | --- | --- |
| `CandidateKind`(精确/前缀/组词) | 三路 | **恒为 `EXACT`** —— librime API 只给 `text` |
| `score` | 档位加分 + 用户频次饱和曲线 | 恒为 0,打分在 Rime 内部 |
| 逐词删除用户词 | 可用 | **不可用**(LevelDB 无删单词 API) |
| 用户词频 | 应用自己的 JSON | Rime 自己维护(essay.txt + LevelDB) |
| 导出/导入用户词 | 可用 | **已撤掉**(格式是 librime 私有实现) |
| 分节位规则 | 词库键带撇 | 方案的 `alphabet` |

**用户已有学习成果会丢**:旧 `files/user_dict.json` 与 Rime 的 LevelDB 是两套东西,无转换路径。

`InputMethod` 加了 `supportsWordRemoval` 并把 `removeUserWord` 改成返回 `Boolean` ——
不支持就如实报告,UI 才能告诉用户。假装删了更糟(词还在,下次输出来又冒出来)。

## 测试缺口(重要)

**真的拼音解码质量现在没有 JVM 测试覆盖** —— 那段代码在 C++ 里。

原 `ImeControllerTest` / `PinyinInputMethodTest` / `ImeRegistryTest` 直接
`PinyinEngine(PinyinDict(...))` 真加载词典,换引擎后这条路断了。新引入
[FakePinyinInputMethod](查表 + 前缀 + 分节位)作为替身,让**状态机测试与引擎解耦**。

**测试全绿不代表输入法是对的** —— 能查出的候选质量、词频排序、模糊音,全部只能真机验。
