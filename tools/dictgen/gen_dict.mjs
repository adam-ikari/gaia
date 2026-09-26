// 生成内置拼音词库:tools/dictgen/jieba_dict.txt (fxsjy/jieba, MIT)
// → app/src/main/assets/dict.txt.gz
//
// 输出:每行 `拼音<TAB>词<TAB>词频`,按拼音字典序排序、同拼音内按词频降序,
// 运行时只需二分下界 + startsWith 扫描即可完成前缀查询。
//
// 规则(与 brain/pinyin-dict-strategy 一致):
// - 保留 1–6 字纯 CJK 词条,按词频取头部,上限 30 万条;
// - 短语级取读音以消歧多音字(银行→yinhang);单字的所有异读各存一条,次读音词频×0.3;
// - ü → v;j/q/x/y 后的 ü 本就写作 u;无声调、音节相连无分隔符。
import { readFileSync, writeFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { pinyin } from 'pinyin-pro'

const here = dirname(fileURLToPath(import.meta.url))
const SRC = join(here, 'jieba_dict.txt')
const OUT = join(here, '..', '..', 'app', 'src', 'main', 'assets', 'dict.txt.gz')
const MAX_ENTRIES = 300_000
const SECONDARY_FACTOR = 0.3

const isCjk = (ch) => {
  const c = ch.codePointAt(0)
  return (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf)
}

const opts = { toneType: 'none', type: 'array', v: true, nonum: true }

// 1. 读 jieba 词频,过滤 1–6 字纯 CJK 词
const words = [] // {word, freq}
for (const line of readFileSync(SRC, 'utf8').split('\n')) {
  const m = line.match(/^(\S+)\s+(\d+)/)
  if (!m) continue
  const word = m[1]
  const freq = Number(m[2])
  if (word.length < 1 || word.length > 6) continue
  if (![...word].every(isCjk)) continue
  words.push({ word, freq })
}
console.log(`jieba 词条(1-6 字 CJK): ${words.length}`)

// 2. 注音:多字词短语级一次;单字取全部异读
const rows = [] // {pinyin, word, freq}
let bad = 0
for (const { word, freq } of words) {
  if (word.length === 1) {
    const readings = pinyin(word, { ...opts, multiple: true })
    const uniq = [...new Set(readings)]
    uniq.forEach((py, i) => {
      if (/^[a-z]+$/.test(py)) {
        rows.push({ pinyin: py, word, freq: i === 0 ? freq : Math.round(freq * SECONDARY_FACTOR) })
      } else bad++
    })
  } else {
    const py = pinyin(word, opts).join('')
    if (/^[a-z]+$/.test(py)) rows.push({ pinyin: py, word, freq })
    else bad++
  }
}
console.log(`注音完成: ${rows.length} 行(无效 ${bad})`)

// 3. 同 (拼音,词) 去重取最大词频,再按词频取头部
const best = new Map()
for (const r of rows) {
  const k = r.pinyin + '\t' + r.word
  const prev = best.get(k)
  if (prev === undefined || r.freq > prev) best.set(k, r.freq)
}
let all = [...best].map(([k, freq]) => {
  const tab = k.indexOf('\t')
  return { pinyin: k.slice(0, tab), word: k.slice(tab + 1), freq }
})
console.log(`去重后: ${all.length}`)
if (all.length > MAX_ENTRIES) {
  all.sort((a, b) => b.freq - a.freq)
  all = all.slice(0, MAX_ENTRIES)
}

// 4. 按拼音字典序、同拼音词频降序 → gzip TSV
all.sort((a, b) => (a.pinyin < b.pinyin ? -1 : a.pinyin > b.pinyin ? 1 : b.freq - a.freq))
const tsv = all.map((r) => `${r.pinyin}\t${r.word}\t${r.freq}`).join('\n') + '\n'
writeFileSync(OUT, gzipSync(Buffer.from(tsv, 'utf8')))
console.log(`写出 ${OUT}: ${all.length} 条, ${(tsv.length / 1024 / 1024).toFixed(1)} MB 未压缩`)
