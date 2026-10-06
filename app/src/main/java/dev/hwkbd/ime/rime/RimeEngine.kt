package dev.hwkbd.ime.rime

import dev.hwkbd.ime.strategy.PinyinConsumption
import android.content.Context
import android.util.Log
import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.CandidateKind
import dev.hwkbd.ime.strategy.InputMethod
import java.io.File

/**
 * librime 引擎。**替换掉了原来的 [dev.hwkbd.ime.engine.PinyinEngine]**(2026-10-03 用户
 * 「不用对比 直接替换」)。
 *
 * ## 契约与旧引擎相同,上层零改动
 *
 * `query(raw, limit)` / `onCommitted(word)` / `removeUserWord(word)` / `userCount(word)`
 * —— 就是 [dev.hwkbd.ime.strategy.InputMethod] 需要的全部。所以
 * `ImeController`、`PinyinInputMethod`、候选栏、飞字手势**一行都没动**。
 *
 * ## 最大的差异:状态在哪
 *
 * 旧引擎**无状态**:`query(raw, limit)` 拿累积串去查词典,`raw` 是唯一真相源。
 *
 * librime **有状态** —— 喂键驱动内部状态机,退格/选词/切语言都会改它。所以本类
 * 每次查询都**以 `raw` 为准重建 session 状态**:
 *
 * ```
 * clear_composition()          ← 丢掉 librime 内部状态
 * for (c in raw) process_key(c) ← 按 raw 重喂
 * ```
 *
 * 为什么不反过来去同步两份状态:上层有退格、有切语言、有从九处入口选词,
 * 漏一处同步就是"界面显示 A、引擎算的是 B"。**以 [raw] 为准重建是唯一不会错的做法**,
 * 代价是每次查询 O(n) 重喂(n ≤ 约 20 个字母,且每个按键才走一次)。
 *
 * ## 三件拿不到的东西(不是 bug,是 librime 的 C API 边界)
 *
 * 1. **`CandidateKind` 全是 [CandidateKind.EXACT]。** API 只给候选的 `text`,
 *    不暴露「精确/前缀/组词」分类。旧引擎那套 kind 只用于**内部排序**,
 *    UI 不消费(全项目搜过,`CandidateKind.` 在 `PinyinEngine.kt` 之外零引用),
 *    所以丢掉不影响显示;打分已由 Rime 内部完成。
 * 2. **`score` 恒为 0。** 词频归 Rime 管。
 * 3. **`removeUserWord` / `userCount` 是空实现。** Rime 的用户词频走
 *    `essay.txt` + LevelDB,**没有删除单个词的 API**。所以设置页里
 *    「删除用户词」在换引擎后是无效的 —— 这条**不能假装能用**,
 *    设置页那边要如实显示成不可用(见 `SettingsActivity` 的处理)。
 *
 * ## 只做简体
 *
 * `schema_id` 固定 `luna_pinyin`(它本身即输出简体)。没有 schema 切换 ——
 * 留着就是个点了没反应的死开关。
 *
 * ## 线程
 *
 * librime 的 session 不是线程安全的:[query]/[select] 只在 IME 主线程调;
 * 部署([prepare])在后台线程,两者用 [lock] 互斥。
 */
class RimeEngine(private val context: Context) : InputMethod {

    override val id: String get() = "pinyin"

    @Volatile
    private var session: Long = 0
    private val lock = Any()

    val available: Boolean get() = RimeNative.available

    /** librime 版本,失败时给 [RimeNative.LOAD_FAILED]。 */
    val version: String get() = RimeNative.version()

    /** 部署是否完成。未完成时 [query] 一律返回空 —— 上层显示「无候选」而不是崩。 */
    @Volatile
    var ready: Boolean = false
        private set

    /**
     * [InputMethod.isReady]:词库部署完成、session 可用。
     *
     * 部署是异步的(首次约 3.6 秒),那之前 [query] 只能给空表 ——
     * 而空表与「这个拼音没候选」在调用方看来一样,于是候选行会先清空再填满(闪烁)。
     * 状态机靠这一项把两者分开。
     *
     * 与 [ready] 同一份状态,只是换个名字对外:内部叫「ready」,
     * 接口层面叫「isReady」是因为它回答的是「现在能不能问出有意义的候选」。
     */
    override val isReady: Boolean get() = ready

