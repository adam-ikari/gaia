package dev.hwkbd.ime

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.UserDict

/**
 * 进程级运行时:输入法服务与设置页共享同一份用户词库/偏好/词库实例。
 */
object ImeRuntime {

    const val USER_DICT_FILE = "user_dict.json"

    lateinit var prefs: SharedPreferences
        private set

    lateinit var userDict: UserDict
        private set

    /** 内置词库(service 后台线程载入后赋值)。 */
    @Volatile
    var dict: PinyinDict? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        userDict = UserDict()
        val f = context.applicationContext.getFileStreamPath(USER_DICT_FILE)
        if (f != null && f.exists()) {
            runCatching { f.readText() }.getOrNull()?.let { userDict.loadJson(it) }
        }
    }

    fun saveUserDict(context: Context) {
        if (!userDict.dirty) return
        runCatching {
            context.applicationContext.openFileOutput(USER_DICT_FILE, Context.MODE_PRIVATE).use {
                it.write(userDict.toJson().toByteArray())
            }
            userDict.markClean()
        }
    }
}

class ImeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ImeRuntime.init(this)
    }
}
