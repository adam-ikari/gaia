package dev.hwkbd.ime.rime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * librime 的 JNI 入口。
 *
 * ## 为什么走 `rime_get_api()`
 *
 * librime 1.x 的公开 C API **只有一个符号** `rime_get_api()`,返回 `RimeApi` 函数指针表
 * (见 `librime/src/rime_api.h` 的 `struct RimeApi`)。那些 `RimeSetup` / `RimeCreateSession`
 * 在 `rime_api_impl.h` 里是 `namespace rime` 下的 **C++** 函数(符号形如 `_Z12RimeFinalizev`),
 * 不是 C 链接 —— 直接调连链接都过不去。
 *
 * **表项必须判空**:函数指针表允许不同版本增删成员(`get_status` 就是后加的)。
 * native 侧的 `api()` 拿不到表时所有入口都返回失败值,不留半初始化状态。
 *
 * ## 全部方法都必须在 IME 主线程调,除 [nativeJoinMaintenance]
 *
 * librime 的 session 状态机不是线程安全的。部署(把 `*.yaml` 编成 `*.bin`)在
 * librime 自己的 maintenance 线程里,那个必须等,所以它是唯一一个可以(且应该)
 * 在后台线程调的入口。
 */
object RimeNative {

    /** 库没加载成功的标记。与「librime 返回空」区分开。 */
    const val LOAD_FAILED = "(load failed)"

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    init {
        loaded = try {
            System.loadLibrary("rime_jni")
            true
        } catch (t: UnsatisfiedLinkError) {
            loadError = t.message ?: t.toString()
            false
        }
    }

    val available: Boolean get() = loaded

    /** 加载失败的原因,给日志/设置页用。 */
    val error: String? get() = loadError

    /** librime 版本,形如 `1.17.0`;加载失败时给 [LOAD_FAILED]。 */
    fun version(): String = if (loaded) nativeVersion() else LOAD_FAILED

    /**
     * 报告 `RimeApi` 表里我们关心的项有几个在位,形如 `api=OK present=20/20`。
     *
     * 存在的理由:**本地编过不等于设备上的 .so 是同一版**。ABI 变体、CI 与本机编出的
     * 差异、旧的 .so 被缓存,都会让表项缺失,而症状是"候选词莫名其妙为空"这种极难查的东西。
     */
    fun apiProbe(): String = if (loaded) nativeApiProbe() else LOAD_FAILED

    /**
     * 初始化。**必须先在后台线程做完部署**([nativeJoinMaintenance])再用。
     *
     * @param schemaId 只做简体 ⇒ 固定 `luna_pinyin_simp`;换它等于换用户空间(用户词会"消失")
     * @return 0 = 成功,非 0 = 失败
     */
    fun initialize(userDir: String, sharedDir: String, buildDir: String, schemaId: String, appId: String): Int =
        if (loaded) nativeInit(userDir, sharedDir, buildDir, schemaId, appId) else -1

    // ---- 给 [RimeEngine] 的包装 ------------------------------------------------
    //
    // `external` 一律保持 private:**加载失败、参数校验、空表兜底都在这一层做完**,
    // 上层拿到的是"要么是有效结果、要么是安全的空值",不必在每个调用点写
    // `if (!available)`。RimeEngine 因此不必知道 native 到底存不存在。

    /** 等部署结束。**唯一可以(且应该)在后台线程调的入口。 */
    fun joinMaintenance() { if (loaded) nativeJoinMaintenance() }

    fun createSession(): Long = if (loaded) nativeCreateSession() else 0L

    fun destroySession(session: Long) { if (loaded && session != 0L) nativeDestroySession(session) }

    fun destroy() { if (loaded) nativeDestroy() }

    fun reset(session: Long) { if (loaded && session != 0L) nativeReset(session) }

    fun candidates(session: Long, limit: Int): Array<String> =
        if (loaded && session != 0L) nativeCandidates(session, limit) else emptyArray()