    /**
     * 初始化 + 部署。**必须在后台线程调**:首次部署要把 `*.yaml` 编成 `*.bin`,
     * 几秒到几十秒,放主线程会 ANR。
     *
     * @param onDone 回调在**后台线程**;UI 更新要自己切线程
     */
    fun prepare(onDone: (Boolean) -> Unit) {
        if (!available) {
            Log.w(TAG, "native 不可用: ${RimeNative.error}")
            onDone(false)
            return
        }
        val root = File(context.filesDir, "rime")
        val shared = File(root, "shared").apply { mkdirs() }
        val user = File(root, "user").apply { mkdirs() }
        val built = File(root, "build").apply { mkdirs() }

        // 词典必须**先**释放:initialize 之后 librime 才按 shared_data_dir 找 *.yaml,
        // 顺序反了就是「初始化成功但一个候选都没有」。
        RimeData.install(context, shared)

        val rc = RimeNative.initialize(
            user.absolutePath,
            shared.absolutePath,
            built.absolutePath,
            SCHEMA_ID,
            APP_NAME,
        )
        if (rc != 0) {
            Log.w(TAG, "initialize 失败 rc=$rc")
            onDone(false)
            return
        }
        Thread({
            RimeNative.joinMaintenance()
            val s = RimeNative.createSession()
            synchronized(lock) { session = s }
            ready = s != 0L
            Log.i(TAG, "就绪 version=${RimeNative.version()} schema=$SCHEMA_ID session=$s")
            onDone(ready)
        }, "rime-deploy").apply { isDaemon = true }.start()
    }

    /**
     * 查询候选。[raw] 是**累积的拼音串**(上层 `state.raw`,可含 `'` 分隔符)。
     *
     * 与旧引擎同名同义,内部先重建 session 状态再取候选,见类注释。
     */
    override fun query(raw: String, limit: Int): List<Candidate> {
        if (raw.isEmpty()) return emptyList()
        val s = synchronized(lock) { session }
        if (s == 0L) return emptyList()
        synchronized(lock) {
            // 以 raw 为准重建 librime 的内部状态
            RimeNative.reset(s)
            for (c in raw) {
                if (c == '\'') continue // 分节符是本项目自己的概念,librime 不认识
                RimeNative.processKey(s, c.code)
            }
        }
        return readMenu(s, raw, limit)
    }

    /**
     * **不重建**,直接读引擎此刻的菜单。选完候选之后用它,不要用 [query]。
     *
     * 理由见 [InputMethod.currentCandidates]。一句话:引擎刚 `Select` 完,
     * 它的菜单**已经是**「选完剩下那部分」的答案,而 [query] 会把这个答案
     * 扔掉、拿我们拼的字符串重建一遍 —— 那个重建与引擎原本的状态不必相同。
     *
     * [pinyin] 是**剩余缓冲**,由调用方([selectCandidate] 里 `rawAfterConsuming`
     * 的结果)给出:它只填进 [Candidate] 供显示与埋点,不参与查询 ——
     * 引擎侧的 composition 原文没有便宜的形式(`GetCommitText()` 是**预览**,
     * 见 [selectAndRemain] 的注释),而字���是我们自己维护的那份。
     */
    override fun currentCandidates(pinyin: String, limit: Int): List<Candidate> {
        val s = synchronized(lock) { session }
        if (s == 0L) return emptyList()
        synchronized(lock) { return readMenu(s, pinyin, limit) }
    }

    private fun readMenu(s: Long, pinyin: String, limit: Int): List<Candidate> {
        val words = RimeNative.candidates(s, limit)
        if (words.isEmpty()) return emptyList()
        return words.map {
            // kind/score 见类注释:librime 不暴露,统一填 EXACT / 0。
            Candidate(word = it, pinyin = pinyin, kind = CandidateKind.EXACT, score = 0L)
        }
    }

