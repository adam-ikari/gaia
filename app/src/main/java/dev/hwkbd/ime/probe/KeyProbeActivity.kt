package dev.hwkbd.ime.probe

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.R
import dev.hwkbd.ime.service.ImeService

/**
 * 真机按键探测:在 Activity 层拦截全部 KeyEvent / 通用 MotionEvent,
 * 实时列出 keycode/scanCode/meta,用于确认 Titan 2 上 Sym/Fn/方向键的真实键值,
 * 并可一键把探测到的键设为 Sym 键。
 */
class KeyProbeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val log = StringBuilder()
    private var lastKeyCode = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImeRuntime.init(this)
        setContentView(R.layout.activity_probe)
        logView = findViewById(R.id.tv_probe_log)

        findViewById<Button>(R.id.btn_probe_clear).setOnClickListener {
            log.clear()
            lastKeyCode = -1
            render()
        }
        findViewById<Button>(R.id.btn_probe_share).setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log.toString())
                    },
                    getString(R.string.probe_share),
                )
            )
        }
        findViewById<Button>(R.id.btn_set_sym).setOnClickListener {
            if (lastKeyCode <= 0) {
                Toast.makeText(this, R.string.probe_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ImeRuntime.prefs.edit().putInt(ImeService.PREF_SYM_KEYCODE, lastKeyCode).apply()
            Toast.makeText(this, getString(R.string.probe_sym_set, lastKeyCode), Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.btn_reset_sym).setOnClickListener {
            ImeRuntime.prefs.edit().putInt(ImeService.PREF_SYM_KEYCODE, KeyEvent.KEYCODE_PICTSYMBOLS).apply()
            render()
        }
        render()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastKeyCode = event.keyCode
            appendLine(
                "KEY %s code=%d(%s) scan=%d meta=0x%x rep=%d dev=%s".format(
                    if (event.action == KeyEvent.ACTION_DOWN) "↓" else "↑",
                    event.keyCode,
                    KeyEvent.keyCodeToString(event.keyCode),
                    event.scanCode,
                    event.metaState,
                    event.repeatCount,
                    event.device?.name ?: "?",
                )
            )
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_HOME) {
            return super.dispatchKeyEvent(event)
        }
        return true // 消费,避免导航副作用
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        appendLine("MOTION action=%d source=0x%x".format(ev.action, ev.source))
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun appendLine(s: String) {
        log.append(s).append('\n')
        render()
    }

    private fun render() {
        val sym = ImeRuntime.prefs.getInt(ImeService.PREF_SYM_KEYCODE, KeyEvent.KEYCODE_PICTSYMBOLS)
        findViewById<TextView>(R.id.tv_sym_status).text =
            getString(R.string.probe_sym_current, sym, KeyEvent.keyCodeToString(sym))
        logView.text = log
    }
}
