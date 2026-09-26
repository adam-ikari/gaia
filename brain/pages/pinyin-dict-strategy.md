---
id: pinyin-dict-strategy
title: "拼音词库生成与导入策略"
category: decision
status: active
tags: [pinyin, dict]
created: "2026-09-23T04:27:03"
updated: "2026-09-23T04:29:42"
---

<!-- compiled_truth -->
自带"中等词库"，并提供导入通道扩容（用户已确认该路线）。

## 内置词库（生成管线，可复现）

`tools/dictgen/` 下的 Python 脚本在开发机上生成 `app/src/main/assets/dict.txt.gz`：

- 词与词频来源：`fxsjy/jieba` 的 `dict.txt`（MIT）——约 35 万词条含词频。
- 读音来源：`pypinyin`，**短语级**取读音以消除多音字（"银行"hang、"行走"xing）；单字条目把所有异读音各存一条（次读音词频打折），避免"行"只能打 xing。
- 归一化：`ü` → `v`；`ü` 跟在 `j q x y` 后时记作 `u`（与用户输入习惯一致）；拼音全小写、音节直接相连、无分隔符。
- 过滤：保留 1–6 字词条，按词频取头部，目标规模 **10–30 万条**（可可为 29 万）。
- 输出：gzip 压缩 TSV，每行 `拼音<TAB>词<TAB>词频`，**按拼音字典序排序，同拼音内按词频降序**——这让运行时只需二分下界 + `startsWith` 扫描即可完成前缀查询。

## 运行时

- 首次输入前在后台线程解压载入内存（约 3–6 MB 文本），建"拼音 → 行区间"索引；不做磁盘随机读，避免输入法掉帧。
- 用户词频：`filesDir/user_dict.json`，上屏时加权，停止输入时落盘；支持长按候选删除（可可同款）。
- 不申请网络权限，词库全部本地。

## 导入格式

设置页支持导入纯文本/gzip：

```
词<TAB>拼音<TAB>词频     # 首选
词<TAB>拼音              # 词频默认 1
```

拼音缺省的行会被拒绝并给出明确提示（输入法无法在不带读音时安全回填多音字）。导入前自动备份原文件，可一键回退。深蓝词库转换等外部工具可先转成上述格式。

## 时间线

- time: 2026-09-23T04:34:00
  kind: decision
  summary: 词库改为"jieba 词频 + pypinyin 短语读音"离线生成 gzip TSV，10–30 万条，运行时全内存前缀二分
  source: 用户选择"自带中等词库+支持导入"；可可基于老版 LatinIME 二进制格式的教训（作者自述坑多）
  affects: []


## Timeline

- time: 2026-09-23T04:27:03
  kind: decision
  summary: "Created this page: 拼音词库生成与导入策略"
  source: "用户选择：自带中等词库+支持导入"
  affects: [pinyin-dict-strategy]

- time: 2026-09-23T04:29:42
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [pinyin-dict-strategy]
