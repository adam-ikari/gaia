---
id: chinese-ime-lm-survey
title: "chinese-ime-lm(metasequoiaime)调查:对本项目可用与不可用的分界"
category: reference
status: active
tags: [survey, license, eval]
created: "2026-10-23T08:07:48"
updated: "2026-10-23T08:08:07"
---

<!-- compiled_truth -->
- **它是什么**:`metasequoiaime/chinese-ime-lm`(Rust,仓库 GPL-3.0,2025-11 建,869 stars),面向中文输入法的语言模型工程,配套输入法是 MSIME。两条路线:①n-gram(语料清洗→KenLM→Viterbi),**从未有模型出自这条管线进过产品**(语料不可自由再分发,衍生统计的许可没解决);②字级 Transformer 重排器,**已发布权重**,HuggingFace `metasequoiaime/pinyin-ime-reranker-4M`(int8 4,486,280 字节)与 25M(25,480,184 字节)。训练语料只有 C4(ODC-BY)+LCCC(MIT)。
- **实测结论文档是整个仓的核心**(docs/measurements.md):重排**不是普遍有益**——词典整键精确命中(top-1 0.719,2052 例)上重排任何权重都掉分(最好 0.690),因为词典命中自带语料词频;只有解码器拼装、不带词频证据的候选才该让模型挑(37 例,0.000→0.189)。所以 API 带门控(`best_where`),要调用方替每个候选回答 `answers_key`(是否覆盖整个键)和 `trusted_dictionary_hit`(词典命中值不值得让位)两个只有引擎才知道的事实;两个近似按字数猜,在不纠错时成立、纠错时不成立。
- **评测集比模型更值钱**(eval/):`quanpin-words-v1.tsv` 25,119 条词级(从 Microsoft Windows-classic-samples 的 MIT 全拼示例词库固化),测词典命中与排序;`sentences-v1.tsv` 60 条手写整句,上限 0.929(52/60 金标准在候选池内)。整句集统计功效不够(1pp 差别要 1千–1万条),用处是抓住「重排把原本对的弄坏」这类定性回归。原方警告:**不要拿自己的词库出题**——等于让解码器考自己。
- **对本项目的适用分界**:我们走 librime,候选几乎全带词频(table/用户词典),按他们实测的口径,重排预期收益有限(推断,未量)。直接可用的是**评测集当外部尺子**:让我们的引擎对 quanpin-words-v1.tsv 出候选,量 top-1 命中率,作为词库/词频调整前后的固定基线(查证方法,未跑)。重排权重若要采用,阻塞在两个门控事实能否从 librime 的 C API 逐候选拿准(`answers_key` 尤其,preedit 在 RimeCandidate 里不直接给;查证,未验)。4.25M 的键盘预设(keyboard,6.85M 参数,int8 7,125,640 字节)形状就是按移动端定的,variant 未发布但算法可复算。
- **许可对本项目(闭源)的分界**:仓库整体 GPL-3.0 ⇒ `neural/`、`ngram/` 的代码不能抄;`reference/`(Rust 参考实现)单独 Apache-2.0 可用;**权重 Apache-2.0**,但语料署名必须随权重传播——写在 safetensors 头的 `attribution` 字段里,分发 APK 内嵌权重即需随包署名。评测集词级部分派生自 MIT 词库,无障碍。
- **工程要点**(若将来采用):标准 safetensors,无自定义容器,加载器照 format.md 任何语言能写;词表全是单字(无拼音字母 token)⇒ 模型不可能纠拼音,只挑汉字候选;int8 与 f16 排序全同,发布用 int8;跨按键前缀续算复用 61%–81.5% 已算字符,按键路径 p95 8.6ms(桌面 CPU;手机 CPU 必须重测);不展开 f32 保持 int8 驻留,4.5MB 文件 12.3MB 驻留。


## Timeline

- time: 2026-10-23T08:07:48
  kind: decision
  summary: "Created this page: chinese-ime-lm(metasequoiaime)调查:对本项目可用与不可用的分界"
  source: "2026-10-23 用户「调查Chinese-IME-lm」;gh api repos/metasequoiaime/chinese-ime-lm + 本地 README/docs 副本(/tmp/opencode/cim_*)"
  affects: [chinese-ime-lm-survey]

- time: 2026-10-23T08:08:07
  kind: decision
  summary: "调查结论:重排只在无词频证据的候选上有价值(带门控);对本项目最重要的是评测集当外部尺子,权重可用但受许可署名与 librime 门控事实可得性两条约束"
  source: brain update-truth
  affects: [chinese-ime-lm-survey]