    fun selectCandidate(session: Long, index: Int): Boolean =
        loaded && session != 0L && nativeSelectCandidate(session, index)

    fun commit(session: Long): String? =
        if (loaded && session != 0L) nativeCommit(session) else null

    /**
     * 选中候选之后的 `commit_text_preview`。**空串是合法值**(表示全被吃掉了),
     * 与 null(没查到)在上层是两件事。
     */
    fun commitTextPreview(session: Long): String? =
        if (loaded && session != 0L) nativeCommitTextPreview(session) else null

    fun highlighted(session: Long): Int =
        if (loaded && session != 0L) nativeHighlighted(session) else -1

    /** 喂一个键。[RimeEngine] 用它把 `state.raw` 逐字重喂。 */
    fun processKey(session: Long, keycode: Int): Boolean =
        loaded && session != 0L && nativeProcessKey(session, keycode)

    fun compositionLength(session: Long): Int =
        if (loaded && session != 0L) nativeCompositionLength(session) else 0

    private external fun nativeVersion(): String
    private external fun nativeApiProbe(): String
    private external fun nativeInit(userDir: String, sharedDir: String, buildDir: String, schemaId: String, appId: String): Int
    private external fun nativeJoinMaintenance()
    private external fun nativeCreateSession(): Long
    private external fun nativeDestroySession(session: Long)
    private external fun nativeDestroy()
    private external fun nativeReset(session: Long)
    private external fun nativeCandidates(session: Long, limit: Int): Array<String>
    private external fun nativeSelectCandidate(session: Long, index: Int): Boolean
    private external fun nativeCommit(session: Long): String?

    /**
     * 选出候选之后的 `commit_text_preview`(见 `rime_jni.cpp` 里同名函数的注释 ——
     * 那里解释了为什么 `get_commit` 与 `composition.preedit` 都给不出「剩下的拼音」)。
     *
     * 返回的是**整串**预览,上层要自己把已选中的那个词剥掉。
     */
    private external fun nativeCommitTextPreview(session: Long): String?
    private external fun nativeHighlighted(session: Long): Int
    private external fun nativeProcessKey(session: Long, keycode: Int): Boolean
    private external fun nativeCompositionLength(session: Long): Int
}

/**
 * 把 `luna_pinyin_simp` 与 `essay.txt` 打进 assets,并在首次启动时释放到 shared 目录。
 *
 * ## 为什么不直接依赖 librime 自带的数据
 *
 * librime 源码里带着 `data/`(luna_pinyin.dict.yaml 等),但那是**编译期**用来生成
 * `*.bin` 的源;装到手机上的 `*.bin` 需要单独带。
 *
 * ## 体积
 *
 * 这一套是换引擎最大的固定成本:`luna_pinyin` 的 dict 编出来约 8-12MB,
 * `essay.txt` 约 1MB。**比现在 APK 里那份 `assets/dict.txt`(8.3MB)并不会更小** ——
 * 也就是说换引擎在体积上不吃亏,前提是**删掉旧的 jieba 词典**。
 * 两份都留着就是 16MB+。
 *
 * @see PinyinDict —— 旧引擎那份词典,换引擎后应当删除
 */
object RimeData {

    private const val TAG = "RimeData"

    /** assets 里词典/配置所在的目录名。 */
    const val ASSET_DIR = "rime"

