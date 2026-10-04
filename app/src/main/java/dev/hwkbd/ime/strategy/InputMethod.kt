package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate

/**
 * 输入法策略:候选来源与用户词维护。新增输入法只需 [ImeRegistry.registerMethod] 注册,
 * 状态机调用的是接口,不再认识任何具体引擎。
 *
 * ## 为什么换引擎后这个接口几乎没变(2026-10-03)
 *
 * 换掉 [dev.hwkbd.ime.engine.PinyinEngine] 之前我以为要改这里 —— 结果没有。
 * 原因是接口当初就定得够窄:`query` 吃的是**累积串**而不是按键序列,
 * 于是「有状态还是无状态」这件事被挡在了实现里([RimeEngine] 内部自己重建 session 状态)。
 * 这是当初那条「状态机只认接口不认引擎」的设计兑现了。
 */
interface InputMethod {

    /** 唯一 id,[Language.methodId] 引用它。 */
    val id: String

    /** 按缓冲串取候选(上限 [limit])。 */
    fun query(raw: String, limit: Int): List<Candidate>

    /** 一词上屏后的词频反馈。 */
    fun onCommitted(word: String) {}

    /**
     * 长按候选删除该词的用户词条/频次。
     *
     * ## 返回值是 2026-10-03 加的
     *
     * 换成 librime 后这个操作**做不到**:它的用户词频在 LevelDB 里,没有删单个词的 API。
     * 原来签名是 `Unit`,调用方无从得知"没删掉",于是会**假装删了** ——
     * 用户点了删除,词还在,下次输出来又冒出来,比不做这个入口更糟。
     *
     * 所以改成返回 `Boolean`,调用方**必须**根据它决定是否告诉用户。
     */
    fun removeUserWord(word: String): Boolean = false

    /**
     * 该输入法**是否支持**删除单个用户词。
     *
     * 设置页用它决定入口是可用还是置灰。librime 不支持(见 [removeUserWord]),
     * 所以 [PinyinInputMethod] 给 false。
     */
    val supportsWordRemoval: Boolean get() = true

    /** 词的用户使用频次(测试/调试用)。 */
    fun userCount(word: String): Int = 0

    /**
     * 选中第 [index] 个候选(与 [query] 返回的顺序一致),返回
     * **「上屏文本」+「还没被吃掉的那部分输入」**。
     *
     * ## 为什么需要它(2026-10-04 用户「拼音的消费也要根据实际选词消费,不能一次清空」)
     *
     * 原来的选词路径是「拿 `query` 里的词 → 直接 `commitText` → 清空整个缓冲」。
     * 那是**假设一个候选吃掉全部拼音**。单音节下成立,多段输入下不成立:
     * 输入 `ni'hao` 选「你」之后,`hao` 被一起清掉 —— 用户已经打的那段白打。
     *
     * 而「这个候选吃了几段」是引擎内部的信息,拿不到 —— 所以要问引擎本身。
     *
     * ## 默认实现 = 旧行为(吃干净)
     *
     * 返回 `null` 表示「本引擎不参与选词」,调用方**照旧清空整个缓冲**。
     * 这样:
     * - 不支持的引擎([FakePinyinInputMethod] 之类)行为不变;
     * - librime 一次吃完整串时(单音节常态)也走这条路,行为与之前完全一致。
     *
     * 也就是说这是个**加法式**接口:猜错了也不会更坏。
     *
     * @param index 逻辑候选下标(**不是显示位** —— 显示位要自己经 [dev.hwkbd.ime.ime.ImeState.displayOrder] 换算)
     * @return (上屏文本, 剩余输入);`remaining` 为 null = 全部吃掉;
     *   整体返回 null = 本引擎不参与,调用方走旧路径
     */
    fun selectAndRemain(index: Int): Pair<String, String?>? = null
}
