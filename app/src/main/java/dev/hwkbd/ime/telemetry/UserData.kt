package dev.hwkbd.ime.telemetry

import android.content.Context
import java.io.File

/**
 * librime 用户数据目录的只读视图:给设置页显示统计、给「清空学习记录」用。
 *
 * ## 为什么单独一个类
 *
 * 换引擎前用户词频是应用自己的 JSON(`files/user_dict.json`),现在换成
 * librime 的 LevelDB(`files/rime/user/`)。LevelDB **没有一个能安全在线打开读取的
 * 公开 API**(`.ldb` / `.log` / `MANIFEST` 是内部格式),所以:
 *
 * - **统计**只能靠「目录大小 + 文件数」,拿不到词条数与词频条数;
 * - **导出/导入**整个撤掉了 —— 解析 LevelDB 要引入一个库,而它的格式是 librime 私有实现。
 *   与其引一个不可靠的依赖,不如把入口撤掉(设置页里那些项已一并删除)。
 * - **清空**可以做:删掉 `user/` 目录,librime 下次访问会重建。
 *   注意这会**清掉全部学习记录**,所以要用前确认引擎不在用(设置页打开时 service 多半还活着,
 *   所以实际上删完要重启输入法才生效 —— 这点要在文案里说)。
 *
 * @see RimeData.install —— 只读的词典源在 `assets/rime/`,这里是可写的用户数据
 */
object UserData {

    private const val RIME = "rime"
    private const val USER = "user"

    private fun userDir(context: Context): File =
        File(File(context.filesDir, RIME), USER)

    /**
     * 统计。返回 (目录字节数, 文件数)。
     *
     * 目录不存在 = 还没部署过,librime 会在首次访问时建。
     */
    fun stats(context: Context): Pair<Long, Int> {
        val dir = userDir(context)
        if (!dir.isDirectory) return 0L to 0
        var bytes = 0L
        var count = 0
        // 递归:LevelDB 会按哈希分目录
        fun walk(d: File) {
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f) else { bytes += f.length(); count++ }
            }
        }
        walk(dir)
        return bytes to count
    }

    fun exists(context: Context): Boolean = userDir(context).isDirectory

    /**
     * 清空全部学习记录。
     *
     * **只在输入法不活跃时才真正生效** —— librime 进程里可能还持有 LevelDB 的文件句柄,
     * 删了目录它也不一定立刻释放。所以返回后建议重启输入法;设置页的文案写明了这一点。
     *
     * @return 目录是否已不存在(成功)
     */
    fun clear(context: Context): Boolean {
        val dir = userDir(context)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 用户数据目录的可读路径,设置页显示它。 */
    fun location(context: Context): String = userDir(context).absolutePath
}
