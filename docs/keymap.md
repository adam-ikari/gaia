# L1–L3 键位表

以代码为准，出处两个：

- **L1 / L2 / L3 的出厂值**：`app/src/main/java/dev/hwkbd/ime/keymap/KeyLayout.kt` 的 `default()`
  （L2 那张表注释里写明与厂商键帽 `.TitanKey.kcm` 一致 —— 2026-09-30 逐键比对，26 键全对）
- **中文全角态对 L3 的覆盖**：`strategy/ChineseLanguage.kt` 的 `L3_CN`

三层的分工：L1 字母，L2 符号数字（Alt 键），L3 编程符号（Sym 键）。L1-L3 敲不出的键才轮到状态行，见 [l4.md](l4.md)。

## 每张图怎么读

上面三行是这个键打出来的字，**下面三行是键帽上印的字母**，一一对齐。第 2 行右移一格、第 3 行右移两格，是键盘本来的错位，不是排版问题。

## L1 字母

`a`–`z`，由 `KEYCODE_A` 起连续生成。Shift 不是第四张表，是把 L1 的输出转大写 —— 单击一次、双击锁定、长按持续，判定在 `ModifierGestures`。

## L2 符号数字（按住 Alt）

数字 0-9 走九宫格（`Q`=0），标点集中在右手区；本机没有独立的标点键。

```text
L2 符号数字

0     1     2     3     (     )     -     _     /     :
      @     4     5     6     *     #     +     "     '
            !     7     8     9     .     ,     ?

Q     W     E     R     T     Y     U     I     O     P
      A     S     D     F     G     H     J     K     L
            Z     X     C     V     B     N     M
```

## L3 编程（按住 Sym）

成对括号是有意相邻的（`<` `>`、`[` `]`、`{` `}`），写代码时顺手。

```text
L3 编程

Esc   ↑     ~     $     <     >     ^     PgUp  PgDn  Tab
      ←     ↓     →     %     [     ]     `     Home  End
            \     &     |     {     }     =     Ins

Q     W     E     R     T     Y     U     I     O     P
      A     S     D     F     G     H     J     K     L
            Z     X     C     V     B     N     M
```

`Esc` `↑` `PgUp` `PgDn` `Tab` `←` `↓` `→` `Home` `End` `Ins` 这 11 个功能键位，中文全角态也原样不动。

## 中文全角态下的 L3（`ChineseLanguage.L3_CN`）

拼音全角时 R 位给 `￥` 而不是 `$` —— 同一个"钱"的位置，两态各给各的形态。其余 14 格换成中文习惯的全角写法与中文括号：

```text
L3 编程(中文全角)

同 Esc同 ↑  ～    ￥    《    》    ＾    同 PgUp同 PgDn同 Tab
      同 ←  同 ↓  同 →  ％    【    】    ｀    同 Home同 End
            、    ＆    ｜    ｛    ｝    ＝    同 Ins

Q     W     E     R     T     Y     U     I     O     P
      A     S     D     F     G     H     J     K     L
            Z     X     C     V     B     N     M
```

`《》【】` 因此回到物理键上，状态行不必再摆它们。

## 不在这三张表里的键

| 键 | 归谁 |
| --- |
| `Shift` | 修饰键，管大写 |
| `Alt` | 修饰键，管 L2 |
| `Sym` | 修饰键，管 L3 |
| `Fn` | 系统里指派给 `Ctrl`，五键选字与 `Ctrl` 组合都走它 |
| `Back` / `Recent` | 系统导航键，输入法不接管 |
| 空格 | 固定选首选，不参与层映射 |

## 改键位

设置 → 按键与层 → 恢复默认键位映射。键位以文本形式存着，可导出备份再导入，改完立即生效。
