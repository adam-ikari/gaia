<template>
  <figure class="gaia-kcmap">
    <!--
      一张**键位表**,不是键盘的照片。大字是这一层打出来的东西,小字是键帽上印的字母。

      为什么敢画这个而不敢画演示件(`a1ee77f` 删掉的那四个):这张表的每一个格子都有出处
      —— 出厂键位在 `app/src/main/java/dev/hwkbd/ime/keymap/KeyLayout.kt` 的 `default()`,
      下面的数据是逐条从那里抄的(2026-10-30)。它陈述的是「按 Alt 再按 Q 出 0」这个映射,
      不演示任何手感。

      反过来,它必须**不像**键盘:不画外壳、不做键井、不做 3D、不加阴影。
      那四样合起来就是被删掉的 `KeyboardStack`,而它的 3D 投影还把 375px 的手机
      撑成 559px 的布局视口(见 brain 的 site-mobile)。
    -->
    <div class="kc-rows">
      <div v-for="(row, ri) in rows" :key="ri" class="kc-row" :class="`kc-row-${ri}`">
        <span v-for="k in row" :key="k.key" class="kc-cap">
          <b class="kc-out">{{ k.out }}</b>
          <i class="kc-print">{{ k.key }}</i>
        </span>
      </div>
    </div>
    <figcaption>{{ note }}</figcaption>
  </figure>
</template>

<script setup lang="ts">
/**
 * 数据源:`KeyLayout.kt` 的 `default()` 里 `l2` / `l3` 两个列表。
 * 功能键在代码里是 `key:xxx`,`KeyNames` 给的名字;页面上要写成键帽上看得懂的样子。
 */
type Cap = { key: string; out: string }

/** 物理键的三排(第 2 排右移一格、第 3 排右移两格,是真键盘的错位)。 */
const ROWS: string[][] = [
  ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
  ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
  ['z', 'x', 'c', 'v', 'b', 'n', 'm'],
]

const L2: Record<string, string> = {
  q: '0', w: '1', e: '2', r: '3', t: '(', y: ')', u: '-', i: '_', o: '/', p: ':',
  a: '@', s: '4', d: '5', f: '6', g: '*', h: '#', j: '+', k: '"', l: "'",
  z: '!', x: '7', c: '8', v: '9', b: '.', n: ',', m: '?',
}

/** 代码里 `key:xxx` 在页面上的写法(与键帽上印的功能标记一致)。 */
const FUN: Record<string, string> = {
  'key:esc': 'Esc', 'key:up': '↑', 'key:down': '↓', 'key:left': '←', 'key:right': '→',
  'key:pgup': 'PgUp', 'key:pgdn': 'PgDn', 'key:tab': 'Tab', 'key:move_home': 'Home',
  'key:move_end': 'End', 'key:insert': 'Ins',
}

const L3: Record<string, string> = {
  q: 'key:esc', w: 'key:up', e: '~', r: '$', t: '<', y: '>', u: '^', i: 'key:pgup', o: 'key:pgdn', p: 'key:tab',
  a: 'key:left', s: 'key:down', d: 'key:right', f: '%', g: '[', h: ']', j: '`', k: 'key:move_home', l: 'key:move_end',
  z: '\\', x: '&', c: '|', v: '{', b: '}', n: '=', m: 'key:insert',
}

const props = defineProps<{
  layer: 'L2' | 'L3'
  /** 图下那一行说明。两层的来历不同,说法必须分开写(见下)。 */
  note: string
}>()

const table = props.layer === 'L2' ? L2 : L3
const rows: Cap[][] = ROWS.map((row) =>
  row.map((k) => ({ key: k.toUpperCase(), out: FUN[table[k]] ?? table[k] }))
)
</script>

<style scoped>
.gaia-kcmap {
  margin: 0;
}

.kc-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kc-row {
  display: flex;
  gap: 8px;
}

/* 第 2、3 排的错位:真键盘上手指本来就往右偏,图里跟着偏,读的人才对得上键帽。 */
.kc-row-1 {
  padding-left: 5%;
}

.kc-row-2 {
  padding-left: 10%;
}

.kc-cap {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  padding: 7px 2px;
  border: 1px solid var(--gaia-cap-line);
  border-radius: 7px;
  background: var(--gaia-cap-bg);
}

.kc-out {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--gaia-cap-out);
  /* 一个格子只装得下一个字符宽的东西:PgUp 这种就让它缩进去,不许撑破格子 */
  max-width: 100%;
  overflow: hidden;
  text-overflow: clip;
  white-space: nowrap;
}

.kc-print {
  font-style: normal;
  font-size: 12px;
  letter-spacing: 0.02em;
  color: var(--gaia-cap-print);
}

.gaia-kcmap figcaption {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--vp-c-divider);
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
  text-align: left;
}

/*
 * ≤430px **换形态，不缩字号**。
 *
 * 十列排到 335px 的带上，每格只剩 26px：`PgUp`、`Home` 会被切掉半截，
 * 而一个被切掉的符号没人看得出来它被切了 —— 那就等于这张表在窄屏上是错的。
 * 于是窄屏不再摆键盘的形状，改成 `Q → 0` 的横排标签，一行排不下就自己换行。
 * 排布换了，26 条映射一条没少（这是「窄屏换形态，不是缩数」那条规矩的来源）。
 *
 * 576dp 的 Titan 2 不在这档里，它吃的还是十列。
 */
@media (max-width: 430px) {
  .kc-rows {
    gap: 6px;
  }

  .kc-row {
    flex-wrap: wrap;
    gap: 6px;
    padding-left: 0;
  }

  .kc-cap {
    flex: 0 1 auto;
    flex-direction: row;
    align-items: baseline;
    gap: 0;
    padding: 6px 11px;
    border-radius: 980px;
  }

  .kc-out {
    order: 2;
    font-size: 15px;
    overflow: visible;
  }

  .kc-print {
    order: 1;
  }

  .kc-print::after {
    content: '→';
    margin-left: 6px;
    color: var(--gaia-cap-print);
  }
}
</style>
