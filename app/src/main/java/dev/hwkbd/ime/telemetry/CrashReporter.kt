package dev.hwkbd.ime.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import dev.hwkbd.ime.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获:**只落本地**(2026-10-03 用户「这版本先不做上传方案 先保存到本地」)。
 *
 * ## 落点
 *
 * `getExternalFilesDir("crash")` ⇒
 * `/sdcard/Android/data/dev.hwkbd.ime/files/crash/<时间戳>.txt`
 *
 * 选应用私有外部目录的原因:
 * - **不需要运行时权限**(scoped storage 下 `getExternalFilesDir` 一直可用),
 *   `adb pull` 或文件管理器直接能取;
 * - 卸载即删除,不会在用户手机上留一堆积灰文件。
 *
 * 被否掉的:公共 `Download/`(要额外权限、卸载不清理)、内部 `files/`(得 `run-as` 才能取)。
 *
 * ## 为什么要它
 *
 * 开了 R8 之后类名变成 `a.b.c`,崩溃栈是 `a.b.c(SourceFile:42)`。
 * **没有 `mapping.txt` 这些栈等于废的**,所以 CI 必须同时上传 mapping
 * (见 `.github/workflows/release.yml` 的 `uploadProguardMapping`),
 * 本机则可以用 SDK 里的 `retrace` 还原:
 *
 * ```bash
 * retrace mapping.txt crash.txt
 * ```
 *
 * `tools/telemetry/triage-crashes.sh` 把「按版本找 mapping → 批量 retrace → 按栈归并」
 * 串成一条命令。
 *
 * ## 采什么、不采什么
 *
 * 采:异常类名 + 栈 + 应用版本 + **粗桶后的** ABI / 系统版本 / 密度。
 * 不采:**任何用户输入**、签名指纹、厂商型号。见 [Redaction] 的理由。
 *
 * 本文件里不存在 `inputText` / `candidate` / `keystroke` 这类字段,不是没填,是不给 ——
 * 输入法拿得到全部输入,这条线不能让。
 */
object CrashReporter {

    private const val TAG = "ImeCrash"
    const val DIR = "crash"

    /** 只留最近这么多份 —— 崩溃循环时目录会长得很快,不能把用户存储撑满。 */
    private const val MAX_FILES = 50

    @Volatile
    private var context: Context? = null

    /**
     * 装捕获。**只装一次**,且必须早于本进程任何别的初始化。
     *
     * @return 上一个 handler(通常是系统的),记下来以便将来恢复
     */
    fun install(ctx: Context): Thread.UncaughtExceptionHandler? {
        val app = ctx.applicationContext
        context = app
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 先落盘,再交给上一个 handler:崩溃瞬间进程随时可能死。
            // runCatching 是必须的 —— 捕获器自己抛异常会让系统 handler 永远收不到,
            // 症状是「应用无提示地消失」。
            runCatching { record(app, thread, throwable) }
            prev?.uncaughtException(thread, throwable)
        }
        return prev
    }

    private fun record(ctx: Context, thread: Thread, t: Throwable) {
        val stack = stackTraceOf(t)
        val entry = buildString {
            appendLine("---- $NOW ----")
            appendLine("app        : ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("abiBucket  : ${Redaction.abiBucket(Build.SUPPORTED_ABIS.toList())}")
            appendLine("sdkBucket  : ${Redaction.sdkBucket(Build.VERSION.SDK_INT)}")
            appendLine("densBucket : ${Redaction.densityBucket(ctx.resources.displayMetrics.density)}")
            appendLine("thread     : ${thread.name}")
            appendLine("---- stack ----")
            appendLine(stack)
        }
        Log.e(TAG, "崩溃已捕获:\n$entry")
        runCatching { writeFile(ctx, entry) }
    }

    private fun writeFile(ctx: Context, entry: String) {
        val dir: File = ctx.getExternalFilesDir(DIR) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "${SimpleDateFormat(Redaction.FILENAME_TIMESTAMP, Locale.US).format(Date())}.txt")
        f.writeText(entry)
        trim(dir)
    }

    /** 只留最近 [MAX_FILES] 份。 */
    private fun trim(dir: File) {
        val files = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".txt") }
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    // ---- 供 triage 脚本与设置页使用 ----

    /** 报告目录的可读路径。 */
    fun location(ctx: Context): String =
        (ctx.getExternalFilesDir(DIR) ?: ctx.filesDir).absolutePath

    /** 已收集的报告数。 */
    fun count(ctx: Context): Int =
        (ctx.getExternalFilesDir(DIR)?.listFiles() ?: emptyArray()).count { it.name.endsWith(".txt") }

    /** 全部报告拼成一份,便于导出/查看。 */
    fun localReports(ctx: Context): String {
        val dir = ctx.getExternalFilesDir(DIR) ?: return ""
        return runCatching {
            (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".txt") }
                .sortedBy { it.lastModified() }
                .joinToString("\n\n") { "===== ${it.name} =====\n" + it.readText() }
        }.getOrElse { "" }
    }

    fun clear(ctx: Context) {
        runCatching { (ctx.getExternalFilesDir(DIR)?.listFiles() ?: emptyArray()).forEach { it.delete() } }
    }

    private val NOW: String
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}
