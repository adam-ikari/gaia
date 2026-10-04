package dev.hwkbd.ime.rime

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
                if (c == '\'') continue // 分隔符是本项目自己的概念,librime 不认识
                RimeNative.processKey(s, c.code)
            }
        }
        val words = RimeNative.candidates(s, limit)
        if (words.isEmpty()) return emptyList()
        return words.map {
            // kind/score 见类注释:librime 不暴露,统一填 EXACT / 0。
            Candidate(word = it, pinyin = raw, kind = CandidateKind.EXACT, score = 0L)
        }
    }

    /**
     * 选中第 [index] 个候选。
     *
     * @return 上屏文本 + **还没被吃掉的那部分拼音**;无候选/未提交给 null。
     *
     * ## 为什么返回「剩下多少」而不是只返回上屏文本
     *
     * 原实现只返回上屏文本,类注释写「选完 librime 的 composition 就空了 ——
     * 上层这时也该清 `state.raw`」。**那个假设对单音节成立,对多段输入不成立**:
     * librime 是分段的,选一个候选只吃掉它覆盖的那几段。
     *
     * 后果(2026-10-04 用户「拼音的消费也要根据实际选词消费,不能一次清空」):
     * 输入 `ni'hao` 选「你」之后,`hao` 被一起清掉了 —— 用户已经打的那一段白打。
     *
     * 所以这里把**剩下的拼音**一并带回去,让上层只清吃掉的那部分。
     * `remaining` 为 null 表示「librime 一次吃完整串」,上层照旧全清 ——
     * 也就是说这条路径对现有行为是**加法式**的。
     */
    fun select(index: Int): Pair<String, String?>? {
        val s = synchronized(lock) { session }
        if (s == 0L) return null
        if (!RimeNative.selectCandidate(s, index)) return null
        val committed = RimeNative.commit(s) ?: return null
        // **必须在 commit 之后读**:get_commit 会取走 commit 队列,
        // 但不会动 composition —— 剩下的段还在 preedit 里。
        val remaining = RimeNative.remainingPinyin(s)
        return committed to remaining
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
