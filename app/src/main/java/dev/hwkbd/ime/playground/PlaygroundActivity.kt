package dev.hwkbd.ime.playground

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyLayoutStore
import dev.hwkbd.ime.strategy.DirectInputMethod
import dev.hwkbd.ime.strategy.ImeRegistry
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 实验场:一个干净的输入框,专门用来试拼音/飞字/层切换/快捷键,
 * 下方可查看本输入法的运行日志(词库加载、飞字事件),方便真机反馈问题。
 */
class PlaygroundActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playground)
        // 实验场不需要任何自动填充:整窗禁用。密码框聚焦时 GMS 仍会弹数据集选择器抢焦点,
        // 字段级 importantForAutofill="no" 压不住,升级到窗口级(实验场是调试工具,禁了无副作用)
        window.decorView.importantForAutofill =
            android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val et = findViewById<EditText>(R.id.et_playground)
        val logView = findViewById<TextView>(R.id.tv_play_log)

        findViewById<Button>(R.id.btn_play_clear).setOnClickListener { et.setText("") }
        findViewById<Button>(R.id.btn_play_paste_log).setOnClickListener {
            logView.text = readRecentImeLog()
        }
        // L1-L5 键位总览:三层物理映射 + L4 各输入框类型的实际显示键 + L5 说明
        findViewById<Button>(R.id.btn_play_keymap).setOnClickListener {
            logView.text = KeymapOverview.build(
                KeyLayoutStore.load(this),
                ImeRegistry.builtin().registerMethod(DirectInputMethod),
            )
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        android.util.Log.i("Playground", "touch act=${ev.actionMasked} src=0x${Integer.toHexString(ev.source)} x=${ev.x.toInt()} y=${ev.y.toInt()}")
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        android.util.Log.i("Playground", "generic act=${ev.actionMasked} src=0x${Integer.toHexString(ev.source)} x=${ev.x.toInt()} y=${ev.y.toInt()}")
        return super.dispatchGenericMotionEvent(ev)
    }

    /** logcat -d 抓本应用最近的 ImeService 日志(需要 adb 才能拿全,这里尽力而为)。 */
    private fun readRecentImeLog(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "ImeService:*", "-t", "100"))
        val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
        process.waitFor()
        if (out.isBlank()) "无日志(logcat 不可用或为空)" else out.takeLast(4000)
    } catch (e: Exception) {
        "读取日志失败:${e.message}\n(完整日志请用 adb logcat -s ImeService)"
    }
}
