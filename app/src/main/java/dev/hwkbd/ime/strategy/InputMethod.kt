package dev.hwkbd.ime.strategy

// 2026-10-06:[PinyinConsumption] 从 `dev.hwkbd.ime.rime` 搬到这里 ——
// 它是**接口的返回类型**,定义在实现层意味着「抽象层反向依赖实现层」。
// 同包的 [PinyinInputMethod] 因此也不用再 import。
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

    /**
     * **不重建**,直接读引擎此刻的菜单(上限 [limit])。
     *
     * ## 为什么需要它:选完候选之后不要重建
     *
     * [query] 是无状态的:`clear_composition()` + 逐字重喂 [raw]。按键路径上
     * 这样很干净 —— 候选恒等于 `raw` 的函数。
     *
     * 但**选完候选**这条路不一样:引擎刚 `Select(index)` 完,**它自己的菜单
     * 已经是「选完剩下的那部分」的答案**。此时若走 [query],我们把这个答案
     * 扔掉,拿自己拼出来的 `raw` 重建一遍 —— 而
     * 「`clear` + 重喂 S」**≡** 「引擎在任意状态下的菜单」这个等式从来没被验证过。
     *
     * 2026-10-06 用户报的就是它:`nihao` 选「你」之后缓冲里明明白白是 `hao`,
     * **候选栏一个词都没有**。而从零手打 `hao` 候选是有的 ⇒ 同一个 `raw`
     * 两个不同的候选栏 ⇒ 纯函数性破了,破在重建这一步。
     *
     * 所以这里直接读菜单。方向上不可能错:引擎说选完剩什么,它的菜单就是
     * 剩什么的候选;而且比重建**更便宜**(少一次全量重喂 + 跨语言往返)。
     *
     * ## 默认实现为什么是「退回 [query]」而不是空表
     *
     * 第一版这里返回 `emptyList()`,结果一条既有单测当场红了
     * (`只清候选实际吃掉的那部分 剩下的拼音留着`):`FakePinyinInputMethod`
     * 没重写它,拿到空表 ⇒ 选完候选后候选栏空。
     *
     * 而那正是**最坏的默认值形状**:
     *
     * - 默认 `emptyList()` ⇒ 「没实现」被静默渲染成「这个拼音没有候选」。
     *   症状与真 bug **完全一样**,查起来要从头找 —— 而根因在接口默认方法里。
     * - 默认 `query(pinyin, limit)` ⇒ 没重写的引擎行为与改动前**完全一致**
     *   (还是那条重建路径)。它可能不是最优,但它**不会静默坏掉**。
     *
     * ⇒ 判据:默认实现要退化到**旧行为**,不能退化到**空**。
     *   「空」是最像「对」的假答案。
     *
     * [pinyin] 是剩余缓冲,**只用于填 [Candidate] 供显示与埋点**,
     * 不参与查询(查询已经在引擎里做完了)。
     */
    fun currentCandidates(pinyin: String, limit: Int): List<Candidate> = query(pinyin, limit)

    /**
     * 菜单第一项(候选栏中央槽)候选的**音节拼写**与覆盖区间;引擎不支持 → `null`。
     *
     * 2026-10-12 用户「拼音显示需要自动加入分隔符,以当前候选词栏中央的最高优先级
     * 候选词为准」:拼音显示(preedit)跟随首选候选的切音节自动插入 `'`,
     * 而不是等用户手打 —— 手打分节符在这台设备上几乎不可达(Alt+L 被五键选字吃掉)。
     *
     * ## 默认值为什么是 null 而不是空串
     *
     * 与 [selectAndRemain] 同一条规矩:**加法式接口,默认退化成旧行为**。
     * `null` = 「本引擎说不清」,调用方照旧显示原始拼写(没有分隔符,和改动前一致);
     * 空串会被当成「首选没有音节拼写」—— 那也是同一个显示结果,但分不清
     * 「引擎不支持」与「这一页真的没有」,排查时多一道坎。
     *
     * 调用时机:紧跟 [query] / [currentCandidates] —— 它读的是引擎**此刻**的菜单,
     * 与候选列表同源,晚一步就是两份不同的状态。
     */
    fun topSpelling(): SyllableSpelling? = null

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
     * 引擎**是否已就绪**、能不能给出有意义的候选。
     *
     * ## 为什么必须能区分「没就绪」与「没候选」(2026-10-04 用户报候选行闪烁)
     *
     * 换 librime 之后词库要在**首次启动时异步部署**(4.6MB → `.ocd2` + LevelDB,
     * 实测约 3.6 秒)。那段时间里 [query] 只能返回**空表** ——
     * 而「空表」和「这个拼音真的没有候选」在调用方看来**完全一样**。
     *
     * 于是按键时候选行先被清空(n=0),几秒后部署完成的回调再填满(n=45):
     * 用户看到的就是**候选行自己交替闪烁**。埋点实测就是这样:
     *     query#1 from=onKeyDown           raw="n" n=0
     *     query#2 from=onCreate$1$1.invoke raw="n" n=45
     *
     * 默认 `true`:不实现这一项的引擎(或测试替身)行为与以前完全一样。
     */
    val isReady: Boolean get() = true

    /**
     * 选中第 [index] 个候选(与 [query] 返回的顺序一致),返回**消费结果**。
     *
     * ## 为什么需要它(2026-10-04 用户「拼音的消费也要根据实际选词消费,不能一次清空」)
     *
     * 原来的选词路径是「拿 `query` 里的词 → 直接 `commitText` → 清空整个缓冲」。
     * 那是**假设一个候选吃掉全部拼音**。单音节下成立,多段输入下不成立:
     * 输入 `nihao` 选「你」(它只覆盖 `ni`)之后,`hao` 被一起清掉 —— 用户已经打的那段白打。
     *
     * ## [index] 是**全局**下标,不是页内下标
     *
     * 候选栏按 [dev.hwkbd.ime.ime.ImeState.pageSize] 分页显示,
     * 而引擎手里的菜单是**一整份**。翻到第 2 页选第 0 槽时,
     * 页内下标是 0、**全局下标是 `page * pageSize + 0`** —— 传错的话引擎选的是
     * 第 0 页那个候选,报回来的「吃掉了几个字符」属于另一个词,缓冲就此错位。
     * 上屏的字不受影响(用的是调用方自己那份),所以症状是**选完缓冲不对**,
     * 而且**只在翻过页之后出现**,第 0 页一切正常。
     *
     * ## [consumed] 必须是引擎给的**输入**长度,不能由调用方猜
     *
     * 曾经的做法是「拿 `commit_text_preview` 减去刚选中的词,剩下的当剩余拼音」。
     * **那是错的**(2026-10-05 推翻,见 [dev.hwkbd.ime.rime.PinyinConsumption]):
     * `commit_text_preview` 是「按回车会提交什么」的预览,**每个段都填候选文字**,
     * 连没被选中的段也是 —— 剩下那部分是**汉字**,拿它的长度当拼音长度必然错,
     * 而且单音节时恰好是对的,所以一路全绿。
     *
     * ## 默认实现 = 旧行为(吃干净)
     *
     * 返回 `null` 表示「本引擎不参与选词」,调用方**照旧清空整个缓冲**。
     * 不支持的引擎([FakePinyinInputMethod] 之类)行为不变 ——
     * 也就是说这是个**加法式**接口:猜错了也不会更坏。
     *
     * @param selectedWord 上层**已经决定上屏**的那个词。**只用于埋点** ——
     *   消费长度不再靠减法猜,所以它不参与任何判定。
     * @return 消费结果;`null` = 本引擎不参与选词,调用方**走旧路径**(清空整个缓冲)
     */
    fun selectAndRemain(index: Int, selectedWord: String): PinyinConsumption? = null
}

