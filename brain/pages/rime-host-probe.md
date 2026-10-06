---
id: rime-host-probe
title: "librime 宿主机探针:没有设备时唯一能拿到运行时证据的路"
category: reference
status: active
created: "2026-10-06T10:05:53"
updated: "2026-10-06T10:06:16"
---

<!-- compiled_truth -->
## 它是什么

一个在**宿主机**上把 vendored librime 编起来跑的探针,回答关于
**分段 / 选词 / 候选**的问题。它不是单元测试替身 —— 跑的是真 librime、
真 `luna_pinyin.schema.yaml`、真词典,与真机那条路径同一份代码。

    tools/rimeprobe/run.sh nihao "ni'hao" xian

## 为什么必须有它(这是它存在的理由,不是锦上添花)

这个项目的**运行时证据通道曾经是全断的**:

- 用户那台设备没有 adb、给不了 logcat(2026-10-06 用户明说);
- 本机没有 `/dev/kvm`,模拟器起来 SystemUI 自己 ANR(实测,不是推测)。

于是只剩「读源码推导」一条路 —— 而它已经骗过人:
2026-10-06 我因为只读 `Context::Select` 的函数体,就把一个源码完全支持的
结论推翻并改了 brain 页标题。**读源码推导不够,但读源码+跑引擎就够了。**

librime 及其全部依赖都在 `deps/`(`yaml-cpp / leveldb / marisa-trie / opencc / glog`),
**所以它能在宿主机上编起来**。这不是新发现,是 2026-10-06 才发现的既成事实。

## 建它的三个坑

1. **`GetSession` 返回 `shared_ptr`,不是裸指针**;
   `Session` 定义在 `service.h` 里(没有 `session.h` 这个文件)。
2. **`RimeContext` 必须 `RIME_STRUCT_INIT`**(填 `data_size`)。
   `memset` 成 0 会被 librime 当成老版本结构体、后面的成员全部错位 ——
   **症状是「取得到 context,但 `menu.num_candidates` 恒为 0」**,
   看起来像「引擎没有候选」,其实是自己没初始化。
   `rime_jni.cpp` 的 `context_of()` 里有同样的注释,踩过一次。
3. 宿主机上**不需要** app 那套「主机版 + 目标版编两遍 opencc」的绕法 ——
   工具和库都是本机编的,天然能跑。

`ENABLE_LOGGING` 在宿主机上默认 **OFF**:本机 glog 头里没有
`google::IsGoogleLoggingInitialized`,开了编不过。分段轨迹目前靠探针自己
打印 composition 的每一段,不走 DLOG。

## 判据

**凡是要问「引擎在这之后是什么样」的问题,先跑探针,再改代码。**
读源码推出来的答案在这个项目已经错过一次,而它当时读起来完全合理。


## Timeline

- time: 2026-10-06T10:05:53
  kind: decision
  summary: "Created this page: librime 宿主机探针:没有设备时唯一能拿到运行时证据的路"
  source: created via brain create-page
  affects: [rime-host-probe]

- time: 2026-10-06T10:06:03
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "2026-10-06 建工具时踩的三个坑 + 它立刻查出的根因"
  affects: [rime-host-probe]

- time: 2026-10-06T10:06:16
  kind: evidence
  summary: "建它不到一小时就定位了两天没定位到的根因。另外它当场否掉了我三个判断:① 「方案只有两个 segmentor」是 rg -A12 截断造成的误判(实际七个,affix_segmentor@pinyin 在);② 「xian 出西安靠音节切分」错,那条路是 matcher 按整串查词典;③ 「空闲按 v 会 commit 'v'」至今未验,但探针能验 run.sh v 就能出答案 —— 这条仍挂着"
  source: "2026-10-06 用它查 hao 报障"
  affects: [rime-host-probe]