    /**
     * [InputMethod.selectAndRemain] 的实现:选中第 [index] 个候选。
     *
     * @return 这个候选**吃掉了几个输入字符**,以及 librime 对整串的预览(埋点用)。
     *   选不中 / 说不出吃掉了多少 ⇒ `null`,调用方走旧路径。
     *
     * ## 「吃掉几个字符」必须由引擎给,不能自己算
     *
     * 上一版是「拿 `commit_text_preview` 减去刚选中的那个词,剩下的当剩余拼音」。
     * **那个模型是错的**,而且错得没有征兆 —— 2026-10-05 真机日志实测:
     *
     * ```
     * raw="nihao"  选第 4 个候选「你」(它只覆盖 "ni")
     * consume: preview="你好" word="你"
     * ⇒ 剥掉「你」剩下「好」,把「好」的长度 1 当拼音长度
     * ⇒ 缓冲从 "nihao" 变成 "n"(应该是 "hao")
     * ```
     *
     * 错在 `Composition::GetCommitText()`:**每个段都填候选文字**,连没被选中的段
     * 也是(它填的是那段当前高亮的候选)。所以 preview 里「没被吃掉的那部分」
     * 是**汉字**,不是拼音 —— 拿它的长度当拼音长度,截出来的缓冲当然是错的。
     * 单音节时答案恰好是对的,所以一路全绿。
     *
     * 现在直接问引擎要长度(`GetCurrentStartPosition()`,见 `rime_jni.cpp`),
     * 那是 composition 里「还没被吃掉的那段」的起点,天然是**输入**坐标。
     *
     * ## [selectedWord] 现在只用于埋点
     *
     * 它不再参与任何判定 —— 消费长度不再靠减法猜。
     */
    override fun selectAndRemain(index: Int, selectedWord: String): PinyinConsumption? {
        val s = synchronized(lock) { session }
        if (s == 0L) return null
        val consumed = RimeNative.selectCandidate(s, index)
        if (consumed < 0) return null
        // 预览**只作埋点**:它就是「按回车会提交什么」,拿来对照能一眼看出缓冲被截错了。
        val preview = RimeNative.commitTextPreview(s)
        android.util.Log.i(
            TAG,
            "consume: index=$index word=\"$selectedWord\" consumed=$consumed preview=\"$preview\"",
        )
        // 2026-10-06:**不再把 0 折进 null**。
        //
        // 原文写的是「`<= 0` 一律当说不清…… 两者上层动作相同,不必区分」。
        // 动作确实相同(都清空缓冲),但**含义完全不同**:
        //
        //   0 = 引擎说「整个输入都被吃掉了」 —— 这是**一个答案**
        //   <0 = 没选成                    —— 这是「没有答案」
        //
        // 折掉之后埋点就说谎了:日志里的 `consumed=0` 原本表示「全吃掉」,
        // 与「没选成」再也分不开。而这两种在真机上频率差得很远 ——
        // **全吃掉是常态**(首选候选通常覆盖整串),所以一旦混在一起,
        // 「为什么这次没留下剩余」这类问题就再也查不出来。
        //
        // 机制:全吃掉时 `ConcreteEngine::OnSelect` 把段标 kConfirmed 后
        // `Forward()` 推出空段,composition 里已没有未确认段 ⇒
        // `GetCurrentStartPosition()` 报 0。探针实测确认
        // (`tools/rimeprobe/run.sh nihao`:选「你好」→ consumed=0、composition 清空;
        //  选「你」→ consumed=2、剩 [2,5))。
        //
        // 只有 <0 才是「说不清」—— 交给调用方保守处理。
        return PinyinConsumption(consumed = consumed, preview = preview.orEmpty())
    }

    /**
     * 词已上屏 —— **不需要做任何事**:librime 的用户词频由它自己在
     * `essay.txt` + LevelDB 里维护,上屏一次它就学到一次。
     */
    override fun onCommitted(word: String) {
        // librime 内部自动学习
    }

    /**
     * 逐词删除用户词 —— **不支持**(返回 false)。
     *
     * Rime 的用户词频在 LevelDB 里,没有删单个词的 API(只能整个目录删掉,
     * 那等于清空全部学习记录)。返回 false 而不是抛异常,是为了让调用方
     * **如实告诉用户"没删掉"**;假装成功更糟 —— 词还在,下次输出来又冒出来。
     * 见 [InputMethod.supportsWordRemoval]。
     */
    override val supportsWordRemoval: Boolean get() = false

    override fun removeUserWord(word: String): Boolean = false

    override fun userCount(word: String): Int = 0

    /** 高亮项序号(-1 = 无)。候选栏用它给选中项加背景。 */
    fun highlighted(): Int {
        val s = synchronized(lock) { session }
        return if (s == 0L) -1 else RimeNative.highlighted(s)
    }

    /** 清空 composition(切语言 / ESC)。 */
    fun reset() {
        val s = synchronized(lock) { session }
        if (s != 0L) RimeNative.reset(s)
    }

    fun close() {
        synchronized(lock) {
            if (session != 0L) RimeNative.destroySession(session)
            session = 0
        }
        RimeNative.destroy()
    }

