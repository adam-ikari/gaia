package dev.hwkbd.ime.service

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.TextView
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.R
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.ime.ImeController
import dev.hwkbd.ime.ime.ImeHost
import dev.hwkbd.ime.ime.ImeMode
import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyLayoutStore
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.ui.CandidateBarView
import dev.hwkbd.ime.ui.SoftKeyboardView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * 输入法服务:KeyEvent → ImeController → InputConnection。
 * 有硬键盘时系统默认不显示输入视图,这里强制显示(候选栏是主要选字通道)。
 */
class ImeService : InputMethodService() {

    private lateinit var controller: ImeController
    private lateinit var engine: PinyinEngine

    private var statusRow: View? = null
    private var candidateRow: View? = null
    private var statusMode: TextView? = null
    private var candidateBar: CandidateBarView? = null
    private var softKeyboard: SoftKeyboardView? = null
    private var btnSoftKeyboard: ImageButton? = null

    private var showSoftKeyboard = true

    /** 当前组词区内容(render 与 onUpdateSelection 对账用)。 */
    private var composing = ""

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dict-loader").apply { isDaemon = true } }

    private val host = object : ImeHost {
        override fun commitText(text: String) {
            val ic: InputConnection = currentInputConnection ?: return
            if (composing.isNotEmpty()) {
                ic.setComposingText(text, 1)
                ic.finishComposingText()
            } else {
                ic.commitText(text, 1)
            }
            composing = ""
        }

        override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) {
            val ic: InputConnection = currentInputConnection ?: return
            val t = SystemClock.uptimeMillis()
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(t, t + 20, KeyEvent.ACTION_UP, code, 0, meta))
        }

        override fun render() {
            updateUi()
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        ImeRuntime.init(this)
        val dict = PinyinDict()
        engine = PinyinEngine(dict, ImeRuntime.userDict)
        controller = ImeController(host, KeyLayoutStore.load(this), engine)
        ImeRuntime.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyPrefs()
        executor.execute {
            val t0 = System.currentTimeMillis()
            try {
                val lines = ArrayList<String>(300_000)
                openDictAsset().use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 1 shl 16).useLines { seq ->
                        seq.forEach { lines.add(it) }
                    }
                }
                dict.load(lines)
                ImeRuntime.dict = dict
                android.util.Log.i(TAG, "dict loaded: ${dict.size} entries, ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "dict load FAILED", e)
            }
            controller.refreshQuery()
        }
    }

    /**
     * 打开内置词库流。AAPT2 打包时会把 assets 的 dict.txt.gz 解压并改名为 dict.txt,
     * 所以资产名动态解析,并按 gzip 魔数(1f 8b)自适应明文/压缩两种形态。
     */
    private fun openDictAsset(): java.io.InputStream {
        val names = assets.list("") ?: emptyArray()
        val name = names.firstOrNull { it.startsWith("dict.txt") }
            ?: throw java.io.FileNotFoundException("no dict.txt* asset, got: ${names.joinToString()}")
        val raw = java.io.PushbackInputStream(assets.open(name), 2)
        val magic = ByteArray(2)
        val n = raw.read(magic)
        if (n == 2) raw.unread(magic)
        val gz = n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        return if (gz) GZIPInputStream(raw) else raw
    }

    override fun onDestroy() {
        ImeRuntime.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        ImeRuntime.saveUserDict(this)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_root, null)
        statusRow = root.findViewById(R.id.status_row)
        candidateRow = root.findViewById(R.id.candidate_row)
        statusMode = root.findViewById(R.id.tv_mode)
        candidateBar = root.findViewById<CandidateBarView>(R.id.candidate_bar).apply {
            onSelect = { controller.selectCandidate(it) }
            onPage = { controller.pageStep(it) }
        }
        softKeyboard = root.findViewById<SoftKeyboardView>(R.id.soft_keyboard).apply {
            layout = KeyLayoutStore.load(this@ImeService)
            onKey = { code -> controller.onKeyDown(code, false, false) }
            onAltTap = { controller.onAltTap() }
            onSymTap = { controller.onSymTap() }
            onShiftTap = { controller.onShiftTap() }
            onSelectZone = { controller.selectCandidate(it) }
            onPage = { controller.pageStep(it) }
        }
        btnSoftKeyboard = root.findViewById<ImageButton>(R.id.btn_soft_keyboard).apply {
            setOnClickListener { toggleSoftKeyboardPref() }
        }
        root.findViewById<ImageButton>(R.id.btn_hide_ime).setOnClickListener { requestHideSelf(0) }
        statusMode?.setOnClickListener { controller.toggleMode() }
        applyPrefs()
        updateUi()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 层映射可能被设置页改过,每次开始输入重新加载(开销可忽略)
        val layout: KeyLayout = KeyLayoutStore.load(this)
        controller.layout = layout
        softKeyboard?.layout = layout
        composing = ""
        controller.resetForNewInput()
    }

    override fun onFinishInput() {
        ImeRuntime.saveUserDict(this)
        super.onFinishInput()
    }

    // ------------------------------------------------------------------ 键事件

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return super.onKeyDown(keyCode, event)
        val consumed = controller.onKeyDown(
            keyCode,
            event.isCtrlPressed,
            event.isShiftPressed,
            event.isCapsLockOn,
            event.repeatCount > 0,
        )
        if (consumed) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return super.onKeyUp(keyCode, event)
        if (controller.onKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 光标被外部移动/组词区被破坏时丢弃组词,避免错位上屏。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (controller.state.hasComposition && composing.isNotEmpty()) {
            val expected = candidatesStart + composing.length
            if (candidatesEnd != expected) {
                composing = ""
                controller.abandonComposition()
            }
        }
    }

    /**
     * 飞字:键盘表面(TOUCHPAD)滑动选字。
     * 事件经 onGenericMotionEvent 送达(src=0x100008),坐标空间约 1440x720。
     * 手势 = DOWN 起点向上位移超过阈值 → 按起点横坐标落在 5 区的哪一区选候选。
     */
    private class FlyDetector {
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var tracking = false

        fun onMotion(action: Int, x: Float, y: Int): Int? {
            android.util.Log.i(TAG, "fly: act=$action x=$x y=$y tracking=$tracking")
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y.toFloat()
                    downAt = System.currentTimeMillis()
                    tracking = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasTracking = tracking
                    tracking = false
                    if (!wasTracking || action == MotionEvent.ACTION_CANCEL) return null
                    val dy = downY - y
                    val dx = kotlin.math.abs(x - downX)
                    val dt = System.currentTimeMillis() - downAt
                    // 上滑 60 单位以上、横向漂移不大于纵向位移、800ms 内 → 飞字
                    if (dy >= 60f && dy > dx && dt < 800) {
                        return zoneOf(downX)
                    }
                }
            }
            return null
        }

        /** 全宽按 zoneWeights 比例分 5 区。 */
        private fun zoneOf(x: Float): Int {
            val w = ImeRuntime.prefs.getString(PREF_HZ_SPLIT, "1,2,4,2,1")
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
                ?: listOf(1, 2, 4, 2, 1)
            val total = w.sum().toFloat()
            var acc = 0f
            val f = x / FLY_X_MAX * total
            for (i in w.indices) {
                acc += w[i]
                if (f < acc) return i
            }
            return w.size - 1
        }
    }

    private val flyDetector = FlyDetector()

    /** 探测键盘表面(TOUCHPAD)滑动:全量日志 + 飞字手势识别。 */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)
        if (event.source and SOURCE_TOUCHPAD_MASK != 0) {
            val zone = flyDetector.onMotion(event.actionMasked, event.x, event.y.toInt())
            if (zone != null && controller.state.hasComposition) {
                controller.selectCandidate(zone)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ 窗口策略

    /** 有硬键盘也要显示输入视图:候选栏是硬件键盘方案的核心选字通道。 */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** 密码/数字域不进拼音模式,直接透传(仍保留层与候选通道)。 */
    private fun isPasswordField(attribute: EditorInfo?): Boolean {
        val t = attribute?.inputType ?: InputType.TYPE_NULL
        val variation = t and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    // ------------------------------------------------------------------ 偏好

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    private fun applyPrefs() {
        val p = ImeRuntime.prefs
        controller.state.pageSize = p.getString(PREF_PAGE_SIZE, "5")?.toIntOrNull() ?: 5
        controller.fullWidthSymbolsInCn = p.getBoolean(PREF_FULLWIDTH, true)
        controller.pageReverse = p.getBoolean(PREF_PAGE_REVERSE, false)
        controller.symKeycode = p.getInt(PREF_SYM_KEYCODE, KeyEvent.KEYCODE_SYM)
        val alt = p.getInt(PREF_ALT_KEYCODE, -1)
        controller.altKeycodes =
            if (alt > 0) intArrayOf(alt) else intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)

        showSoftKeyboard = p.getBoolean(PREF_SHOW_SOFT, true)
        candidateBar?.swipeSelectEnabled = p.getBoolean(PREF_SWIPE_SELECT, true)
        candidateBar?.swipePageEnabled = p.getBoolean(PREF_SWIPE_PAGE, true)
        softKeyboard?.swipeSelectEnabled = p.getBoolean(PREF_KEYBOARD_SWIPE, true)
        softKeyboard?.swipePageEnabled = p.getBoolean(PREF_KEYBOARD_SWIPE, true)
        softKeyboard?.zoneWeights = p.getString(PREF_HZ_SPLIT, "1,2,4,2,1")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }?.toIntArray()
            ?: intArrayOf(1, 2, 4, 2, 1)
        softKeyboard?.visibility = if (showSoftKeyboard) View.VISIBLE else View.GONE
        updateUi()
    }

    private fun toggleSoftKeyboardPref() {
        ImeRuntime.prefs.edit().putBoolean(PREF_SHOW_SOFT, !showSoftKeyboard).apply()
    }

    // ------------------------------------------------------------------ 渲染

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun updateUi() {
        // 词库后台线程加载完成会触发 render,统一调度回主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateUi() }
            return
        }
        val s: ImeState = controller.state
        val modeText = when (s.mode) {
            ImeMode.CN -> getString(R.string.mode_cn)
            ImeMode.EN -> getString(R.string.mode_en)
        }
        val layerTag = when (s.effectiveLayer) {
            Layer.L2 -> getString(R.string.mode_alt)
            Layer.L3 -> getString(R.string.mode_sym)
            Layer.L1 -> ""
        }
        val lockTag = if (s.lock != null) getString(R.string.lock_badge) else ""
        statusMode?.text = modeText + layerTag + lockTag
        candidateBar?.setState(s)
        softKeyboard?.setState(s)

        // 单行两态:组词中整行给候选,空闲时整行给状态
        val composingNow = s.hasComposition
        statusRow?.visibility = if (composingNow) View.GONE else View.VISIBLE
        candidateRow?.visibility = if (composingNow) View.VISIBLE else View.GONE

        // 组词区同步:编辑框里显示下划线拼音
        val ic = currentInputConnection
        if (s.raw != composing && ic != null) {
            if (s.raw.isEmpty()) {
                if (composing.isNotEmpty()) ic.finishComposingText()
            } else {
                ic.setComposingText(s.raw, 1)
            }
            composing = s.raw
        }
    }

    companion object {
        private const val TAG = "ImeService"

        /** InputDevice.SOURCE_TOUCHPAD(避免 SDK 版本差异直接用常量)。 */
        private const val SOURCE_TOUCHPAD_MASK = 0x100008

        /** 键盘表面触摸坐标 X 上限(dumpsys 实测 raw 0..1440)。 */
        private const val FLY_X_MAX = 1440f
        const val PREF_SHOW_SOFT = "show_soft_keyboard"
        const val PREF_PAGE_SIZE = "candidate_page_size"
        const val PREF_FULLWIDTH = "fullwidth_symbols_in_cn"
        const val PREF_PAGE_REVERSE = "page_reverse"
        const val PREF_SWIPE_SELECT = "swipe_select"
        const val PREF_SWIPE_PAGE = "swipe_page"
        const val PREF_KEYBOARD_SWIPE = "keyboard_swipe"
        const val PREF_HZ_SPLIT = "hz_split"
        const val PREF_SYM_KEYCODE = "sym_keycode"
        const val PREF_ALT_KEYCODE = "alt_keycode"
    }
}