/**
 * 一次选词之后,引擎报回来的**消费事实**。
 *
 * ## `consumed == 0` 是一个**答案**,不是「不知道」
 *
 * 2026-10-06 之前的写法是把 `<= 0` 一律折成 `null`,注释里写着
 * 「两者上层动作相同,不必区分」—— 动作确实相同,但**含义完全不同**:
 *
 * | `consumed` | 事实 | 上层该做什么 |
 * | --- | --- | --- |
 * | `> 0` | 吃掉这么多,剩尾巴 | 截断缓冲、接着组词 |
 * | `== 0` | **整个输入都被吃掉了** | 清空缓冲 |
 * | `null` | 引擎不参与 / 没选成 | 清空缓冲(保守) |
 *
 * 把 0 折进 `null` 的代价是**埋点会说谎**:日志里 `consumed=0` 原本表示
 * 「全吃掉」,折掉之后它与「没选成」无法区分。
 * 而这两种在真机上出现频率差得很远(全吃掉是常态:首选候选通常覆盖整串),
 * 一旦混在一起,「为什么这次没留下剩余」这种问题就再也查不出答案。
 *
 * 依据(宿主机探针实测,`tools/rimeprobe/run.sh nihao`):
 *
 * ```
 * 选「你好」(覆盖整串) → consumed=0,composition 被清空
 * 选「你」  (只覆盖 ni) → consumed=2,剩下 [2,5) 与它的 45 个候选
 * ```
 *
 * @param consumed 这个候选**吃掉了几个输入字符**(引擎坐标:不含 `'`)。
 *   `0` = 整串吃干净。上层拿它把 `state.raw` 截断 —— 截的是**前缀**,
 *   因为 librime 的候选永远从当前段起点开始吃(见 `rawAfterConsuming`)。
 * @param preview librime 的 `commit_text_preview` 整串。**只作埋点**:
 *   它是「按回车会提交什么」的预览,不是「哪些输入被吃掉了」的记录
 *   (它每段都填候选文字,包括没被选中的那段)—— 拿来算消费会算错。
 */
data class PinyinConsumption(
    val consumed: Int,
    val preview: String,
)

/**
 * 菜单第一项(候选栏中央槽)候选的**音节拼写**与它覆盖的输入区间。
 *
 * @property text 音节拼写,`'` 分节(如 `xi'an`)。引擎给的是空格分节
 *   (`ScriptSyllabifier::GetPreeditString` 取 schema delimiter 首字符),
 *   换成项目规范的 `'` 是 rime 层 `topSpelling()` 做的事。
 * @property start / end 候选覆盖的输入区间,**引擎输入坐标、不含撇号**
 *   (引擎重建时 `'` 不喂,见 `RimeEngine.query`)。拼回 raw 显示要数非撇字符,
 *   见 `ImeController.displaySpelling`。
 */
data class SyllableSpelling(
    val text: String,
    val start: Int,
    val end: Int,
)