    private companion object {
        const val TAG = "RimeEngine"

        /**
         * 简体拼音方案。
         *
         * 是 `luna_pinyin` 而不是 `luna_pinyin_simp` —— 后者属于完整的
         * `rime-luna-pinyin` 包,而我们用的是 librime `data/minimal/` + `term-ime-dict`。
         * 写错的后果**极隐蔽**:schema 不存在时 librime 不报错、部署照跑,
         * 只是没有 translator 生效 ⇒ 查询永远空。
         */
        const val SCHEMA_ID = "luna_pinyin"

        /**
         * librime 的 `app_name`。**改动等于换用户空间** —— 已学到的用户词会「消失」
         * (它在 installation.yaml 里当键用)。定下来就别动。
         * 带 `rime.` 前缀是 librime 的要求(见 rime_api.h)。
         */
        const val APP_NAME = "rime.hwkbd"
    }
}

// [PinyinConsumption] 的定义已搬到 `dev.hwkbd.ime.strategy`(2026-10-06):
// 它是这个包对外的返回类型,放在实现层会让 strategy 反过来依赖 rime。
// 连「`consumed == 0` 是「全吃掉」而不是「说不清」」那份说明也一起过去了。

/**
 * 丢掉 raw 开头**已经吃掉的那 [consumed] 个字符**,返回剩下的。
 *
 * ## 为什么截的是前缀而不是后缀
 *
 * librime 的候选**永远从当前段起点开始吃**:`Context::Select` 选的是
 * `composition().back()` 那一段的菜单,那一段的起点就是还没被吃掉的输入的开头。
 * 选「你」(覆盖 `nihao` 里的 "ni")之后,剩下的是 `"hao"` —— **尾巴**,不是头。
 *
 * 上一版(2026-10-05 之前)用的是 `rawPrefixOf`,截的是**前缀**,方向正好反了:
 * 它只在「被吃掉的正好是最后一段」时才碰巧对。而首选候选覆盖的通常是**开头**
 * 那一段(`nihao` 的首选是「你好」,但列表里也有只覆盖 "ni" 的「你」),
 * 所以那条错路在真机上天天走。
 *
 * ## [consumed] 是**下标**,不是「非撇字符的个数」
 *
 * 2026-10-06 之前这里写的是「喂进去之前 `'` 被剥掉了」,于是实现数的是
 * **非撇字符**。**那句话是假的** —— [dev.hwkbd.ime.strategy.PinyinInputMethod.normalize]
 * 明确**保留** `'`(只合并连续的、去掉开头的),所以撇就在喂给引擎的那串里,
 * 也就在坐标里。
 *
 * 探针实测(`tools/rimeprobe/run.sh "zhong'guo"`,9 字符含 1 个撇):
 *
 * ```
 * 选「中古」→ consumed=8,引擎剩下的输入是 "o"（preedit="中古o"）
 * ```
 *
 * `8` 是**下标**(`"zhong'guo"[8] == 'o'`)。按「非撇字符计数」,
 * 8 个非撇字符正好把那 8 个字母数完 ⇒ 会返回 `""`,**把 `o` 弄丢**。
 *
 * 两种语义**只在 raw 里没有撇时相同** —— 而屏幕上没有撇键,
 * 所以这条错路要等真机接了物理键盘才踩得到,单测里也抓不到。
 *
 * 项目自己的 E2E 场景 `consume-partial` 断言的正是
 * `after === raw_before.slice(consumed)` —— **下标语义**。
 * 也就是说断言一直是对的,只有这个实现是错的。
 *
 * ## 分节符:切点正好落在撇上时,那个撇跟着一起丢
 *
 * 撇标的是「上一个音节到此为止」,属于**被吃掉的那一段**。
 * 留着会得到以撇开头的串(下一次 `normalize` 还得再 `trimStart` 一次)。
 *
 * ```
 * "nihao"  吃 2 ⇒ "hao"    "ni'hao" 吃 2 ⇒ "hao"   (切点落在撇上,撇一起丢)
 * "ni'ha'o" 吃 4 ⇒ "o"      "hao" 吃 99 ⇒ ""        (宁可全清,不留半个词)
 * ```
 *
 * 中间的分节符要**保留**:它标着「这里是两段」,下一轮 query 靠它定界。
 */
internal fun rawAfterConsuming(raw: String, consumed: Int): String {
    if (consumed <= 0) return raw
    // 下标语义,超长则夹到末尾(宁可全清,不留半个词)
    var i = consumed.coerceAtMost(raw.length)
    // 切点正好落在分节符上时,那个分节符是**被吃掉那段**的边界 —— 一起丢。
    while (i < raw.length && raw[i] == '\'') i++
    return raw.substring(i)
}
