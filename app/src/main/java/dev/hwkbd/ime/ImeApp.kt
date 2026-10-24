package dev.hwkbd.ime

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.hwkbd.ime.rime.RimeEngine
import dev.hwkbd.ime.telemetry.CrashReporter

/**
 * 进程级运行时:输入法服务与设置页共享的偏好与引擎实例。
 *
 * ## 换引擎后少了什么(2026-10-03)
 *
 * 原来这里还有三样,现在都没了:
 * - `userDict: UserDict` —— 旧引擎的**用户词频**是自己实现的 JSON 文件
 *   (`files/user_dict.json`),上屏一次要自己 `bump` 一次、自己落盘。
 *   librime 的用户词频在 `files/rime/user/` 里由它自己维护(essay.txt + LevelDB),
 *   **不需要应用做任何事**,也就少了一份要维护的持久化状态。
 * - `dict: PinyinDict?` —— 旧引擎那份 jieba 词典,连同 `assets/dict.txt.gz`
 *   (3.1MB)一起删了,换成 librime 的 `assets/rime/`(4.6MB)。
 * - `saveUserDict()` —— 上面第一条的配套。
 *
 * 用户已有的学习成果**会丢**:旧的 `user_dict.json` 与 Rime 的 LevelDB 是两套东西,
 * 没有转换路径。这是一次性的代价,要在发版说明里讲清楚。
 */
object ImeRuntime {

    lateinit var prefs: SharedPreferences
        private set

    /**
     * librime 引擎,service 建时创建。
     *
     * 挂在全局是因为设置页要看词典统计(词条数、占用),而那需要访问引擎
     * 的用户数据目录 —— service 不活跃时也能读。
     */
    @Volatile
    var rime: RimeEngine? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        purgeCloudCredentials()
    }

    /**
     * 云端识别退役(2026-10-24)后,老用户本机可能还留着填过的密钥。
     *
     * 代码已经不再读这几个键,但**留在 SharedPreferences 里就等于白留** ——
     * 备份到别的设备、或以后有人 root 取证,都还在。启动即抹掉,一次性。
     */
    private fun purgeCloudCredentials() {
        val keys = listOf(
            "voice_engine",
            "tencent_secret_id",
            "tencent_secret_key",
            "xfyun_app_id",
            "xfyun_api_key",
            "xfyun_api_secret",
        )
        if (!keys.any(prefs::contains)) return
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }
}

class ImeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获必须**早于一切** —— 它要抓的是「本进程启动过程中」的崩溃,
        // 装晚了就只剩运行期的。
        CrashReporter.install(this)

        ImeRuntime.init(this)
    }
}
