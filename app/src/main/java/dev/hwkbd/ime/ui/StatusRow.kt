package dev.hwkbd.ime.ui

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftMode
import dev.hwkbd.ime.keymap.Layer

/**
 * 状态行左区的**呈现判定**——纯函数,零 Android 依赖(所以能 JVM 单测)。
 *
 * **为什么是"状态"而不是"芯片"**(2026-09-30 用户定):左区那几颗小方块最早是按"芯片"的工业
 * 设计语言命名的(物理外观),但这一层关心的是"**输入法此刻处于什么状态**",与画成什么形状无关。
 * 叫 status 之后,规则读起来就是产品语言:「层与大小写互斥」「锁定态」「借用」,而不是排版术语。
 *
 * ## 为什么要有这一层(公理 A)
 *
 * 状态行是**状态的纯函数**:同一份状态永远该显示同样一排状态项,不看时间、不看手势进行到哪一步。
 * 之前这些规则(host 侧的 `renderStatus`)与视图操作写在一起——互斥、锁定下划线、样式统一,
 * 每一条都只能靠人眼在机上看,改动全靠手点验证。现在规则全在 [present] 里,
 * [ImeService] 只负责"把单子贴到 TextView 上",于是每一条规则都有回归测试(`StatusRowTest`)。
 *
 * ## 曾经有一条"暂定态"规则,已删(2026-09-30)
 *
 * 那条规则是:敲击窗口内的"一次性层/大写一次"先**藏起来**,等窗口结算时补画,为的是双击锁定
 * 别闪一下下划线。敲击判定统一到 keyUp 之后它变成了**纯副作用**——按住期间画出来的东西和
 * 敲击落定的结果**长得一模一样**(同一层、同样的锁定样式),藏它只会造成闪烁。真机报障:
 * 「在 alt 状态下长按 alt 会有 alt 消失 0.5s 左右」——本来亮着的状态项被藏 300ms 再亮回来。
 * 于是整条规则连同 `ModifierGestures.settled` 事件流与它的收集器一起删掉。
 *
 * 双击锁定时看到的是"下划线长出来那一下":那是锁定**真正成立**的时刻,是一次状态变化,
 * 不是闪一下又变回去。
 *
 * ## 唯一一条带产品判断的规则:大小写与层互斥
 *
 * 符号层上大小写根本不参与输出(`commitText` 的 `upper` 只在字母上起作用),两颗并排显示
 * 会让人以为 alt 层还能出大写——所以层生效时藏起大小写状态,回 L1 它自己回来,
 * **大写状态本身不清**(Caps 是持续态,只是这一层期间不显示)。
 */
object StatusRow {

    /** 状态项的种类(= 布局里那五个 TextView 的身份)。 */
    enum class Kind { MODE, FULLWIDTH, SHIFT, ALT, SYM }

    /** 一项画出来的状态:画什么字、什么颜色、是不是锁定态。 */
    data class Item(
        val kind: Kind,
        val label: String,
        val color: Int,
        val locked: Boolean,
        /** 无障碍朗读文本(仅大小写需要:三种大写态读法不同);null = 用标签本身。 */
        val contentDescription: String? = null,
    )

    /**
     * 呈现判定需要的**状态之外**的输入。全部是纯数据,由 [ImeService] 从策略/偏好里取好传进来
     * ——这样这个函数不必认识 `ImeController`,测试也不必造一个输入法。
     */
    data class Spec(
        /** 输入框表现:数字类判定与 Alt 那一项的文字(`0-9+*#`)都在这。 */
        val altLabel: String,
        val digitsOnly: Boolean,
        /** 当前语言 id(「全/半」开关只对拼音可见的判据用它,见 [present])。 */
        val languageId: String,
        /** 语言那一项的文字(`拼` / `En`)。 */
        val languageLabel: String,
        /** 当前语言是不是主语言(主语言=主色字,非主语言=前景色字)。 */
        val languageIsPrimary: Boolean,
        /** 该语言要不要显示大小写状态(拼音态不要——拼音没有大小写)。 */
        val showShift: Boolean,
        /** 当前是不是全角(开关标的是**当前状态**,不是"按了会变成什么")。 */
        val fullWidth: Boolean,
        val theme: ImeTheme,
    )

