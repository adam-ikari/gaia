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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.R
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.ime.ImeController
import dev.hwkbd.ime.ime.ImeHost
import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftState
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyLayoutStore
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.PinyinInputMethod
import dev.hwkbd.ime.ui.CandidateBarView
import dev.hwkbd.ime.ui.ImeRootView
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

    private var statusRow: View? = null
    private var candidateRow: View? = null
    private var chipMode: TextView? = null
    private var chipAlt: TextView? = null
    private var chipSym: TextView? = null
    private var chipShift: TextView? = null
    private var preeditRow: TextView? = null
    private var l4Panel: dev.hwkbd.ime.ui.L4PanelView? = null
    private var showL4Panel = false

    /** L4 面板当前键帽对应的签名([ImeController.l4Signature]);变了才重建键帽。 */
    private var l4PanelSig: String? = null

    /** L5 行态(状态栏上方独立行):溢出有无 / 行是否被点开 / 组词态;由 L4 面板测量回调驱动。 */
    private var l5HasOverflow = false
    private var l5RowShown = false
    private var l5Composing = false
    private var l4ShownNow = false
    private var l5Panel: dev.hwkbd.ime.ui.L4PanelView? = null
    private var btnL5: ImageButton? = null

    /**
     * 三颗互斥芯片(大写 ab / ALT / SYM):**同一时刻只亮一颗**,后亮起来的把先前那颗压灰。
     * [litLayerPrev]/[litCapsPrev] 是上一次渲染时各自"是否生效",用来分辨这次是谁刚亮。
     */
    private var litChip = LitChip.NONE
    private var litLayerPrev: Layer? = null
    private var litCapsPrev = false

    private enum class LitChip { NONE, CAPS, ALT, SYM }

    private var candidateBar: CandidateBarView? = null
    private var imeRoot: dev.hwkbd.ime.ui.ImeRootView? = null
    private var lastComposingForInsets = false
    private var theme = dev.hwkbd.ime.ui.ImeTheme.DEFAULT

    /** 当前组词区内容(render 与 onUpdateSelection 对账用)。 */
    private var composing = ""

    /**
     * 拼音悬浮条的显示条件:App 自己渲染 composing 区(EditText 类会在
     * onUpdateSelection 里回报组词区)就藏起来,只给 Termux 这类不回报的显示。
     * 组词开始后 [PREEDIT_PROBE_MS] 内没等到回报 → 判定 App 不渲染,本会话放行。
     */
    private var appRendersComposing = false
    private var bubbleAllowed = false
    private var preeditProbeArmed = false

    private val preeditProbe = Runnable {
        preeditProbeArmed = false
        if (!appRendersComposing && controller.state.hasComposition) {
            bubbleAllowed = true
            updateUi()
        }
    }

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
        // 窗口背景必须透明:组词态窗口拉到全屏,若 Theme.InputMethod 的 windowBackground
        // 不透明,会把背后 App 整片盖住(Termux 表现为"界面空白")。
        window?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        ImeRuntime.init(this)
        val dict = PinyinDict()
        val engine = PinyinEngine(dict, ImeRuntime.userDict)
        // 策略注册表:出厂语言/输入框表现 + 本进程的拼音输入法(加语言/输入法只需注册)
        val registry = ImeRegistry.builtin().registerMethod(PinyinInputMethod(engine))
        controller = ImeController(host, KeyLayoutStore.load(this), registry)
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
        imeRoot = root as? dev.hwkbd.ime.ui.ImeRootView
        imeRoot?.flyHandler = { ev -> handleFlyMotion(ev) }
        statusRow = root.findViewById(R.id.status_row)
        candidateRow = root.findViewById(R.id.candidate_row)
        chipMode = root.findViewById(R.id.chip_mode)
        chipAlt = root.findViewById(R.id.chip_alt)
        chipSym = root.findViewById(R.id.chip_sym)
        chipShift = root.findViewById(R.id.chip_shift)
        preeditRow = root.findViewById(R.id.preedit_row)
        l5Panel = root.findViewById<dev.hwkbd.ime.ui.L4PanelView>(R.id.l5_panel).apply {
            tailMode = true // L5 行只显示 L4 装不下的尾巴([tailFrom] 起)
            onL4Key = { controller.commitL4Key(it) } // 漏接则 L5 键点了没反应(2026-09-29 修)
        }
        l4Panel = root.findViewById<dev.hwkbd.ime.ui.L4PanelView>(R.id.l4_panel).apply {
            onL4Key = { controller.commitL4Key(it) }
            onOverflowChange = { hasL5, cut ->
                l5HasOverflow = hasL5
                if (!hasL5) l5RowShown = false // 没溢出了就收回,避免下次出现时自动弹开
                l5Panel?.tailFrom = cut
                l5Panel?.requestLayout()
                syncL5()
            }
        }
        // 齿轮左侧键盘图标:点按切换**状态栏上方 L5 行**的显隐(无溢出时不出现)
        btnL5 = root.findViewById<ImageButton>(R.id.btn_l5).apply {
            setOnClickListener {
                l5RowShown = !l5RowShown
                syncL5()
            }
        }
        candidateBar = root.findViewById<CandidateBarView>(R.id.candidate_bar).apply {
            onSelect = { controller.selectCandidate(it) }
            onLongPress = { controller.removeUserWord(it) }
            onPage = { controller.pageStep(it) }
        }
        root.findViewById<ImageButton>(R.id.btn_settings).apply {
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@ImeService, dev.hwkbd.ime.settings.SettingsActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        root.findViewById<ImageButton>(R.id.btn_hide_ime).setOnClickListener { requestHideSelf(0) }
        // 状态栏触摸切换:模式芯片 = 循环语言(注册表序);层芯片 ALT/SYM 单击=单次切换 / 长按=锁定切换;大写芯片 = Caps 开关
        chipMode?.setOnClickListener { controller.cycleLanguage() }
        chipAlt?.setOnClickListener { controller.tapLayerChip(Layer.L2) }
        chipAlt?.setOnLongClickListener {
            controller.longPressLayerChip(Layer.L2)
            true
        }
        chipSym?.setOnClickListener { controller.tapLayerChip(Layer.L3) }
        chipSym?.setOnLongClickListener {
            controller.longPressLayerChip(Layer.L3)
            true
        }
        chipShift?.setOnClickListener { controller.tapShiftChip() }
        applyPrefs()
        updateUi()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 层映射可能被设置页改过,每次开始输入重新加载(开销可忽略)
        controller.layout = KeyLayoutStore.load(this)
        // **输入类型决定上下文**:网址/邮件框换 L4 键位套,数字类聚焦即锁数字层
        controller.setFieldKind(FieldKind.from(attribute?.inputType ?: InputType.TYPE_NULL))
        // 密码框置安全标志:框内禁止切语言(只能英文),见 ImeState.secure
        controller.state.secure = isPasswordField(attribute)
        l4PanelSig = null // 键位套变了,下次渲染重建 L4 面板
        composing = ""
        // 每次开始输入都重新判定:换一个输入框,App 是否就地渲染组词区可能不同
        mainHandler.removeCallbacks(preeditProbe)
        preeditProbeArmed = false
        appRendersComposing = false
        bubbleAllowed = false
        controller.resetForNewInput()
        // 密码框强制英文态,避免拼音组词混入密码显示
        if (isPasswordField(attribute) &&
            controller.state.languageId != controller.registry.secureLanguageId
        ) {
            controller.setLanguage(controller.registry.secureLanguageId)
        } else if (!restarting) {
            // 输入框类型带默认语言(邮箱框默认英文,2026-09-29 用户定案):
            // 换框即恢复默认;同框重启动(restarting)不动,不打断框内手动切的语言
            controller.registry.fieldProfile(controller.state.fieldKind)
                .defaultLanguageId?.let { controller.setLanguage(it) }
        }
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
        android.util.Log.i(
            TAG,
            "updateSelection sel=$newSelStart..$newSelEnd comp=$candidatesStart..$candidatesEnd raw=$composing",
        )
        // App 回报了与我们一致的组词区 → 它自己会把拼音显示在输入框里,悬浮条让位
        if (composing.isNotEmpty() && candidatesStart >= 0 && candidatesEnd == candidatesStart + composing.length) {
            appRendersComposing = true
            mainHandler.removeCallbacks(preeditProbe)
            preeditProbeArmed = false
        }
        if (controller.state.hasComposition && composing.isNotEmpty()) {
            val expected = candidatesStart + composing.length
            if (candidatesEnd != expected) {
                composing = ""
                controller.abandonComposition()
            }
        }
    }

    /**
     * 飞字窗口策略:键盘表面被固件映射为屏幕触摸,落点遍布屏幕,
     * 只有窗口全屏才能收到。组词中窗口临时全屏(透明、内容贴底),
     * 条上方区域全部转飞字手势;非组词恢复 48dp 细条,不挡应用。
     *
     * 注意:窗口全屏 ≠ 内容全屏。默认实现把 contentTopInsets 取成根视图顶(=0),
     * 背后 App 会认为输入法内容占满全屏,把自己的内容区压成 0——
     * Termux 在此状态下终端整片空白,并主动 hideSoftInput(状态条/候选条一起消失)。
     * 所以这里显式上报**最上面一条可见的输入法内容**:组词态与空闲态(状态行)恒等,
     * App 侧既不 resize 也不 pan;L5 行(状态栏上方)出现时改报 L5 行顶——
     * 那是真实内容边界,App 必须让出这块空间,否则 L5 会盖在 App 内容上。
     * 触摸范围仍用整个窗口框架,飞字手势不受影响。
     */
    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null) return
        val row = when {
            l5Panel?.visibility == View.VISIBLE -> l5Panel
            candidateRow?.visibility == View.VISIBLE -> candidateRow
            else -> statusRow
        } ?: return
        val loc = IntArray(2)
        row.getLocationInWindow(loc)
        outInsets.contentTopInsets = loc[1]
        outInsets.visibleTopInsets = loc[1]
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }

    private fun applyWindowMode(composing: Boolean) {
        val root = imeRoot ?: run { android.util.Log.i(TAG, "winmode: imeRoot null"); return }
        android.util.Log.i(TAG, "winmode: composing=$composing")
        root.flyMode = composing
    }

    private fun notifyInsetsChanged() {
        window?.window?.decorView?.requestLayout()
    }

    /**
     * 飞字:键盘表面(TOUCHPAD)滑动选字。
     * 事件经全屏 touchable 区送达输入法窗口,坐标空间约 1440x720。
     *
     * 手势分两段:一上滑就进入拖动(词随手指缓慢起升、其余候选渐隐),
     * 位移越过阈值立刻提交,剩下的飞字动画由候选栏自己飞完;
     * 松手还没过阈值就把拖出来的效果淡回去。
     */
    private class FlyDetector(private val listener: Listener) {
        interface Listener {
            /** 拖动中:progress = 上移距离/阈值,0..1(词已开始起升)。 */
            fun onFlyDrag(zone: Int, progress: Float)

            /** 越过阈值:立即提交飞字(完成段动画 + 选字上屏)。 */
            fun onFlyCommit(zone: Int)

            /** 拖动放弃(松手未过阈值 / 转为横滑 / 事件取消)。 */
            fun onFlyAbort()
        }

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var tracking = false
        private var zone = -1
        private var dragging = false
        private var committed = false

        /** 飞字触发上滑距离(屏幕坐标单位),设置页三档 100/160/240,默认 160。 */
        var threshold = 160f

        /**
         * 屏宽(服务按 displayMetrics 注入):5 区按屏宽平分——Titan 2 物理键盘正好 10 列,
         * 每区 = 2 个键列,分区边界与物理键列对齐(用户 2026-09-29 定案)。
         */
        var xMax = 1440f

        /** 上滑死区 = 阈值 1/4:位移更小视为抖动忽略,不启动拖动(词不起升、其余候选不渐隐)。 */
        private val deadZone: Float get() = threshold / 4f

        /** 返回值:null=无手势,>=0 飞字区号,-1=上一页,-2=下一页。 */
        fun onMotion(action: Int, x: Float, y: Int): Int? {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y.toFloat()
                    downAt = System.currentTimeMillis()
                    tracking = true
                    zone = -1
                    dragging = false
                    committed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || committed) return null
                    val dy = downY - y
                    val dx = x - downX
                    // 回拖或横向为主:放弃飞字(翻页在 UP 判定)
                    if (dy <= 0f || kotlin.math.abs(dx) >= dy) {
                        abortDrag()
                        return null
                    }
                    // 死区:上滑还不到阈值 1/4 时整体忽略(已起拖的拖回死区则淡回放弃)
                    if (dy <= deadZone) {
                        abortDrag()
                        return null
                    }
                    if (zone < 0) zone = zoneOf(downX)
                    dragging = true
                    // 过死区后进度从 0 重算,过阈值语义不变(仍 dy ≥ threshold 提交)
                    val progress = (dy - deadZone) / (threshold - deadZone)
                    listener.onFlyDrag(zone, progress.coerceAtMost(1f))
                    if (progress >= 1f) {
                        // 过阈值即提交:抬手不再重复判定,完成段动画交给候选栏
                        committed = true
                        dragging = false
                        listener.onFlyCommit(zone)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasTracking = tracking
                    tracking = false
                    if (committed) return null
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) listener.onFlyAbort()
                    if (!wasTracking || action == MotionEvent.ACTION_CANCEL) return null
                    val dy = downY - y
                    val dx = x - downX
                    val adx = kotlin.math.abs(dx)
                    val dt = System.currentTimeMillis() - downAt
                    android.util.Log.i(TAG, "fly judge: dy=${dy.toInt()} dx=${dx.toInt()} dt=$dt thr=${threshold.toInt()}")
                    if (dt > 1500) return null
                    // 横向主位移 → 翻页:左滑=下一页(内容拖拽隐喻,与候选栏手势一致)
                    if (adx > 60f && adx > dy) return if (dx < 0) PAGE_NEXT else PAGE_PREV
                    // 纵向主位移(向上):兜底(拖动过程的 MOVE 丢失时仍能提交)
                    if (dy > threshold && dy > adx) return zoneOf(downX)
                }
            }
            return null
        }

        private fun abortDrag() {
            if (dragging) {
                dragging = false
                listener.onFlyAbort()
            }
        }

        /** 全宽平分 5 区(= 每区 2 个物理键列),返回 downX 落在哪一区。 */
        private fun zoneOf(x: Float): Int {
            val f = x / xMax * FLY_ZONES
            return (f.toInt()).coerceIn(0, FLY_ZONES - 1)
        }
    }

    /** 键盘表面触摸(经 ImeRootView 转发,y<0 为窗口外飞字区):判定并执行手势。 */
    private fun handleFlyMotion(ev: MotionEvent): Boolean {
        val zone = flyDetector.onMotion(ev.actionMasked, ev.rawX, ev.rawY.toInt())
        if (zone == null) return true
        when {
            zone >= 0 -> commitFly(zone) // 兜底提交路径
            zone == PAGE_NEXT -> {
                android.util.Log.i(TAG, "fly page: next")
                controller.pageStep(1)
            }
            else -> {
                android.util.Log.i(TAG, "fly page: prev")
                controller.pageStep(-1)
            }
        }
        return true
    }

    private val flyDetector = FlyDetector(object : FlyDetector.Listener {
        override fun onFlyDrag(zone: Int, progress: Float) {
            if (!controller.state.hasComposition) return
            candidateBar?.beginFlyDrag(zone)
            candidateBar?.setFlyDrag(progress)
        }

        override fun onFlyCommit(zone: Int) = commitFly(zone)

        override fun onFlyAbort() {
            candidateBar?.cancelFlyDrag()
        }
    })

    /** 越过阈值:先把完成段动画挂上(flyP 置位后 render 才会保持候选行可见),再选字上屏。 */
    private fun commitFly(zone: Int) {
        val has = controller.state.hasComposition
        android.util.Log.i(TAG, "fly select: zone=$zone hasComposition=$has")
        if (!has) {
            candidateBar?.cancelFlyDrag()
            return
        }
        val word = controller.state.pageSlots().getOrNull(zone)?.word ?: ""
        candidateBar?.startFlyAnim(zone, word)
        controller.selectCandidate(zone)
        mainHandler.postDelayed({ updateUi() }, 260) // 动画结束后两态归位
    }

    // ------------------------------------------------------------------ 窗口策略

    /** 有硬键盘也要显示输入视图:候选栏是硬件键盘方案的核心选字通道。 */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** 密码框不进拼音模式,直接透传(仍保留层与候选通道);变体判定见 [FieldKind.isPassword]。 */
    private fun isPasswordField(attribute: EditorInfo?): Boolean =
        FieldKind.isPassword(attribute?.inputType ?: InputType.TYPE_NULL)

    // ------------------------------------------------------------------ 偏好

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    private fun applyPrefs() {
        val p = ImeRuntime.prefs
        controller.state.pageSize = PAGE_SIZE // 固定每页 5 候选,与 5 区上滑一一对应
        controller.fullWidthSymbolsInCn = p.getBoolean(PREF_FULLWIDTH, true)
        controller.pageReverse = p.getBoolean(PREF_PAGE_REVERSE, false)
        controller.symKeycode = p.getInt(PREF_SYM_KEYCODE, KeyEvent.KEYCODE_SYM)
        val alt = p.getInt(PREF_ALT_KEYCODE, -1)
        controller.altKeycodes =
            if (alt > 0) intArrayOf(alt) else intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)

        candidateBar?.swipeSelectEnabled = p.getBoolean(PREF_SWIPE_SELECT, true)
        candidateBar?.swipePageEnabled = p.getBoolean(PREF_SWIPE_PAGE, true)
        migrateFlyThreshold(p)
        val flyThreshold = p.getString(PREF_FLY_THRESHOLD, FLY_THRESHOLD_DEFAULT)?.toFloatOrNull()
            ?: FLY_THRESHOLD_DEFAULT.toFloat()
        flyDetector.threshold = flyThreshold
        // 屏宽注入:5 区按屏宽平分 = 每区 2 个物理键列(Titan 2 正好 10 列)
        flyDetector.xMax = resources.displayMetrics.widthPixels.toFloat()
        candidateBar?.flyThresholdPx = flyThreshold // 候选行内起手的上滑用同一阈值
        theme = dev.hwkbd.ime.ui.ImeTheme.byId(p.getString(PREF_THEME, "obsidian"))
        candidateBar?.setTheme(theme)
        statusRow?.setBackgroundColor(theme.barBg)
        candidateRow?.setBackgroundColor(theme.barBg)
        l5Panel?.setBackgroundColor(theme.barBg)
        showL4Panel = p.getBoolean(PREF_SHOW_L4, true)
        l4PanelSig = null // 主题可能变了,下次渲染重建键帽
        // INVISIBLE 占位:中区留着,右侧按钮位置不跳
        l4Panel?.visibility = if (showL4Panel) View.VISIBLE else View.INVISIBLE
        updateUi()
    }

    /**
     * 飞字阈值整体上调(触发距离更大):v2 15/25/40 → 25/40/60,v3 再翻倍 → 50/80/120,
     * v4 再翻倍 → 100/160/240(用户 2026-09-28:"飞字的阈值需要加大 而不是缩小")。
     * 各为一次性迁移(带标记),否则已在新档位上的值会被反复上调。
     */
    private fun migrateFlyThreshold(p: SharedPreferences) {
        if (!p.getBoolean(PREF_FLY_THRESHOLD_V2, false)) {
            val mapped = when (p.getString(PREF_FLY_THRESHOLD, null)) {
                "15" -> "25"
                "25" -> "40"
                "40" -> "60"
                null -> "40"
                else -> p.getString(PREF_FLY_THRESHOLD, FLY_THRESHOLD_DEFAULT)
            }
            p.edit().putString(PREF_FLY_THRESHOLD, mapped).putBoolean(PREF_FLY_THRESHOLD_V2, true).apply()
        }
        if (!p.getBoolean(PREF_FLY_THRESHOLD_V3, false)) {
            val mapped = when (p.getString(PREF_FLY_THRESHOLD, null)) {
                "25" -> "50"
                "40" -> "80"
                "60" -> "120"
                null -> FLY_THRESHOLD_DEFAULT
                else -> p.getString(PREF_FLY_THRESHOLD, FLY_THRESHOLD_DEFAULT)
            }
            p.edit().putString(PREF_FLY_THRESHOLD, mapped).putBoolean(PREF_FLY_THRESHOLD_V3, true).apply()
        }
        if (!p.getBoolean(PREF_FLY_THRESHOLD_V4, false)) {
            val mapped = when (p.getString(PREF_FLY_THRESHOLD, null)) {
                "50" -> "100"
                "80" -> "160"
                "120" -> "240"
                null -> FLY_THRESHOLD_DEFAULT
                else -> p.getString(PREF_FLY_THRESHOLD, FLY_THRESHOLD_DEFAULT)
            }
            p.edit().putString(PREF_FLY_THRESHOLD, mapped).putBoolean(PREF_FLY_THRESHOLD_V4, true).apply()
        }
    }

    // ------------------------------------------------------------------ 渲染

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 状态徽标:全部标签化。语言芯片=注册表策略(首个注册语言主色实心);层芯片 锁=实心 / 一次性=描边。 */
    private fun renderChips(s: ImeState) {
        val t = theme
        val lang = controller.registry.language(s.languageId)
        // 数字框:状态按钮全部隐藏,只留 Alt 芯片(文字 = 实际字符集 0-9/0-9./0-9+*#…)——数字就靠它
        val digitsOnly = controller.registry.fieldProfile(s.fieldKind).digitsOnly
        chipMode?.visibility = if (digitsOnly) View.GONE else View.VISIBLE
        chipMode?.apply {
            text = lang.chipLabel
            setTextColor(0xFF16181D.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4f * resources.displayMetrics.density
                setColor(if (s.languageId == controller.registry.primaryLanguageId) t.accent else t.fg)
            }
        }
        // 层芯片 alt/sym **常显**(打普通字母时没有"当前层"图标,只是两颗灰芯片):
        // 未切到的层 = 灰描边;切到 = 该层主色(锁定=实心 / 单次=描边)。
        // 谁亮着:**三颗互斥(大写/ALT/SYM),同一时刻只亮一颗**——
        // 刚生效的那颗赢(后按的压过先前);两个都还生效就维持上次亮着的那颗;
        // 亮着的那颗失效了就退给另一颗还生效的;全都没生效 = 全灰。
        val layerNow: Layer? = s.effectiveLayer.takeIf { it == Layer.L2 || it == Layer.L3 }
        val capsNow = s.shift != ShiftState.OFF || s.shiftHeld
        val layerJustOn = layerNow != null && layerNow != litLayerPrev
        val capsJustOn = capsNow && !litCapsPrev
        litChip = when {
            layerJustOn -> if (layerNow == Layer.L2) LitChip.ALT else LitChip.SYM
            capsJustOn -> LitChip.CAPS
            litChip == LitChip.ALT && layerNow == Layer.L2 -> LitChip.ALT
            litChip == LitChip.SYM && layerNow == Layer.L3 -> LitChip.SYM
            litChip == LitChip.CAPS && capsNow -> LitChip.CAPS
            layerNow != null -> if (layerNow == Layer.L2) LitChip.ALT else LitChip.SYM
            capsNow -> LitChip.CAPS
            else -> LitChip.NONE
        }
        litLayerPrev = layerNow
        litCapsPrev = capsNow

        // 轮到它亮才上色(锁定=实心 / 单次=描边);没轮到就整颗灰描边(哪怕该层正生效)。
        // Alt 芯片文字随**输入类型**变:数字框按实际可输字符集显示(0-9、0-9.、0-9+*#,只改这颗芯片的显示)。
        val altLabel = controller.registry.fieldProfile(s.fieldKind).altChipLabel
        renderLayerChip(chipAlt, Layer.L2, altLabel, t.chipL2, s, t, litChip == LitChip.ALT)
        if (digitsOnly) {
            // 数字框:SYM/大写状态与数字无关,连同语言芯片一起藏掉
            chipSym?.visibility = View.GONE
            chipShift?.visibility = View.GONE
        } else {
            renderLayerChip(chipSym, Layer.L3, "sym", t.chipL3, s, t, litChip == LitChip.SYM)

            // 大写状态:语言策略说不显示就 GONE(拼音不需要大小写,「拼」右边的 alt/sym 紧贴过来);
            // 英文态用 ab / Ab / AB 直接表状态——小写 / 大写一次(或正按住) / 大写锁定(可触摸开关)。
            // 中英切换时中区(L4)不挪,靠的是 chip_row 那个**权重定宽的盒子**(不再是这颗芯片占位)。
            // 没轮到它亮(含大写根本没生效)= 灰描边——ab **不再常亮**。
            when {
                !lang.showShiftChip -> chipShift?.visibility = View.GONE
                litChip == LitChip.CAPS && s.shift == ShiftState.CAPS ->
                    renderChip(chipShift, "AB", t.accent, solid = true)
                litChip == LitChip.CAPS ->
                    renderChip(chipShift, "Ab", t.accent, solid = false)
                s.shift == ShiftState.CAPS -> renderChip(chipShift, "AB", t.hint, solid = false)
                s.shift == ShiftState.ONCE || s.shiftHeld -> renderChip(chipShift, "Ab", t.hint, solid = false)
                else -> renderChip(chipShift, "ab", t.hint, solid = false)
            }
        }
    }

    /** 层芯片:目标层生效 → 该层主色(锁定实心 / 单次描边);没生效 → 整颗灰描边。 */
    private fun renderLayerChip(
        chip: TextView?,
        target: Layer,
        label: String,
        color: Int,
        s: ImeState,
        t: dev.hwkbd.ime.ui.ImeTheme,
        lit: Boolean,
    ) {
        if (lit && s.effectiveLayer == target) renderChip(chip, label, color, solid = s.lock == target)
        else renderChip(chip, label, t.hint, solid = false)
    }

    /** 芯片渲染:实心 = 锁定/持续状态,描边 = 一次性/临时状态。 */
    private fun renderChip(chip: TextView?, label: String, color: Int, solid: Boolean) {
        val density = resources.displayMetrics.density
        chip ?: return
        chip.text = label
        chip.visibility = View.VISIBLE
        if (solid) {
            chip.setTextColor(0xFF16181D.toInt())
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                setColor(color)
            }
        } else {
            chip.setTextColor(color)
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1.2f * density).toInt(), color)
            }
        }
    }

    /**
     * L5 行 + 齿轮左侧键盘图标:无溢出/L4 整体隐藏时图标 GONE(右区 gravity=end 右贴,
     * 齿轮/收起位置不跳);图标点开时**状态栏上方的 L5 行**出现并高亮;组词态整行让位。
     * L5 行会改变 contentTopInsets——切换后要通知窗口重算 insets,让 App 相应 resize。
     */
    private fun syncL5() {
        val enabled = l4ShownNow && l5HasOverflow
        btnL5?.visibility = if (enabled) View.VISIBLE else View.GONE
        btnL5?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (l5RowShown && enabled) theme.accent
            else androidx.core.content.ContextCompat.getColor(this, R.color.status_fg),
        )
        val rowVisible = enabled && l5RowShown && !l5Composing
        val cur = l5Panel?.visibility == View.VISIBLE
        l5Panel?.visibility = if (rowVisible) View.VISIBLE else View.GONE
        if (rowVisible != cur) notifyInsetsChanged()
    }

    private fun updateUi() {
        // 词库后台线程加载完成会触发 render,统一调度回主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateUi() }
            return
        }
        val s: ImeState = controller.state
        renderChips(s)
        candidateBar?.setState(s)

        // L4 中区随输入类型/语言变化:签名没变不重建键帽(每变一次就是整排视图重建)
        val l4Sig = controller.l4Signature()
        if (l4Sig != l4PanelSig) {
            l4PanelSig = l4Sig
            val keys = controller.l4Keys()
            l4Panel?.refresh(keys, theme)
            l5Panel?.refresh(keys, theme) // 同一份键序,L5 取 L4 装不下的尾巴
        }
        // 数字框限定 0-9:核心键对数字没有意义,整块隐藏(设置页开关只管非数字态)
        val l4Shown = showL4Panel && !controller.registry.fieldProfile(s.fieldKind).digitsOnly
        l4Panel?.visibility = if (l4Shown) View.VISIBLE else View.INVISIBLE
        l4ShownNow = l4Shown
        syncL5()

        // 拼音悬浮条:只在 App 不渲染 composing 区时显示(宽度随文字,见 ime_root.xml)。
        // 组词一开始先等 App 回报,超时没回报才判定"App 不渲染"并放行
        if (s.raw.isNotEmpty() && !appRendersComposing && !bubbleAllowed && !preeditProbeArmed) {
            preeditProbeArmed = true
            mainHandler.postDelayed(preeditProbe, PREEDIT_PROBE_MS)
        }
        preeditRow?.apply {
            val raw = s.raw
            if (raw.isEmpty() || !bubbleAllowed || appRendersComposing) {
                if (visibility != View.GONE) visibility = View.GONE
            } else {
                if (visibility != View.VISIBLE) visibility = View.VISIBLE
                if (text.toString() != raw) text = raw
            }
        }

        // 单行两态:组词中整行给候选,空闲时整行给状态
        // 飞字动画期间保持候选行可见(否则动画没画布),结束后再切回状态行
        val composingNow = s.hasComposition || (candidateBar?.isFlyAnimating() == true)
        statusRow?.visibility = if (composingNow) View.GONE else View.VISIBLE
        candidateRow?.visibility = if (composingNow) View.VISIBLE else View.GONE
        if (composingNow != l5Composing) {
            l5Composing = composingNow // L5 行属空闲态:组词时整行让位(具体显隐 syncL5 判)
            syncL5()
        }
        if (composingNow != lastComposingForInsets) {
            lastComposingForInsets = composingNow
            applyWindowMode(composingNow) // 组词中窗口全屏接键盘表面触摸,空闲恢复细条
        }

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

        /** 每页候选固定 5 条,与键盘面平分 5 区上滑一一对应。 */
        const val PAGE_SIZE = 5

        /** InputDevice.SOURCE_TOUCHPAD(避免 SDK 版本差异直接用常量)。 */
        private const val SOURCE_TOUCHPAD_MASK = 0x100008

        /** 键盘面平分 5 区上滑选字(每区 = 2 个物理键列,屏宽按 displayMetrics 注入 FlyDetector.xMax)。 */
        private const val FLY_ZONES = 5

        /** 手势返回码:飞字用区号(>=0),翻页用负数。 */
        private const val PAGE_PREV = -1
        private const val PAGE_NEXT = -2
        const val PREF_FULLWIDTH = "fullwidth_symbols_in_cn"
        const val PREF_PAGE_REVERSE = "page_reverse"
        const val PREF_SWIPE_SELECT = "swipe_select"
        const val PREF_SWIPE_PAGE = "swipe_page"
        const val PREF_FLY_THRESHOLD = "fly_threshold"
        const val PREF_SHOW_L4 = "show_l4_panel"

        /** 飞字阈值默认档(更保守:需明显上滑才触发)。 */
        const val FLY_THRESHOLD_DEFAULT = "160"

        /** 组词开始后等 App 回报组词区的时限:没等到 = App 不渲染组词区,悬浮拼音上场。 */
        private const val PREEDIT_PROBE_MS = 100L

        /** 飞字阈值档位上调的一次性迁移标记(v2/v3/v4 = 逐次翻倍一档)。 */
        const val PREF_FLY_THRESHOLD_V2 = "fly_threshold_v2"
        const val PREF_FLY_THRESHOLD_V3 = "fly_threshold_v3"
        const val PREF_FLY_THRESHOLD_V4 = "fly_threshold_v4"
        const val PREF_THEME = "theme"
        const val PREF_SYM_KEYCODE = "sym_keycode"
        const val PREF_ALT_KEYCODE = "alt_keycode"
    }
}