    /**
     * 释放 assets 到 shared 目录。**幂等**:已释放且内容一致就跳过。
     *
     * 幂等很重要:每次启动都重写会让 librime 认为数据变了从而**重新部署**,
     * 那要好几秒,等于每次开输入法都卡一下。
     *
     * ## 之前只判「存在且非空」—— 那是会静默吞掉改动的(2026-10-04 修)
     *
     * 旧代码是 `if (out.exists() && out.length() > 0) continue`,注释写的是
     * 「大小一致就跳过」,但**代码从没比对过大小**,只要文件在就永远不覆盖。
     *
     * 后果:改了 `assets/rime 下那些 .yaml` 打成新 APK,老用户设备上那份**永远不更新**。
     * 2026-10-04 加简体输出时就撞上这个 —— schema 改了 `reset: 1`,
     * 但真机跑的仍是三个月前释放的那份,所以「改了没生效」,
     * 而本地看代码一切正常。
     *
     * 现在:**小文件逐字节比对**(见 [VERIFY_LIMIT]),大文件才退回长度判断。
     */
    fun install(context: Context, sharedDir: File) {
        if (!sharedDir.exists()) sharedDir.mkdirs()
        try {
            val files = listAssets(context, ASSET_DIR)
            if (files.isEmpty()) {
                // 这是**预期内**的情况:词典还没打进来。此时 librime 仍能初始化,
                // 但 `start_maintenance` 部署不出任何 bin,查询会一直空。
                // 不抛异常 —— 由 RimeEngine.prepare 的返回值决定上层要不要回退。
                Log.w(TAG, "assets/$ASSET_DIR 为空:没有词典,Rime 会查不出候选")
                return
            }
            for (path in files) {
                val out = File(sharedDir, path.substringAfter("$ASSET_DIR/", ""))
                if (!needsInstall(context, path, out)) continue
                out.parentFile?.mkdirs()
                // 直接按字节复制:词典是几 MB 的 YAML,过 String 会白占一份堆内存。
                // 也不能包 OutputStreamWriter —— `copyTo` 要的是 OutputStream,包了就编译不过。
                context.assets.open(path).use { input ->
                    out.outputStream().use { os -> input.copyTo(os, 64 * 1024) }
                }
            }
        } catch (t: Throwable) {
            // 词典装不上不该让输入法起不来:上层会回退到自研引擎。
            Log.w(TAG, "释放词典失败: ${t.message}")
        }
    }

    /**
     * 逐字节比对的上限(bytes)。**小于等于**它的文件走全量比对。
     *
     * ## 为什么设上限
     *
     * 比对要先读一遍 asset,而 `essay.txt` 3.8MB、`luna_pinyin.dict.yaml` 890KB ——
     * 每次启动都读完这两个只为比对,不值得。
     *
     * ## 为什么这个上限不构成隐患
     *
     * 会被人手改的是**配置**(`*.schema.yaml`(glob) / `default.yaml` / `opencc 目录里的 .json` /
     * `opencc 目录里的 .txt`),它们最大 34KB,全在限内 ⇒ 逐字节比对。
     * 超限的两个是**纯数据**,改它们本来就要跟词典版本一起重编,
     * 而且改内容通常也改长度,长度判断照样能抓到;
     * 万一内容变了而长度没变,重装一次 APK 即可(删 shared 目录)。
     */
    private const val VERIFY_LIMIT = 256L * 1024

    /** 该文件是否需要(重新)释放。判据见 [install] 与 [VERIFY_LIMIT]。 */
    private fun needsInstall(context: Context, assetPath: String, out: File): Boolean {
        if (!out.exists() || out.length() == 0L) return true
        if (out.length() > VERIFY_LIMIT) return false // 超限:只看存在(见 VERIFY_LIMIT)
        // 限内:逐字节比。asset 侧不依赖 `available()` —— APK 里的 asset 可能是
        // 压缩的,那个方法的返回值不可靠,直接读满更稳。
        val same = try {
            context.assets.open(assetPath).use { input ->
                input.readBytes().contentEquals(out.readBytes())
            }
        } catch (t: Throwable) {
            false // 读不了就当要重装,宁可多拷一次
        }
        return !same
    }

    /** 递归列出 assets 下的文件路径。不引入 asset 遍历库,自己走一遍足够。 */
    private fun listAssets(context: Context, dir: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String) {
            val children = try {
                context.assets.list(path) ?: return
            } catch (t: Throwable) {
                return
            }
            if (children.isEmpty()) {
                out += path // 是文件
                return
            }
            children.forEach { walk("$path/$it") }
        }
        walk(dir)
        return out.filter { it.isNotEmpty() }
    }
}
