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
}