    /** 中文语言 id:「全/半」开关只对拼音可见(沿用 ImeService 里的同一判据)。 */
    const val PINYIN_ID = "zh"

    /**
     * 该显示哪几项,**按左区从左到右的顺序**返回(与 `ime_root.xml` 里的排列一致:
     * 语言 → 全/半 → 大写 → Alt → Sym)。没触发的状态不出现(只显示已触发的)。
     *
     * **只吃状态,不吃时间**:手势进行到哪一步(按住中/借用/锁定)都是一个真实状态,直接显示出来。
     */
    fun present(state: ImeState, spec: Spec): List<Item> {
        val t = spec.theme
        val out = ArrayList<Item>(3)
        val layer = state.effectiveLayer

        // 语言:恒显(数字类藏掉——数字框没有语言可切,锁 L2)
        if (!spec.digitsOnly) {
            out += Item(
                kind = Kind.MODE,
                label = spec.languageLabel,
                color = if (spec.languageIsPrimary) t.accent else t.fg,
                locked = false,
            )
        }

        // 「全/半」开关:硬件键盘上没有这个物理键,状态行是唯一入口(左区其余状态项不可触摸)。
        // 它是**按钮**不是状态:文字标的是当前状态,跟右区齿轮/收起同一套按钮语言。
        // **只在拼音态可见**:英文态没有全角概念,数字类绕过语言表,那两种情况下它都是按了
        // 没反应的死开关。判据放在这里(而不是让 service 算好传进来):规则与它的测试同处一地,
        // 不会有"service 算错一个条件、两处悄悄不一致"的空间。
        if (!spec.digitsOnly && spec.languageId == PINYIN_ID) {
            out += Item(
                kind = Kind.FULLWIDTH,
                label = if (spec.fullWidth) "全" else "半",
                color = if (spec.fullWidth) t.accent else t.fg,
                locked = false,
            )
        }

        // 大写:**英文态恒显**,用字形本身表达大小写状态(2026-09-30 用户定案
        // 「把 A 改为 ab / Ab / AB 的模式,不显示下划线锁定了」):
        //   `ab` 未大写 / `Ab` 大写一次(含物理 Shift 正按住)/ `AB` Caps 锁定。
        // 取代原先"一把 `A` + 锁定加下划线"——大小写这件事由**字形**说清,不用再叠一个标记,
        // 于是它与 alt/sym 的锁定下划线不再是同一套语言(它们仍用下划线,见下)。
        // **层生效时不显示**(与层状态互斥)。
        val caps = state.shiftMode == ShiftMode.CAPS
        if (spec.showShift && !spec.digitsOnly && layer == Layer.L1) {
            val once = state.shiftMode == ShiftMode.ONCE
            val held = state.shiftMode == ShiftMode.HELD
            val (label, color, desc) = when {
                caps -> Triple("AB", t.accent, CAPS_LABEL)
                // 按住中是前景色:大写来自物理修饰键,还没落成状态
                held -> Triple("Ab", t.fg, ONCE_LABEL)
                once -> Triple("Ab", t.accent, ONCE_LABEL)
                else -> Triple("ab", t.fg, OFF_LABEL)
            }
            out += Item(
                kind = Kind.SHIFT,
                label = label,
                color = color,
                // **不带下划线**:锁定与否由字形 AB/Ab 表达(用户 2026-09-30 定案)
                locked = false,
                contentDescription = desc,
            )
        }

        // 层状态:哪个生效显哪个(alt 与 sym 互斥,effectiveLayer 是单值)。
        // 数字框只认 Alt 那项(此时文字是实际可输字符集);锁定=下划线,借用/按住=无。
        if (layer == Layer.L2) {
            out += Item(Kind.ALT, spec.altLabel, t.statusL2, state.lockedLayer == Layer.L2)
        }
        if (!spec.digitsOnly && layer == Layer.L3) {
            out += Item(Kind.SYM, "sym", t.statusL3, state.lockedLayer == Layer.L3)
        }
        return out
    }

    /** 无障碍朗读文本(中文)。渲染层直接用,不必再带一套 R.string。 */
    const val CAPS_LABEL = "大写锁定"
    const val ONCE_LABEL = "大写一次"
    const val OFF_LABEL = "未大写"
}
