package dev.hwkbd.ime.strategy

// [PinyinConsumption] 2026-10-06 从 dev.hwkbd.ime.rime 搬到了 strategy 包(同包,无需 import)
import dev.hwkbd.ime.engine.Candidate

/**
 * 拼音输入法策略:把引擎包一层,只负责**查询前归一**。
 *
 * ## 为什么引擎是构造参数而不是硬编码
 *
 * 换 librime 之前这里硬 `PinyinEngine(...)`。换引擎时测试全要重写,因为
 * [dev.hwkbd.ime.engine.PinyinEngine] 是具体类、不能在 JVM 上替换。
 * 现在收窄到 [InputMethod] 之后:
 * - 生产用 [dev.hwkbd.ime.rime.RimeEngine](librime,native);
 * - 测试用 [FakePinyinInputMethod](查表)。
 *
 * 策略层只做归一这件事,本身与引擎实现无关 —— 这是当初把 [InputMethod] 定得够窄的兑现。
 */
class PinyinInputMethod(private val engine: InputMethod) : InputMethod {

    override val id: String get() = engine.id

    override fun query(raw: String, limit: Int): List<Candidate> =
        engine.query(normalize(raw), limit)

    /** 词已上屏。转发给引擎(旧引擎要 `user.bump`,librime 自己会学)。 */
    override fun onCommitted(word: String) = engine.onCommitted(word)

    override val supportsWordRemoval: Boolean get() = engine.supportsWordRemoval

    /** 转发,但**结果以引擎为准** —— 换成不支持删除的引擎就如实返回 false。 */
    override fun removeUserWord(word: String): Boolean = engine.removeUserWord(word)

    override fun userCount(word: String): Int = engine.userCount(word)

    override fun selectAndRemain(index: Int, selectedWord: String): PinyinConsumption? =
        engine.selectAndRemain(index, selectedWord)

    /**
     * 转发,别用接口默认值。
     *
     * ## 这里踩过一次:漏转发 ⇒ 整道就绪门形同虚设
     *
     * 生产注册的是 `ImeRegistry.builtin().registerMethod(PinyinInputMethod(rime))`
     * —— **外面套着这一层装饰器**,不是裸引擎。
     * 而接口默认 `isReady = true`,所以漏转发时状态机永远看到「已就绪」,
     * `refreshQuery` 的就绪门一次都不会触发,候选行照旧「清空↔填满」地闪。
     *
     * 单测当时用的是**裸** `FakePinyinInputMethod` 注册,所以测不到这一层 ——
     * 生产代码里的 bug 反而是绿的。
     *
     * ⇒ 教训:给 [InputMethod] 加成员时,记得**两处**都要动 ——
     * 接口默认值 + 这个装饰器的转发。改完把装饰器也测上(见 `PinyinInputMethodTest`)。
     */
    override val isReady: Boolean get() = engine.isReady

    companion object {
        /** 测试注入点:用任意 [InputMethod] 构造策略。生产代码不用这个。 */
        internal fun forTest(engine: InputMethod) = PinyinInputMethod(engine)

        /**
         * 查询前归一:滤掉字母与 `'` 以外的字符、连续 `'` 并成一个、丢掉开头的 `'`
         * (开头没有前一个音节,分节位无意义)。**末尾 `'` 保留** —— 它表示"本音节到此为止"。
         *
         * 即使 librime 自己不认识 `'`,归一仍必须做:
         * 归一后的串既喂引擎也进上层 `state.raw`,两边要一致;
         * 而且分节意图要保留，将来接带分节规则的方案时不用重写这一层。
         */
        internal fun normalize(raw: String): String =
            raw.filter { it in 'a'..'z' || it == '\'' }
                .replace(Regex("'+"), "'")
                .trimStart('\'')
    }
}
