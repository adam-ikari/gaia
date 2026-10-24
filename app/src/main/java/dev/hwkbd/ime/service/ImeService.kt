package dev.hwkbd.ime.service

import android.content.SharedPreferences
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.ImeRuntime
import dev.hwkbd.ime.R
import dev.hwkbd.ime.ime.Haptics
import dev.hwkbd.ime.ime.ImeController
import dev.hwkbd.ime.ime.ImeHost
import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.KeyEventGate
import dev.hwkbd.ime.ui.StatusRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import dev.hwkbd.ime.keymap.KeyLayoutStore
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.rime.RimeEngine
import dev.hwkbd.ime.strategy.PinyinInputMethod
import dev.hwkbd.ime.service.voice.StartResult
import dev.hwkbd.ime.service.voice.VoiceError
import dev.hwkbd.ime.service.voice.VoiceErrorKind
import dev.hwkbd.ime.service.voice.VoiceInput
import dev.hwkbd.ime.service.voice.VoiceSettings
import dev.hwkbd.ime.service.voice.VoiceState
import dev.hwkbd.ime.ui.CandidateBarView
import dev.hwkbd.ime.ui.ImeRootView
import java.util.concurrent.Executors

/**
 * 输入法服务:KeyEvent → ImeController → InputConnection。
 * 有硬键盘时系统默认不显示输入视图,这里强制显示(候选栏是主要选字通道)。
 */
class ImeService : InputMethodService() {

    private lateinit var controller: ImeController

    /**
     * 按键与滑动之间的门。2026-10-02 定案「按键后不响应键盘滑动」,2026-10-16 扩成两段:
     * **按住键期间一直挡 + 抬手后 [KeyEventGate.blockMs] 内也挡**(长度可配置,见 [PREF_KEY_GATE_MS])。
     *
     * **四个滑动入口全查**:飞字手势(本类 [handleFlyMotion],键盘表面 TOUCHPAD)、
     * 候选行([CandidateBarView])、L4([L4PanelView],预留)、L5 面板([L5PanelView])。
     * 点按与长按一律不查 —— 完整理由见 [dev.hwkbd.ime.ime.KeyEventGate] 的类注释。
     */
    private val keyGate = KeyEventGate()

    /** 本次飞字手势的滑动许可:起手落在静默窗里就整条不接(见 [handleFlyMotion])。 */
    private var flyArmed = true

    /** [warnZoneBasisDrift] 只报一次:漂移是布局属性,不是每笔手势会变的东西。 */
    private var zoneBasisDriftLogged = false

    private var statusRow: View? = null
    private var candidateRow: View? = null
    private var statusMode: TextView? = null
    private var statusAlt: TextView? = null
    private var statusSym: TextView? = null
    private var statusShift: TextView? = null
    private var statusFullWidth: TextView? = null
    private var preeditRow: TextView? = null
    private var voiceHudView: View? = null
    private var voiceHudText: TextView? = null
    private var voiceLevelView: dev.hwkbd.ime.ui.VoiceLevelView? = null
    private var voiceMic: android.widget.ImageView? = null
    private var l5PanelView: dev.hwkbd.ime.ui.L5PanelView? = null
    private var l4Panel: dev.hwkbd.ime.ui.L4PanelView? = null

    /** L4 面板当前键帽对应的签名([ImeController.l4Signature]);变了才重建键帽。 */
    private var l4PanelSig: String? = null

    /** L4 最近一次测出的溢出切点:场景 tab = `l4Keys().drop(l5Cut)`。 */
    private var l5Cut = 0
    /** 上次同步给按钮的面板态;变才重设图标(免得每帧重建 drawable)。 */
    private var syncedPanelMode: dev.hwkbd.ime.ime.PanelMode? = null
    private var btnL5: ImageButton? = null

    // ------------------------------------------------------------- 剪贴板历史(2026-10-16 用户定案)

    /** 内存剪贴板历史(最近 [dev.hwkbd.ime.ime.ClipHistory.DEFAULT_CAPACITY] 条,最新在前)。 */
    private val clipHistory = dev.hwkbd.ime.ime.ClipHistory()
    private var clipManager: android.content.ClipboardManager? = null
    private val clipListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener { readClipboard() }

    /**
     * 读一次系统剪贴板并记进历史。剪贴板监听在 onCreate 注册、全程生效(含 IME 隐藏时
     * 别的 App 复制触发回调)。Android 10+ 的后台读取限制对当前 IME 多半豁免,但豁免边界
     * 未在真机确证 —— `primaryClip` 读取包 try/catch 防一次 SecurityException 崩进程。
     * 敏感标记(密码管理器会设)只有 Android 13+ 有。
     */
    private fun readClipboard() {
        val clip = try { clipManager?.primaryClip ?: return } catch (_: SecurityException) { return }
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        val sensitive = if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras
                ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        } else {
            false
        }
        clipHistory.record(text, sensitive)
        controller.setL5Clipboard(clipHistory.entries)
    }

    private var candidateBar: CandidateBarView? = null
    private var imeRoot: dev.hwkbd.ime.ui.ImeRootView? = null

    /** 触感反馈(全应用唯一实例):锁定/翻页/飞字共用,总开关 [PREF_HAPTIC]。 */
    private var haptics: Haptics? = null
    private var lastComposingForInsets = false

    /** L5 面板磨砂:当前窗口是否已开背景模糊(避免每帧重设,见 [syncL5Blur])。 */
    private var l5BlurOn = false
    private var theme = dev.hwkbd.ime.ui.ImeTheme.DEFAULT

    /** 当前组词区内容(render 与 onUpdateSelection 对账用)。 */
    private var composing = ""

    /**
     * 我们刚写过一次组词区,正在等框架回报它。
     *
     * ## 为什么必须跳过那一次对账(2026-10-09 真机实测)
     *
     * 「部分消费后继续生成候选词」当时不工作,根因在这里。真机 `nihao` 选覆盖 `ni` 的那个词:
     *
     * ```
     * consume:        index=3 consumed=2 preview="泥好"
     * query#9:        from=选词后剩余 raw="hao" n=45
     * syncComposing:  wrote="hao" len=3 before=泥hao
     * updateSelection sel=1..1 comp=0..1  raw=hao      ← 框架报的**中间态**
     * ABANDON:        comp=0..1 expected=3 …           ← 对账把它当成「外力破坏」
     * updateSelection sel=1..1 comp=-1..-1 raw=
     * updateSelection sel=4..4 comp=1..4  raw=         ← App 真正的组词区 [1,4),长度 3,对得上
     * winmode: composing=false                          ← 窗口关了,用户看不到 hao 的候选
     * ```
     *
     * App 侧自始至终是好的(`before=泥hao`、组词区 `[1,4)`),
     * 只有**第一次**回报是陈旧中间态。而那次回报就是**我们自己那一次 `setComposingText`
     * 的回声** —— 对账存在的目的是抓第三方改动,把自己写的回声当成破坏,
     * 等于每次选词后都自杀一次。
     *
     * 所以:自己写完之后的那一次回报不对账,之后恢复。
     * 代价是万一框架没回报(极少数),下一次真正的外部改动会被漏掉一次 ——
     * 那一次漏判只是不清理组词,而误判的代价是**整个引擎被清掉**,两者不对称。
     */
    private var composingAckPending = false

    /**
     * 把 [composingAckPending] 掐掉:下一个按键 / 生命周期变化开始时调用。
     *
     * 「等到对得上才算确认」必须**有界** —— 万一某个 App 从不回报匹配的组词区,
     * 那个标志会一直挂着,把之后真正的外部改动全放过去。
     * 一次按键就把等待窗口关掉,是最自然也最保守的界。
     */
    private fun expireComposingAck() {
        composingAckPending = false
    }

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
            requestRender() // 组词区同步 + 合并渲染(见 requestRender)
        }

        /**
         * 长按字母键到点回调(300ms,主线程)—— 见 [ImeController.onLongPressFire]。
         * 语义:空闲且开关开 = 上屏该键 L2 符号;组词中且该键 L2 是 `'` = 插分隔符。
         */
        private val longPressRunnable = Runnable { controller.onLongPressFire() }

        override fun scheduleLongPress(code: Int) {
            // 新一次按压覆盖旧调度(连续快打时上个定时器必须让位)
            mainHandler.removeCallbacks(longPressRunnable)
            mainHandler.postDelayed(longPressRunnable, ImeController.HOLD_MS)
        }

        override fun cancelLongPress() {
            mainHandler.removeCallbacks(longPressRunnable)
        }

        override fun startVoiceInput(languageId: String): Boolean =
            when (voiceInput.start(languageId)) {
                StartResult.STARTED -> true
                StartResult.NO_PERMISSION -> {
                    toast(this@ImeService, R.string.voice_error_permission)
                    false
                }
                StartResult.UNAVAILABLE -> {
                    toast(this@ImeService, R.string.voice_error_config)
                    false
                }
                StartResult.UNSUPPORTED_LANGUAGE -> {
                    toast(this@ImeService, R.string.voice_error_language_local)
                    false
                }
                StartResult.FAILED -> {
                    toast(this@ImeService, R.string.voice_error_start)
                    false
                }
            }

        override fun stopVoiceInput() {
            voiceInput.stop()
        }

        override fun cancelVoiceInput() {
            voiceInput.cancel()
        }
    }

    /** 语音 HUD 文字(null = 不显示)。HUD 是条上方那枚悬浮的麦克风 + 音量表。 */
    private var voiceHud: String? = null

    // 显式类型:`host` 与 `voiceInput` 互相引用,不写死会触发 Kotlin 的递归类型推断
    private val voiceInput: VoiceInput = VoiceInput(
        this,
        settingsProvider = { VoiceSettings.read(ImeRuntime.prefs) },
        onState = { state, partial ->
            voiceHud = when (state) {
                VoiceState.PREPARING -> getString(R.string.voice_preparing)
                VoiceState.CONNECTING -> getString(R.string.voice_connecting)
                VoiceState.LISTENING ->
                    if (partial.isNullOrEmpty()) getString(R.string.voice_listening)
                    else getString(R.string.voice_partial, partial)
                VoiceState.RECOGNIZING ->
                    if (partial.isNullOrEmpty()) getString(R.string.voice_recognizing)
                    else getString(R.string.voice_partial, partial)
                VoiceState.IDLE -> null
            }
            syncVoiceHud()
        },
        onText = { text ->
            voiceHud = null
            syncVoiceHud()
            host.commitText(text)
            updateUi()
        },
        onError = { error ->
            voiceHud = null
            syncVoiceHud()
            updateUi()
            toastVoiceError(error)
        },
        onLevel = { level -> voiceLevelView?.setLevel(level) },
        onFallback = { toast(this, R.string.voice_fallback_local) },
    )

    /** 把 [voiceHud] 照到悬浮视图上;语音与拼音组词互斥,不会同时出现。 */
    private fun syncVoiceHud() {
        val view = voiceHudView ?: return
        val text = voiceHud
        if (text == null) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            voiceLevelView?.setLevel(0f)
            return
        }
        if (voiceHudText?.text?.toString() != text) voiceHudText?.text = text
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
    }

    private fun toast(context: android.content.Context, res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
    }

    /** 识别错误 → 用户能看懂的一句。服务商细节只放进带 `%s` 的模板,不泄露密钥。 */
    private fun toastVoiceError(error: VoiceError) {
        val res = when (error.kind) {
            VoiceErrorKind.NO_PERMISSION -> R.string.voice_error_permission
            VoiceErrorKind.UNAVAILABLE -> R.string.voice_error_config
            VoiceErrorKind.BUSY -> R.string.voice_error_busy
            VoiceErrorKind.NO_MATCH -> R.string.voice_error_no_match
            VoiceErrorKind.NETWORK -> R.string.voice_error_network
            VoiceErrorKind.LANGUAGE -> R.string.voice_error_language_local
            VoiceErrorKind.AUTH -> R.string.voice_error_auth
            VoiceErrorKind.PROVIDER -> R.string.voice_error_provider
            VoiceErrorKind.GENERIC -> R.string.voice_error_generic
        }
        val text = when {
            error.kind == VoiceErrorKind.PROVIDER && !error.detail.isNullOrBlank() ->
                getString(res, error.detail)
            error.kind == VoiceErrorKind.GENERIC && !error.detail.isNullOrBlank() ->
                getString(R.string.voice_error_generic_detail, error.detail)
            else -> getString(res)
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        // 窗口背景必须透明:组词态窗口拉到全屏,若 Theme.InputMethod 的 windowBackground
        // 不透明,会把背后 App 整片盖住(Termux 表现为"界面空白")。
        window?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        ImeRuntime.init(this)
        // **librime 是异步就绪的**:首次启动要把 assets/rime 里的 *.yaml 部署成 *.bin,
        // 几秒到几十秒,放主线程会 ANR。所以先注册一个「未就绪时返回空候选」的引擎,
        // 部署完成后再回调刷新 —— 期间界面正常可用,只是拼音查不出候选。
        //
        // 为什么不用旧引擎兜底:用户 2026-10-03「不用对比 直接替换」。未就绪期间的表现
        // 是「敲拼音没候选」,而不是崩溃或静默换引擎。
        val rime = RimeEngine(this)
        ImeRuntime.rime = rime
        val registry = ImeRegistry.builtin().registerMethod(PinyinInputMethod(rime))
        Thread({
            rime.prepare { ok ->
                android.util.Log.i(TAG, "rime 部署完成 ok=$ok version=${rime.version}")
                // 切回主线程再碰 UI:prepare 的回调在部署线程上
                // `from` 是这条埋点里**最要紧的字段**:2026-10-04 用户报「候选行自己
                // 交替闪烁」时,一眼就能看出是这里在异步改候选列表。
                mainHandler.post { if (ok) controller.refreshQuery(from = "部署完成回调") }
            }
        }, "rime-boot").apply { isDaemon = true }.start()
        // 触感:全应用唯一的 [Haptics] 实例,控制器(锁定类)与候选行(翻页/飞字类)共用,
        // 总开关从 PREF_HAPTIC 读(设置 → 常规 → 震动反馈)
        haptics = Haptics(this).apply { enabled = ImeRuntime.prefs.getBoolean(PREF_HAPTIC, true) }
        controller = ImeController(host, KeyLayoutStore.load(this), registry, haptics)
        // **所有选字入口的飞字动画都从这里出发**(2026-10-02 用户「按键选词也需要飞字动画」)。
        // 挂在 controller 的回调上而不是某个手势分支里:选字有九处入口(候选栏点选/上滑、
        // Shift/Sym/Alt/Ctrl 五键、方向键、空格、高亮确认),逐个补一遍必然漏。
        controller.onCandidateSelected = { zone, word ->
            candidateBar?.startFlyAnim(zone, word)
            // 组词已经被清掉了,候选行得留到动画画完再归位,否则词在半路就没了。
            // **真正归位的是 [CandidateBarView.onFlyFinished]**(动画自己报完成);
            // 这一句只是兜底,防"动画压根没跑起来"(例如候选行不在屏上)时没人归位。
            mainHandler.postDelayed({ updateUi() }, FLY_SETTLE_MS)
        }

        renderPump.startIn(scope) // 按键路径的合并渲染(见 RenderPump)
        ImeRuntime.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyPrefs()
        // 剪贴板监听(2026-10-16 用户「剪贴板功能:历史面板」):历史只在进程生命周期内累积
        // (用户没要跨重启保留)。注销在 [onDestroy]。
        clipManager = getSystemService(android.content.ClipboardManager::class.java)
        clipManager?.addPrimaryClipChangedListener(clipListener)
        // librime 的部署已经在上面起的 rime-boot 线程里跑了,这里不需要再做事。
        // 原先这里是「executor 读 assets/dict.txt.gz → PinyinDict.load → refreshQuery」,
        // 现在整块删掉:词典由 librime 自己从 files/rime/ 读,不需要应用预先建索引。
    }

    override fun onDestroy() {
        voiceInput.destroy()
        scope.cancel() // 敲击窗口事件流的收集(见 startGestureCollector)
        ImeRuntime.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // 原先这里 saveUserDict():旧引擎的用户词频是应用自己的 JSON,退出时要落盘。
        // librime 的用户词频在 files/rime/user/ 里由它自己同步,**退出时不需要通知** ——
        // 反过来说,如果这里还有保存动作,说明还在走旧引擎那条路。
        clipManager?.removePrimaryClipChangedListener(clipListener)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_root, null)
        imeRoot = root as? dev.hwkbd.ime.ui.ImeRootView
        imeRoot?.flyHandler = { ev -> handleFlyMotion(ev) }
        statusRow = root.findViewById(R.id.status_row)
        candidateRow = root.findViewById(R.id.candidate_row)
        statusMode = root.findViewById<TextView>(R.id.status_mode).apply {
            // 语言项点击 = 切换拼音/En(锁定层时也能切;层状态不响应触摸是另一套约定)
            setOnClickListener { controller.cycleLanguage() }
        }
        statusAlt = root.findViewById(R.id.status_alt)
        statusSym = root.findViewById(R.id.status_sym)
        statusShift = root.findViewById(R.id.status_shift)
        // 「全/半」开关:硬件键盘上没有这个物理键,状态行是唯一的入口(左区其余状态项不可触摸)。
        // 写的是**同一个偏好**设置页那个开关在用 —— 一处改两处都跟着变,不存在两个真相。
        statusFullWidth = root.findViewById<TextView>(R.id.status_fullwidth).apply {
            setOnClickListener {
                val next = !controller.fullWidthSymbolsInCn
                ImeRuntime.prefs.edit().putBoolean(PREF_FULLWIDTH, next).apply()
                // 偏好监听器会回调 applyPrefs(),但 SharedPreferences 的 apply() 是异步落盘的,
                // 立刻改内存值 + 走一遍渲染,点下去当场就变,不等回调
                controller.fullWidthSymbolsInCn = next
                // 面板开着时字符盘也要按新的 L1-L3 敲出集合重算(否则会停在旧的全角/半角一套)
                controller.refreshL5Content()
                updateUi()
            }
        }
        preeditRow = root.findViewById(R.id.preedit_row)
        voiceHudView = root.findViewById(R.id.voice_hud)
        voiceHudText = root.findViewById(R.id.voice_text)
        voiceLevelView = root.findViewById(R.id.voice_level)
        voiceMic = root.findViewById(R.id.voice_mic)
        syncVoiceHud()
        l4Panel = root.findViewById<dev.hwkbd.ime.ui.L4PanelView>(R.id.l4_panel).apply {
            onL4Key = { commitL4Tapped(it) }
            // L4 中区不接横滑(那条路留给飞字手势,见 L4PanelView 类注释),这里接门是为将来:
            // 万一它开始接手势,静默窗规则不用再记得补一处
            swipeGate = { keyGate.swipeBlocked() }
            onOverflowChange = { _, cut ->
                // L4 装不下的尾巴 = 原 L5 行 = 现在面板的「场景」tab(2026-10-19 合并)。
                // 回调是 post 出来的(见 L4PanelView.onMeasure),这里改状态/渲染是安全的。
                l5Cut = cut
                controller.setOverflowCut(cut)
            }
        }
        // L5 面板(原 L6 虚拟键盘 + 原 L5 溢出行合并):键走**与 L4 同一条**上屏通路
        // ([commitL4Tapped]),翻页/切 tab 交回控制器(页码夹取、到头不循环都在 [L5Ui] 里)。
        // 滑动手势也接静默窗(用户 2026-10-02「所有的键盘滑动都无效」)。
        l5PanelView = root.findViewById<dev.hwkbd.ime.ui.L5PanelView>(R.id.l5_panel).apply {
            onKey = { commitL4Tapped(it) }

            // 剪贴板 tab 的整行键 = **粘贴**:直接上屏,不进组词(纯字母内容也一样)。
            onRowKey = { out -> if (out is KeyOut.Text) controller.commitClipboardText(out.text) }
            onTab = { controller.l5SelectTab(it) }
            onSwipePage = { delta -> controller.l5StepPage(delta) }
            // 滑动手势也接静默窗(用户 2026-10-02「所有的键盘滑动都无效」)
            swipeGate = { keyGate.swipeBlocked() }
        }
        btnL5 = root.findViewById<ImageButton>(R.id.btn_l5).apply {
            // **L5 面板开关**(2026-10-19 合并定案):点一下 OFF↔L5,与 Sym+Space 同一通路
            // ([dev.hwkbd.ime.ime.ImeController.cyclePanel]);图标两种形态在 [syncL5] 里随态切。
            // 渲染由 cyclePanel 的 render → renderViews 带过来。
            setOnClickListener { controller.cyclePanel() }
        }
        candidateBar = root.findViewById<CandidateBarView>(R.id.candidate_bar).apply {
            setHaptics(haptics)
            onSelect = { controller.selectCandidate(it) }
            onLongPress = { controller.removeUserWord(it) }
            onPage = { controller.pageStep(it) }
            // 候选行横滑也接静默窗。**点按/长按不看它** —— 敲一个键紧接着点候选上屏是
            // 最常见的手速,一起挡掉比不挡更糟
            swipeGate = { keyGate.swipeBlocked() }
            // 飞字动画自己报完成 → 立刻重新渲染,两态才切得回去。
            // 缺这一步的报障:「偶发拼音都消费完还显示候选词栏」(候选行靠
            // isFlyAnimating 保持可见,动画结束时只有 invalidate、不渲染)
            onFlyFinished = { updateUi() }
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
        // 状态项**不再响应触摸**(2026-09-29 用户定案):只显示已触发的状态,
        // 切换层/语言/大写一律走物理按键(Alt / Sym / Shift / Shift+Space)。
        applyPrefs()
        updateUi()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // **调出即初始态**(2026-10-12 用户「每次调出输入法需要恢复到初始状态」):
        // 层/大写/修饰内部态全部归零。必须走在 [setFieldKind] **之前** ——
        // 数字类聚焦锁 L2 是这里重新建立的,复位放在后面会把它连坐掉。
        //
        // 此前只有 [onWindowHidden] 复位层状态,表单 next 框这类**窗口不隐藏**的
        // 切换会把上一框的锁定层/大写原样带过来 —— 那个缺口由这条补上。
        controller.resetLayers()
        // L4 自定义键每次开始输入重载(开销可忽略)。**L1-L3 不在这里变**:
        // 键位编辑器已下线,[KeyLayoutStore.load] 只从文件取 L4,物理三层永远走出厂表。
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
        } else {
            // 输入框类型自带的默认语言(目前只有邮箱 = 英文,2026-09-29 定案)。
            //
            // 2026-10-15 用户「邮箱框默认英文优先」:默认语言**压过 restarting
            // 保护** —— 此前它与"同框重启动不动语言"共用一个 `!restarting` 条件,
            // 于是用户在邮箱框里手动切中文、离开再回来,语言停在中文;现在有默认
            // 语言的框每次聚焦都回归默认。**没有默认语言的框才是"语言不变"**
            // (defaultLanguageId = null ⇒ 不走这条,保持用户当前语言)。
            controller.registry.fieldProfile(controller.state.fieldKind)
                .defaultLanguageId?.let { want ->
                    if (controller.state.languageId != want) controller.setLanguage(want)
                }
        }
    }

    override fun onFinishInput() {
        // 同样不需要保存用户词频,理由见 onDestroy。
        // 识别中的会话必须在换框/收键盘时停掉,否则结果会落到下一个输入框。
        voiceInput.cancel()
        super.onFinishInput()
    }

    /**
     * 输入法窗口被收起(收起三角 / App 主动 `hideSoftInput` / 焦点离开)时,**大写与 Alt/Sym
     * 全部回到初始态**(2026-09-29 用户定案):L1、无锁定、无一次性、大写关。
     * 窗口都不在了还留着个锁定的 L2 或开着的大写,下次弹出来会停在一个用户没在用的态上,
     * 状态行也会显示它。语言与输入框类型保持不变(换框/换语言是另一套逻辑,见
     * [dev.hwkbd.ime.ime.ImeController.resetForNewInput] 与 [onStartInput])。
     *
     * 挂在 `onWindowHidden` 而不是只在收起三角那一下清:App 自己收键盘是同样常见的路径,
     * 只挂按钮会漏掉它。
     */
    override fun onWindowHidden() {
        controller.resetLayers()
        // 静默窗跟着层状态一起清:窗口隐藏前敲的那一下不该在下次弹出时还挡着滑动
        keyGate.reset()
        super.onWindowHidden()
    }

    // ------------------------------------------------------------------ 键事件

    /**
     * 点 L4 / L5 面板键帽:和物理键同等对待 —— 先记一次按键事件(起抬手后的静默窗),再走同一条提交路径。
     *
     * 屏上键帽和物理键的区别只在事件来源,不在后果:两者都会让候选重排。
     * 漏记的话就是「刚点完屏上的键,马上划一下却生效了」—— 同一种手速,两种结果。
     *
     * 走 [KeyEventGate.onKeyEvent] 而不是 onKeyDown/onKeyUp:点按没有"按住"这段,
     * 它本身就是在抬手那一刻结算的,只需要抬手后的窗口。
     */
    private fun commitL4Tapped(out: dev.hwkbd.ime.keymap.KeyOut) {
        keyGate.onKeyEvent()
        controller.commitL4Key(out)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 窗口隐藏时硬键一律透传给应用,不捕捉(用户 2026-10-16「隐藏时按键透传」)。
        // 收起三角 / App hideSoftInput / 焦点离开后,onKeyDown 仍会被系统路由到本 IME
        // (硬键 IME 始终是 current method),照常走 controller 会把字符吃掉,
        // 游戏 / 终端 / 无焦点编辑框收不到键。
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        // **任何按键都记,不看消费与否**(用户 2026-10-02 补定「无论是否消费」)。
        // 记在 null 检查之前 —— "这个键正被按着"这件事不需要 KeyEvent 才成立。
        // 按住期间手势整条不接(2026-10-16 用户「按键 down 的时候忽略手势」)。
        keyGate.onKeyDown(keyCode)
        event ?: return super.onKeyDown(keyCode, event)
        // 新一次用户输入开始:上一次写入的确认窗口到此为止(见 [expireComposingAck])。
        expireComposingAck()
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
        if (!isInputViewShown) return super.onKeyUp(keyCode, event)
        // 抬手也要记:**静默窗从抬手起算**(2026-10-16 用户「up 之后 300ms 以内也忽略」)。
        // 同样记在 null 检查之前 —— 理由同 onKeyDown。
        keyGate.onKeyUp(keyCode)
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
        // 自己那一次写入的回声:**不对账**,直到看到与我们写的对得上的那一条
        // (见 [composingAckPending] 的完整经过)。
        if (composingAckPending) {
            if (candidatesStart >= 0 && candidatesEnd - candidatesStart == composing.length) {
                composingAckPending = false // 确认到了,恢复对账
            } else {
                android.util.Log.i(
                    TAG,
                    "updateSelection: 跳过自己写入的中间态 " +
                        "comp=$candidatesStart..$candidatesEnd want=len(${composing.length}) " +
                        "raw=$composing"
                )
                return
            }
        }
        if (controller.state.hasComposition && composing.isNotEmpty()) {
            val expected = candidatesStart + composing.length
            if (candidatesEnd != expected) {
                // **每次放弃都打一行**,带全部判据。
                //
                // 原来这里是一个静默的 `abandonComposition()` —— 2026-10-09 查
                // 「部分消费后组词被丢弃」时,日志里只看得到 `comp=0..1 raw=hao`,
                // 看不出是我们写歪了、还是框架回报的与我们写的不是同一件事。
                // 对账一旦触发,它就是**整个引擎被清掉**,不留痕迹太危险。
                android.util.Log.i(
                    TAG,
                    "ABANDON: comp=$candidatesStart..$candidatesEnd expected=$expected " +
                        "composing=\"$composing\" stateRaw=\"${controller.state.raw}\" " +
                        "appRendersComposing=$appRendersComposing"
                )
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
     * App 侧既不 resize 也不 pan;L5 面板(条上方的浮层)出现时改报面板顶 ——
     * 它是真实内容边界,App 必须让出这块空间,否则会被盖在 App 内容上(或面板顶被裁)。
     * 触摸范围仍用整个窗口框架,飞字手势不受影响。
     */
    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null) return
        val row = when {
            l5PanelView?.visibility == View.VISIBLE -> l5PanelView
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
     * L5 面板的磨砂玻璃背景模糊(2026-10-20 用户「颜色应该是半透明的磨砂效果」)。
     *
     * 面板自己只做半透明(见 [dev.hwkbd.ime.ui.L5PanelView] 的 alpha),真正的「磨砂」是
     * **窗口背景模糊**:把面板底下的画面糊掉,半透明面板叠上去就成了毛玻璃。
     * 半透明和模糊缺一不可 —— 面板底不透明则一点模糊都透不出来。
     *
     * **必须是 background blur,不能用 `FLAG_BLUR_BEHIND`**(2026-10-20 用户「不是要全屏模糊」):
     * blur-behind 按文档是「把窗口背后的**整个屏幕**糊掉」,与窗口多大无关 —— 面板一开,
     * App 整屏都糊了。background blur 只糊**窗口边界以内**的内容:空闲态窗口 = 键盘 + 面板,
     * 键盘面本身不透明,所以肉眼只在半透明面板那一块看得到模糊。
     *
     * 只在**面板可见**时开:组词态窗口会拉到全屏,那时若还开着 blur 会把整屏(其实是整个
     * 窗口)糊掉;关面板 / 进组词都会走到这里关掉(进组词前控制器已收面板,
     * 见 [ImeController.refreshQuery])。
     *
     * 需要 API 31+ 且设备/用户开了跨窗口模糊([android.view.WindowManager.isCrossWindowBlurEnabled]);
     * 不支持时静默退回「只有半透明底」,不影响功能。
     */
    private fun syncL5Blur(on: Boolean) {
        if (on == l5BlurOn) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            l5BlurOn = on
            return
        }
        val win = window?.window ?: return // 窗口还没建好:下次渲染再来
        val wm = getSystemService(android.view.WindowManager::class.java)
        if (wm == null || !wm.isCrossWindowBlurEnabled) {
            android.util.Log.i(TAG, "l5blur: 跨窗口模糊不可用,面板仅半透明")
            l5BlurOn = on
            return
        }
        // 窗口背景必须半透明,background blur 才透得出来(窗口背景在 onCreate 已设透明)。
        win.setBackgroundBlurRadius(
            if (on) (L5_BLUR_RADIUS_DP * resources.displayMetrics.density).toInt() else 0
        )
        l5BlurOn = on
        android.util.Log.i(TAG, "l5blur: $on radius=${L5_BLUR_RADIUS_DP}dp (background,窗口内)")
    }

    /**
     * 飞字:键盘表面(TOUCHPAD)滑动选字。
     * 事件经全屏 touchable 区送达输入法窗口,坐标空间约 1440x720。
     *
     * 手势分两段:一上滑就进入拖动(词随手指缓慢起升、其余候选渐隐),
     * 位移越过阈值立刻提交,剩下的飞字动画由候选栏自己飞完;
     * 松手还没过阈值就把拖出来的效果淡回去。
     */
    private class FlyDetector(
        private val listener: Listener,
        /**
         * 分区的**基准宽度**(px),每次手势现取。
         *
         * 这里曾是 `var xMax = 1440f`:一个 Titan 2 的物理像素数被钉成字段初值,于是
         * `applyPrefs()` 还没跑、或将来屏宽不是 1440 时,**1440 会冒充真实屏宽**参与分区
         * (2026-10-10 用户「飞字区域要根据屏幕宽度计算,不要写死」)。改成一个由宿主提供的
         * 函数,本类不再有可以过期的字段。
         *
         * 5 区按屏宽平分——Titan 2 物理键盘正好 10 列,每区 = 2 个键列,
         * 分区边界与物理键列对齐(用户 2026-09-29 定案)。
         */
        private val zoneBasis: () -> Float,
    ) {
        interface Listener {
            /** 拖动中:progress = 上移距离/阈值,0..1(词已开始起升)。 */
            fun onFlyDrag(zone: Int, progress: Float)

            /** 越过阈值:立即提交飞字(完成段动画 + 选字上屏)。 */
            fun onFlyCommit(zone: Int)

            /** 拖动放弃(松手未过阈值 / 转为横滑 / 事件取消)。 */
            fun onFlyAbort()

            /**
             * 横滑翻页起手:横向占优且过了死区。@return 是否接管
             *
             * **跟手绘制、过阈值当帧提交、到头橡皮筋全部由候选栏做**(见
             * [dev.hwkbd.ime.ui.CandidateBarView.pageDragBegin])。这里只负责判方向、
             * 在该放弃的时候(没组词)返回 false。
             */
            fun onPageDragBegin(dir: Float, x: Float, eventTime: Long): Boolean

            /** 横滑翻页跟手。 */
            fun onPageDragMove(x: Float, eventTime: Long)

            /** 横滑翻页收尾:过提交线才翻,到头弹回。 */
            fun onPageDragEnd(x: Float)

            /**
             * 横滑翻页**被打断**(系统取消,不是用户抬手)。
             *
             * 与抬手分开:被打断的橡皮筋直接归零,**不弹轻震** ——
             * 「到头了」那下震是给用户主动确认的,系统抢走手势时不该有。
             * 与候选行自己的 ACTION_CANCEL 同一套语义。
             */
            fun onPageDragCancel()
        }

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var tracking = false
        private var zone = -1
        private var dragging = false
        private var committed = false

        /**
         * 横滑翻页是否已接管(2026-10-02)。
         *
         * **此前键盘面上的左右划只在 ACTION_UP 判一次,然后直接 `pageStep()`** ——
         * 抬手才翻、整条拖动不画位移、到头什么都不发生,于是"翻页的跟手动画和
         * 到头弹性"在键盘表面上从来没有过(用户报「还是没有」,而候选行上一直是好的)。
         * 现在横滑在 MOVE 里就接管,跟手与橡皮筋交给候选栏画。
         */
        private var paging = false

        /** 飞字触发上滑距离(屏幕坐标单位),设置页三档 100/160/240,默认 160。 */
        var threshold = 160f

        /**
         * **横向**翻页所需的指尖位移(px):键盘表面上左右划翻页的判据。
         *
         * 原先写死 60f,2026-10-02 用户「左右滑动阈值需要可调节性」改为由设置页档位注入
         * (见 [ImeService.PAGE_THRESHOLD_TIERS])。**字段默认值随默认档走**(标准档 = 24f),
         * 没有偏好时先用它顶着,`applyPrefs` 会立刻用真档位覆盖。
         *
         * **口径 = 死区之外还要走多远**,不是手指总行程。拖动基线定在死区被跨过的那一帧,
         * 死区已经走完了,所以这里比的是"过了基线再走多远"。
         * 这个字段所有比较点都**直接拿它比**,不再各自记得减死区 ——
         * 旧口径下 [dev.hwkbd.ime.ui.CandidateBarView] 减了、下面 ACTION_UP 的兜底忘了减,
         * 两处量纲对不上。死区因此也不再暗中影响灵敏度。
         *
         * **它与候选行的阈值不是同一个量纲**:键面上横向能走到的距离本来就小
         * (标准档 24px ≈ 候选行标准档 317px 的 1/13),照搬候选行那个比例会变成"几乎划不动"。
         * 所以档位表对两处分别给值。
         *
         * **2026-10-16 与死区一起调大**(第二列 4/8/20 → 12/24/60):死区只管"手放在键面上、
         * 还没打算划"的噪声,真去翻一页要划多远由这一列决定 —— 那张总行程表在
         * [dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE] 的注释里。
         */
        var pageThresholdPx = 24f

        /**
         * 上滑死区:**固定值**,不随 [threshold] 变
         * (2026-10-02 用户「死区不使用比例计算 采用固定值」)。
         *
         * 此前是 `threshold / 4`,于是三档给出 25 / 40 / 60px —— 调上滑距离时死区跟着动,
         * 手感会连带乱掉,而且「灵敏」档的起手空白只有 12.5dp、「保守」档 30dp,
         * 差的不只是触发点。现在键面上横纵共用 [dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE]
         * (12dp;2026-10-16 从 8dp 调大,理由见那条)。
         *
         * 键距在屏幕坐标里,`FlyDetector` 拿不到 density(它是脱离 View 的内部类),
         * 所以由 service 用本机 density 注入 —— 见构造处的 `deadPx`。
         */
        var deadPx = 0f

        /**
         * **横滑**(翻页)的死区(px)。与 [deadPx](上滑)**分开**(2026-10-04)。
         *
         * 本类里横纵两向都要判死区,而用户要「翻页更灵」但「不改上滑」——
         * 共用一个字段时这两条是冲突的,所以拆开注入(两个字段留着,便于将来只调一轴;
         * 2026-10-16 两边喂的是**同一个** [dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE])。
         * 键距在屏幕坐标里,本类拿不到 density,所以两个都由 service 注入。
         */
        var swipeDeadPx = 0f

        /** 上滑(飞字)的死区(px)。 */
        private val deadZone: Float get() = deadPx

        /** 横滑(翻页)的死区(px)。 */
        private val swipeDeadZone: Float get() = swipeDeadPx

        /** 返回值:null=无手势,>=0 飞字区号,-1=上一页,-2=下一页。 */
        fun onMotion(action: Int, x: Float, y: Int, eventTime: Long = 0L): Int? {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y.toFloat()
                    downAt = System.currentTimeMillis()
                    tracking = true
                    zone = -1
                    dragging = false
                    committed = false
                    paging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || committed) return null
                    val dy = downY - y
                    val dx = x - downX
                    val adx = kotlin.math.abs(dx)
                    // **横滑翻页:已在拖就继续喂**(跟手 + 到头橡皮筋都由候选栏画)
                    if (paging) {
                        listener.onPageDragMove(x, eventTime)
                        return null
                    }
                    // 横向占优且过了死区 → 起手翻页拖动(死区吃键面那一档 DEAD_DP_SURFACE)。
                    // 必须在 MOVE 里接管:抬手才判的话整条拖动不画位移,跟手动画就没有。
                    if (adx > swipeDeadZone && adx > dy) {
                        val dir = if (dx < 0f) 1f else -1f
                        if (listener.onPageDragBegin(dir, x, eventTime)) {
                            paging = true
                            listener.onPageDragMove(x, eventTime)
                            return null
                        }
                    }
                    // 回拖或横向为主:放弃飞字(翻页要么已接管,要么没到死区)
                    if (dy <= 0f || adx >= dy) {
                        abortDrag()
                        return null
                    }
                    // 死区:上滑还不到死区时整体忽略(已起拖的拖回死区则淡回放弃)
                    if (dy <= deadZone) {
                        abortDrag()
                        return null
                    }
                    if (zone < 0) zone = zoneOf(downX)
                    dragging = true
                    // 过死区后进度从 0 重算,过阈值语义不变(仍 dy ≥ threshold 提交)
                    val progress = dev.hwkbd.ime.ui.DragGesture.progress(dy, threshold, deadZone)
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
                    // **翻页拖动中 → 收尾**(过线翻页 / 没过线反向收回 / 到头橡皮筋弹回),
                    // 由候选栏按它自己的提交线判定,这里不重复判一次。
                    if (paging) {
                        paging = false
                        val wasFlyDragging = dragging
                        dragging = false
                        if (wasFlyDragging) listener.onFlyAbort()
                        if (action == MotionEvent.ACTION_CANCEL) listener.onPageDragCancel()
                        else listener.onPageDragEnd(x)
                        return null
                    }
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
                    // 横向主位移 → 翻页:**兜底**,只在这条手势压根没进过 MOVE 接管时
                    // (比如事件被系统合并成一次 DOWN+UP)才轮得到它。有跟手动画的路走上面。
                    //
                    // [pageThresholdPx] 是**死区之外**的提交距离,而这里的 adx 从 DOWN 算起,
                    // 死区那一截还在里面 —— 所以要先加上死区,两者才同一个口径
                    // (2026-10-02 改档位口径时就是漏了这一次,两处量纲对不上)。
                    if (adx > swipeDeadZone + pageThresholdPx && adx > dy) return if (dx < 0) PAGE_NEXT else PAGE_PREV
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
            val f = x / zoneBasis() * FLY_ZONES
            return (f.toInt()).coerceIn(0, FLY_ZONES - 1)
        }
    }

    /** 键盘表面触摸(经 ImeRootView 转发,y<0 为窗口外飞字区):判定并执行手势。 */
    private fun handleFlyMotion(ev: MotionEvent): Boolean {
        // 滑动许可只在 DOWN 问一次(见 KeyEventGate):刚敲过键就落在这里的手势整条不接 ——
        // 喂给 FlyDetector 的话它会正常起拖,画面跟着手指走,松手却什么都不做。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            flyArmed = !keyGate.swipeBlocked()
            // 与 [ImeRootView] 的 DOWN 埋点配对:那条说「事件到了没有」,
            // 这条说「到了之后被哪个门挡住」。两条都没有 ⇒ 事件没进窗口。
            android.util.Log.i(
                TAG,
                "fly DOWN: y=${ev.rawY.toInt()} armed=$flyArmed " +
                    "blocked=${keyGate.swipeBlocked()} comp=${controller.state.hasComposition}"
            )
            warnZoneBasisDrift()
        }
        if (!flyArmed) return true
        val zone = flyDetector.onMotion(ev.actionMasked, ev.rawX, ev.rawY.toInt(), ev.eventTime)
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

    /**
     * 上滑分区有两个 owner,而它们**只在布局上相等**:
     * 候选行内起手按 `candidateBar.width / 5` 分([dev.hwkbd.ime.ui.CandidateBarView.cellWidth]),
     * 键盘面起手按 `屏宽 / 5` 分([FlyDetector.zoneOf])。2026-10-10 把候选行改成**通栏**才让
     * 两者重合的(此前差 2×16dp,分区边界逐区漂移,手指落在边界窄带就选中邻词)。
     *
     * 这条不变量跨文件、编译期没有任何联系 —— 谁把 `paddingStart` 加回来,旧 bug 会**无声**
     * 复发(词心照样命中,只有边界附近才错,常规测试场景测不出来)。所以每次飞字起手对一次账,
     * 只报一次。
     */
    private fun warnZoneBasisDrift() {
        if (zoneBasisDriftLogged) return
        val bar = candidateBar ?: return
        val screen = resources.displayMetrics.widthPixels
        if (bar.width <= 0 || bar.width == screen) return
        zoneBasisDriftLogged = true
        android.util.Log.w(
            TAG,
            "分区基准漂移:候选栏实测 ${bar.width}px ≠ 屏宽 ${screen}px," +
                "两套五等分不再重合(候选行必须通栏)"
        )
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

        /**
         * 横滑翻页起手:横向占优且过了死区就交给候选栏接管。
         *
         * **没组词时不接管** —— 没有候选词就没有页可翻,让事件继续往下走(抬手时
         * 那条 `pageStep` 兜底自己会 no-op)。返回 false 就是"这次不接管"。
         */
        override fun onPageDragBegin(dir: Float, x: Float, eventTime: Long): Boolean {
            if (!controller.state.hasComposition) {
                // 静默早退过一次:「没组词 → 不接管」在 logcat 里什么都不留,
                // 看起来与「手势没进来」完全一样。两条要能分开。
                android.util.Log.i(TAG, "page begin: 无组词,不接管")
                return false
            }
            return candidateBar?.pageDragBegin(dir, x, eventTime) == true
        }

        override fun onPageDragMove(x: Float, eventTime: Long) {
            candidateBar?.pageDragMove(x, eventTime)
        }

        override fun onPageDragEnd(x: Float) {
            candidateBar?.pageDragEnd(x)
        }

        override fun onPageDragCancel() {
            candidateBar?.pageDragCancel()
        }
    },
    // 屏宽由这里现取,不再是在 applyPrefs 里刷新的字段:构造期 IME 还没 attach base context,
    // 当场取值会抛,而它**每次手势都要被调用**(zoneOf),那时 resources 早已就绪。
    zoneBasis = { resources.displayMetrics.widthPixels.toFloat() },
)

    /** 越过阈值:先把完成段动画挂上(flyP 置位后 render 才会保持候选行可见),再选字上屏。 */
    private fun commitFly(zone: Int) {
        val has = controller.state.hasComposition
        android.util.Log.i(TAG, "fly select: zone=$zone hasComposition=$has")
        if (!has) {
            candidateBar?.cancelFlyDrag()
            return
        }
        // **动画不在这里起**了:由 [ImeController.onCandidateSelected] 统一触发
        // (2026-10-02 用户「按键选词也需要飞字动画」)。九处选字入口共用同一个回调,
        // 才不会出现"上滑有飞字、按键没有"这种按入口不一致的表现。
        controller.selectCandidate(zone)
    }


    // ------------------------------------------------------------------ 窗口策略

    /**
     * 有硬键盘也要显示输入视图:候选栏是硬件键盘方案的核心选字通道。
     *
     * **必须硬写 true,不能调 super**(2026-09-30 实机事故):平台的默认实现看的是设备是否配了
     * 硬件键盘,Titan 2 配了 ⇒ super 返回 false ⇒ 输入视图**永远不显示**(日志里表现为
     * `ImeTracker: onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE`,`mIsInputViewShown=false`)。
     * 之前为了消 lint 的 `MissingSuperCall` 把它改成 `super.onEvaluateInputViewShown()`,
     * 结果输入法整个弹不出来——**lint 的启发式在这里是错的**,所以改成显式抑制而不是照它改。
     */
    @Suppress("MissingSuperCall")
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
        val oldFullWidth = controller.fullWidthSymbolsInCn
        controller.fullWidthSymbolsInCn = p.getBoolean(PREF_FULLWIDTH, true)
        // 设置页/状态行都会改这个偏好;面板开着时按新开关重算字符盘(见 refreshL5Content)。
        if (oldFullWidth != controller.fullWidthSymbolsInCn) controller.refreshL5Content()
        controller.pageReverse = p.getBoolean(PREF_PAGE_REVERSE, false)
        controller.symKeycode = p.getInt(PREF_SYM_KEYCODE, KeyEvent.KEYCODE_SYM)
        val alt = p.getInt(PREF_ALT_KEYCODE, -1)
        controller.altKeycodes =
            if (alt > 0) intArrayOf(alt) else intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)

        candidateBar?.swipePageEnabled = p.getBoolean(PREF_SWIPE_PAGE, true)
        // 长按字母键上屏 L2 符号(2026-10-15 用户定,默认开;**只支持字母键**)。
        // **拼音输入中长按 L 插分隔符不受它管** —— 那条不可关闭,
        // 见 ImeController.tryArmLongPress。
        controller.longPressSymbolEnabled = p.getBoolean(PREF_LONGPRESS_L2, true)
        // 长按空格语音输入(默认关;权限在设置页申请,IME 自己不能弹权限框)。
        // 引擎/密钥变化时重建会话;关开关时立刻停掉正在收音的那次。
        controller.voiceInputEnabled = p.getBoolean(PREF_VOICE_INPUT, false)
        voiceInput.applySettings(VoiceSettings.read(p), controller.voiceInputEnabled)
        // 震动反馈总开关:关掉后锁定/翻页/飞字四处全部静默,状态机判定不受影响
        haptics?.enabled = p.getBoolean(PREF_HAPTIC, true)
        // 按键与滑动之间的门:按住键期间一律不接手势,抬手后再挡 blockMs 那么久
        // (2026-10-16 用户「按键 down 的时候忽略手势,up 之后 300ms 以内也忽略,并且可配置」)。
        // **按住那段不受这个值影响** —— 它是物理判据,不是手感偏好(见 [KeyEventGate])。
        keyGate.blockMs = p.getString(PREF_KEY_GATE_MS, KEY_GATE_MS_DEFAULT)?.toLongOrNull()
            ?: KEY_GATE_MS_DEFAULT.toLong()
        migrateFlyThreshold(p)
        val flyThreshold = p.getString(PREF_FLY_THRESHOLD, FLY_THRESHOLD_DEFAULT)?.toFloatOrNull()
            ?: FLY_THRESHOLD_DEFAULT.toFloat()
        flyDetector.threshold = flyThreshold
        // 死区固定(density 相关,见 FlyDetector.deadPx / swipeDeadPx)。
        // **键盘表面吃自己那一档**([dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE],12dp;
        // 2026-10-16 用户「键盘面死区调大 8dp → 12dp」)。屏幕上那两块真触摸板
        // (候选行 / L5 面板)仍用 DEAD_DP / DEAD_DP_SWIPE(8dp)。
        // 键面上横纵用**同一个值**:上滑飞字与横滑翻页在同一种手上,不分开调。
        val density = resources.displayMetrics.density
        flyDetector.deadPx = dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE * density
        flyDetector.swipeDeadPx = dev.hwkbd.ime.ui.DragGesture.DEAD_DP_SURFACE * density
        // 左右滑翻页的阈值:一个开关同时喂候选行、L4(预留)、键盘表面三处
        val (pageFrac, pagePx) = pageThreshold(p)
        candidateBar?.pageCommitFrac = pageFrac
        l4Panel?.swipeCommitFrac = pageFrac
        flyDetector.pageThresholdPx = pagePx
        // 键盘表面的横滑翻页现在走候选栏那套跟手/橡皮筋,所以把它的提交线也喂进去。
        // 候选行自己翻页仍按行宽比例(pageCommitFrac),两者量纲不同、各按各的。
        candidateBar?.externalCommitPx = pagePx
        // 上滑分区的基准宽度由 FlyDetector 自己现取屏宽(构造处),这里不再注入。
        candidateBar?.flyThresholdPx = flyThreshold // 候选行内起手的上滑用同一阈值
        theme = dev.hwkbd.ime.ui.ImeTheme.byId(p.getString(PREF_THEME, "obsidian"))
        candidateBar?.setTheme(theme)
        // 拼音悬浮条用主题 accent(2026-10-11 用户「拼音使用主题色 提高可读性」)。
        // accent 在这套主题系统里的定义就是「主色(语言状态、匹配拼音)」——
        // 候选行里匹配拼音的首字母已经用它(见 [dev.hwkbd.ime.ui.CandidateBarView.setTheme]),
        // 拼音条再用同一个色,读起来是同一族:正在输入的拼音 vs 候选词,
        // 一眼分得开,不用再靠「位置」猜哪段是拼音。
        // 主题在设置页换,prefsListener 会再跑一次这里,换色即时生效。
        preeditRow?.setTextColor(theme.accent)
        // 语音 HUD:麦克风与音量表跟拼音条同色(主题 accent),文字用候选前景色。
        voiceMic?.imageTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        voiceLevelView?.setBarColor(theme.accent)
        statusRow?.setBackgroundColor(theme.barBg)
        candidateRow?.setBackgroundColor(theme.barBg)
        // L5 面板的底色/键帽色由 [L5PanelView.setContent] 按 theme 重建,这里不预染。
        l4PanelSig = null // 主题可能变了,下次渲染重建键帽
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

    /**
     * 服务级协程作用域:只用来跑 [RenderPump] 的合并渲染,随 [onDestroy] 取消。
     * [Main.immediate] ⇒ 已在主线程时**同步**执行,不给合并后的重绘再加一帧延迟。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 视觉渲染合并器:一次按键事件里的多次 `render()` 合成一次([RenderPump])。
     * 组词区写入不在它那儿(必须同步,见 [syncComposing])。
     */
    private val renderPump = dev.hwkbd.ime.ui.RenderPump { updateUi() }

    /**
     * 状态行(2026-09-29 用户定案):**只显示已触发的状态**——没触发的层/大写一律不显示,
     * 切换**全部走物理按键**(Alt / Sym / Shift),状态项不响应触摸(全/半开关是例外)。
     * - 语言(拼/En):恒显(数字类藏掉,数字只认 Alt 那项);
     * - 层状态:目标层生效才出现——锁定=下划线、借用/按住=无下划线;数字框里文字是实际可输字符集;
     * - 大写:**英文态恒显**,用字形 `ab` / `Ab` / `AB` 表达三种大写态,不带下划线;
     * - **大写与层互斥**(2026-09-29 用户定案「英文状态大小写状态和 alt/sym 模式是互斥的」):
     *   符号层上大小写根本不参与输出(`commitText` 的 upper 只在字母上起作用),两项并排显示
     *   只会让人以为 alt 层还能出大写——**层优先**,层生效时藏起大写,回 L1 它自己回来;
     *   大写状态本身**不被清掉**(Caps 是持续态,只是这一层期间不显示)。
     * - **不显示"暂定"态这条规则 2026-09-30 已删**(原先窗口内藏着一次性层/大写一次):敲击判定
     *   统一到 keyUp 之后它只剩副作用——按住期间画出来的和敲击落定的结果长得一模一样,藏它只会
     *   闪烁(真机报障:在 alt 状态长按 alt,本来亮着的那项被藏 300ms 再亮回来)。
     *   所以状态行不再吃时间,只看状态。
     *
     * **本函数现在只做"取输入 + 贴到 View"**,判定规则全在纯函数
     * [dev.hwkbd.ime.ui.StatusRow.present] 里(零 Android 依赖、有 JVM 回归测试)。
     */
    private fun renderStatus(s: ImeState) {
        val lang = controller.registry.language(s.languageId)
        val field = controller.registry.fieldProfile(s.fieldKind)
        paintStatusItems(
            StatusRow.present(
                state = s,
                spec = StatusRow.Spec(
                    altLabel = field.altLabel,
                    digitsOnly = field.digitsOnly,
                    languageId = s.languageId,
                    languageLabel = lang.statusLabel,
                    languageIsPrimary = s.languageId == controller.registry.primaryLanguageId,
                    showShift = lang.showShift,
                    fullWidth = controller.fullWidthSymbolsInCn,
                    theme = theme,
                ),
            ),
        )
    }

    /** 把 [StatusRow] 开出的单子贴到那五个 TextView 上,没点名的 GONE。 */
    private fun paintStatusItems(items: List<StatusRow.Item>) {
        val byKind = items.associateBy { it.kind }
        val mode = byKind[StatusRow.Kind.MODE]
        if (mode == null) hideItem(statusMode) else statusMode?.apply {
            // 语言:纯字符样式(无背景块)——主语言=主色字,非主语言=前景色字
            visibility = View.VISIBLE
            text = mode.label
            setTextColor(mode.color)
            background = null
        }
        val fw = byKind[StatusRow.Kind.FULLWIDTH]
        if (fw == null) hideItem(statusFullWidth) else statusFullWidth?.apply {
            visibility = View.VISIBLE
            text = fw.label
            // 当前生效的那一面用主色,像层状态的"锁定"那样一眼看出是哪个状态
            setTextColor(fw.color)
            contentDescription = context.getString(
                if (fw.label == "全") R.string.fullwidth_on else R.string.fullwidth_off,
            )
        }
        // 三项走同一条渲染路径:锁定=底部下划线,其余无背景(样式统一,2026-09-30)
        val shift = byKind[StatusRow.Kind.SHIFT]
        if (shift == null) hideItem(statusShift) else paintItem(
            statusShift, shift.label, shift.color, shift.locked, shift.contentDescription,
        )
        val alt = byKind[StatusRow.Kind.ALT]
        if (alt == null) hideItem(statusAlt) else paintItem(statusAlt, alt.label, alt.color, alt.locked)
        val sym = byKind[StatusRow.Kind.SYM]
        if (sym == null) hideItem(statusSym) else paintItem(statusSym, sym.label, sym.color, sym.locked)
        alignFirstVisibleItem()
    }

    /**
     * 第一颗**可见**状态项不留起始间距:数字态只剩 Alt(`0-9` 那项)时,它到屏幕左缘
     * 要和 `拼`/`En` 对齐(否则多 2dp,2026-09-29 用户报障)。
     */
    private fun alignFirstVisibleItem() {
        val row = statusMode?.parent as? android.view.ViewGroup ?: return
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i)
            if (item.visibility != View.VISIBLE) continue
            val lp = item.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
            if (lp.marginStart != 0) {
                lp.marginStart = 0
                item.layoutParams = lp
            }
            return // 只处理第一颗
        }
    }

    private fun hideItem(item: android.view.View?) {
        item ?: return
        item.visibility = View.GONE
    }

    /**
     * 状态项渲染(2026-09-29 用户定案"状态锁定都采用下划线的方式表示"):
     * **锁定 = 加底部下划线**,借用/按住 = 无下划线。文字/图标本身不带背景按钮样式
     * (此前锁定=实心块、一次性=描边块,与右区三键不统一)。
     * 层状态(alt/sym)与大小写**走同一个函数**——用户报「英文大小写锁定的样式和 alt/sym
     * 锁定不同」时,差的正是这条路径各写各的:大小写那项曾经自己设一遍 `background`,盒子也小一号。
     * [contentDescription] 给大小写用(层状态的字面意思就是自身标签,无参即可)。
     */
    private fun paintItem(
        view: TextView?,
        label: String,
        color: Int,
        locked: Boolean,
        contentDescription: String? = null,
    ) {
        view ?: return
        view.text = label
        view.visibility = View.VISIBLE
        view.setTextColor(color)
        if (contentDescription != null) view.contentDescription = contentDescription
        if (locked) {
            view.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.status_lock_underline)
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        } else {
            view.background = null
        }
    }

    /**
     * 齿轮左侧键盘图标 = L5 面板开关(2026-10-19 合并定案):**常驻**。
     *
     * **展开/关闭只换颜色**(2026-10-19 用户「虚拟键盘图标展开和关闭仅仅改变颜色即可」):
     * 图标形状始终是键盘(`ic_keyboard`),OFF 染状态行前景色、L5 染主题 accent ——
     * 不再切成收起箭头,免得开关一下图标就跳形。
     * 只在态变时重设 tint,免得每帧刷。面板本身的显隐与 insets 通知在 [renderViews]。
     */
    private fun syncL5() {
        val mode = controller.state.panelMode
        btnL5?.let { btn ->
            if (syncedPanelMode != mode) {
                syncedPanelMode = mode
                btn.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (mode == dev.hwkbd.ime.ime.PanelMode.OFF) {
                        androidx.core.content.ContextCompat.getColor(this, R.color.status_fg)
                    } else {
                        theme.accent
                    },
                )
            }
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
        }
    }

    /**
     * 视觉渲染(状态行/候选栏/L4/L5/单行两态/拼音悬浮条)。
     *
     * **可以被合并**([renderPump]):一次按键事件里状态机会请求好几次,中间那几帧是没人看得到的
     * 中间态,合成一次只画终态。所以按键路径走 [requestRender](组词区同步 + 合并请求),
     * 生命周期那些需要立刻见效的路径直接调 [updateUi](同步跑完整一趟)。
     */
    private fun renderViews() {
        val s: ImeState = controller.state
        renderStatus(s)
        candidateBar?.setState(s)

        // L4 中区随输入类型/语言变化:签名没变不重建键帽(每变一次就是整排视图重建)
        val l4Sig = controller.l4Signature()
        if (l4Sig != l4PanelSig) {
            l4PanelSig = l4Sig
            val keys = controller.l4Keys()
            l4Panel?.refresh(keys, theme)
            // 键表换了但切点可能没变:重喂一次当前切点,场景 tab 才会换上新的尾巴键。
            // render=false:本趟渲染马上就会把新 state.l5 画出来,不再排一次渲染。
            controller.setOverflowCut(l5Cut, render = false)
        }
        // L4 必显(2026-09-29 用户定案:设置页开关已删、数字态也不隐藏;数字框里显示的是
        // 该类型的短语组合键位套,核心键与 L1-L3 已有的键已在注册表滤掉)
        l4Panel?.visibility = View.VISIBLE
        syncL5()

        // L5 面板:**状态说了算**(state.l5 非空 = 显示)。开/关/翻页/切 tab 全是控制器改的字段,
        // 这里只是把它照出来。面板 = 原 L6 键盘 + 原 L5 行(「场景」tab),2026-10-19 合并。
        l5PanelView?.apply {
            val ui = s.l5
            val was = visibility == View.VISIBLE
            var rebuilt = false
            if (ui == null) {
                if (was) visibility = View.GONE
            } else {
                rebuilt = setContent(ui, theme)
                if (!was) visibility = View.VISIBLE
            }
            // 面板改变**窗口高度**(条上方多出一整块,切 tab/翻页也会变高矮),
            // 必须通知框架重算 insets —— 不报的话 App 不让位、面板顶被裁掉一截。
            // 延迟再补一次(切换输入框时实测会漏)。
            if ((ui != null) != was || rebuilt) {
                notifyInsetsChanged()
                mainHandler.post { notifyInsetsChanged() }
            }
        }
        // 拼音悬浮条:只在 App 不渲染 composing 区时显示(宽度随文字,见 ime_root.xml)。
        // 组词一开始先等 App 回报,超时没回报才判定"App 不渲染"并放行
        if (s.raw.isNotEmpty() && !appRendersComposing && !bubbleAllowed && !preeditProbeArmed) {
            preeditProbeArmed = true
            mainHandler.postDelayed(preeditProbe, PREEDIT_PROBE_MS)
        }
        preeditRow?.apply {
            val raw = s.raw
            // 拼音条同样带自动分隔符(见 syncComposing 的 shown):App 不渲染
            // composing 区时( Termux 类)这条气泡是用户唯一看得见拼音的地方。
            val shown = s.spelling.ifEmpty { raw }
            if (raw.isEmpty() || !bubbleAllowed || appRendersComposing) {
                if (visibility != View.GONE) visibility = View.GONE
            } else {
                if (visibility != View.VISIBLE) visibility = View.VISIBLE
                if (text.toString() != shown) text = shown
            }
        }

        // 单行两态:组词中整行给候选,空闲时整行给状态
        // 飞字动画期间保持候选行可见(否则动画没画布),结束后再切回状态行
        val composingNow = s.hasComposition || (candidateBar?.isFlyAnimating() == true)
        statusRow?.visibility = if (composingNow) View.GONE else View.VISIBLE
        candidateRow?.visibility = if (composingNow) View.VISIBLE else View.GONE
        if (composingNow != lastComposingForInsets) {
            lastComposingForInsets = composingNow
            applyWindowMode(composingNow) // 组词中窗口全屏接键盘表面触摸,空闲恢复细条
        }
        // 面板的磨砂玻璃:面板可见且窗口**不是**组词全屏时才开(见 syncL5Blur)。
        // 放在 applyWindowMode 之后:同帧先定窗口形态再定 blur,不会出现
        // 「先糊上、下一行又把窗口拉全屏」的那一帧。
        syncL5Blur(s.l5 != null && !composingNow)
    }

    /**
     * 组词区同步:编辑框里显示下划线拼音。**这一步必须同步,不能进 [renderPump]**。
     *
     * 它写的是目标应用的 `InputConnection`,而框架会在**下一次** [onUpdateSelection] 里把
     * 组词区的起止位置回报回来与我们核对,对不上就 [ImeController.abandonComposition] 把组词丢掉。
     * 延后一轮再写,中间那一轮里 App 看到的组词区是**上一份**,核对就会误判——
     * "合并渲染"绝不能把这一步一起合掉(2026-09-30 落地 Stage 3 时发现)。
     */
    private fun syncComposing() {
        val s: ImeState = controller.state
        val ic = currentInputConnection
        // 写进编辑框的拼音**带自动分隔符**(state.spelling,2026-10-12 用户定;
        // 产生与失效见 [ImeController.refreshSpelling])。判据是**显示串**而不是
        // raw:首选候选会变,raw 不变时显示串也可能变(翻页后中央槽换成另一个词,
        // 切音节不同)—— 拿 raw 做判据会漏掉这种改写。
        val shown = s.spelling.ifEmpty { s.raw }
        if (shown != composing && ic != null) {
            if (s.raw.isEmpty()) {
                if (composing.isNotEmpty()) ic.finishComposingText()
            } else {
                ic.setComposingText(shown, 1)
                // 自己写的这一次,下一次 [onUpdateSelection] 是它的回声 —— 别拿它对账
                // (见 [composingAckPending] 的完整经过)。
                composingAckPending = true
                // 写完之后 App 侧到底有什么:`getComposingRegion` / `getSelectedRegion`
                // 都是隐藏 API,所以直接读 App 的文本 —— 那是我们唯一真正关心的东西。
                val before = runCatching { ic.getTextBeforeCursor(200, 0)?.toString() }.getOrNull()
                val after = runCatching { ic.getTextAfterCursor(200, 0)?.toString() }.getOrNull()
                android.util.Log.i(
                    TAG,
                    "syncComposing: wrote=\"$shown\" len=${shown.length} " +
                        "before=$before after=$after prev=\"$composing\""
                )
            }
            composing = shown
        }
    }

    /**
     * 完整同步一趟:组词区写入 + 视觉渲染。**按键路径不走这里**,走 [requestRender] 合并;
     * 生命周期那些需要立刻见效的路径(onStartInput / 偏好变化 / 窗口模式切换…)走这里。
     */
    private fun updateUi() {
        // 词库后台线程加载完成会触发 render,统一调度回主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateUi() }
            return
        }
        syncComposing()
        renderViews()
    }

    /**
     * 按键路径的渲染请求:**组词区同步 + 合并一次视觉渲染**。
     *
     * 一次按键事件里状态机会请求好几次渲染(`beginHold`、敲击落定、`refreshQuery` 各一次),
     * 中间那几帧是没人看得到的中间态——既白费又闪。合成一次只画终态(见 [RenderPump])。
     */
    private fun requestRender() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { requestRender() }
            return
        }
        syncComposing()      // 这一步同步:组词区写入不能被合并延后(见 syncComposing)
        renderPump.request() // 这一步合并:视觉渲染一轮只跑一次
    }

    companion object {
        private const val TAG = "ImeService"

        /** 每页候选固定 5 条,与键盘面平分 5 区上滑一一对应。 */
        const val PAGE_SIZE = 5

        /** L5 面板磨砂玻璃的背景模糊半径(dp):把窗口内背后画面糊开的程度(见 [syncL5Blur])。 */
        private const val L5_BLUR_RADIUS_DP = 24f

        /**
         * 飞字动画的归位延时(ms):上屏后候选行要**留在屏上这么久**,动画才画得出来。
         *
         * [ImeController.selectCandidate] 会立刻清组词,于是两态要等这个延时才归位 ——
         * 不等的话词在半路就没了,只剩一个"啪"地出现在输入框里。
         *
         * 260 沿用手势那条路上一直用的值(2026-10-02 挪到统一回调时没改):它要盖住
         * 飞字的**完成段**(见 [dev.hwkbd.ime.ui.CandidateBarView.startFlyAnim],
         * 时长由松手速度决定)。按键选字的完成段从 0 起,通常比手势那条更短。
         */
        private const val FLY_SETTLE_MS = 260L

        /** 键盘面平分 5 区上滑选字(每区 = 2 个物理键列;基准宽度由 FlyDetector 现取屏宽,见 zoneBasis)。 */
        private const val FLY_ZONES = 5

        /** 手势返回码:飞字用区号(>=0),翻页用负数。 */
        private const val PAGE_PREV = -1
        private const val PAGE_NEXT = -2
        const val PREF_FULLWIDTH = "fullwidth_symbols_in_cn"
        const val PREF_PAGE_REVERSE = "page_reverse"
        // PREF_SWIPE_SELECT("swipe_select")2026-10-12 退役:候选栏上滑选字整条路移除,
        // 上屏收敛为点选。key 留着不管(老设置里的残留值无害),但不再有开关读它。
        const val PREF_SWIPE_PAGE = "swipe_page"
        const val PREF_LONGPRESS_L2 = "longpress_l2"
        const val PREF_VOICE_INPUT = "voice_input"
        const val PREF_FLY_THRESHOLD = "fly_threshold"

        /** 左右滑翻页所需距离(2026-10-02 用户「左右滑动阈值需要可调节性」)。值是档位名。 */
        const val PREF_PAGE_THRESHOLD = "page_threshold"

        /**
         * 按键与滑动之间的间隔(2026-10-16 用户「并且可配置」)。值是毫秒字符串,**0 = 关掉抬手后那段**。
         *
         * 只配「抬手后」那一段:**按住键期间一律不接手势**(物理判据)不受这个值影响,
         * 理由见 [dev.hwkbd.ime.ime.KeyEventGate] 的类注释。
         */
        const val PREF_KEY_GATE_MS = "key_gate_ms"

        /**
         * [PREF_KEY_GATE_MS] 的默认档(ms 字符串)。与 [dev.hwkbd.ime.ime.KeyEventGate.DEFAULT_BLOCK_MS]
         * 同值 —— 后者是判定实现的默认值,这里是设置页的默认档,由 `KeyEventGateTest` 钉住两者相等。
         */
        const val KEY_GATE_MS_DEFAULT = "300"

        /**
         * 默认档:`normal`。它现在的取值 = 用户真机认可的手感(2026-10-02
         * 「灵敏这档的横向滑动手感算正常」+「手感允许变化」)。
         *
         * **为什么默认档的名字又变回 `normal`,而用户认可的是原来那档"灵敏"的值**:
         * 键表面那次把"死区外提交距离"独立成一个量之后,三档不必再为"包含死区"这件事
         * 让位 —— 旧 `sensitive` 的 40px 总行程 = 死区 32(当时是 16dp) + 提交 8,那个 **8** 正是用户
         * 认可的手感。把它放到新的**中档**上,名字 `normal` 与"用户认为正常"就对上了,
         * 于是 `standard(默认)`、`sensitive(更灵敏)`、`conservative(更保守)` 三档自洽,
         * 设置页那行也不用再出现"灵敏是默认、标准不是默认"的别扭标注。
         *
         * 档位名**不改**:`sensitive`/`normal`/`conservative` 是偏好里持久化的值,
         * 改名会让老机器读到旧值静默回落(见 [pageThreshold])。这次连迁移都不需要 ——
         * 老偏好里的 `normal` 读出来仍是"标准档",只是数值比旧版灵敏了一点,那正是本次要的方向。
         *
         * 与两处出厂常量一致(候选行/L5 = 行宽 0.22,键盘表面 = 死区外 24px)。
         */
        private const val PAGE_THRESHOLD_DEFAULT = "normal"

        /**
         * 档位表:一行 = [候选行/L5 的行宽比例, **死区之外**还要走的 px]。
         *
         * **为什么不给一个量纲**:两处的可滑距离差一个量级 —— 候选行 1440px 宽,
         * 默认档 0.22 就是 317px;而键面上能横向划的距离本就小,24px(2026-10-16 前是 8px)
         * 就该翻页。照搬同一个比例会让键盘面"几乎划不动"。所以档位名共用、数值分别给,
         * 用户看到的仍然只有一个开关。
         *
         * ## 键盘面那一列为什么是"死区外"(2026-10-02 用户定)
         *
         * 原来是**手指总行程**(含死区)。于是:
         * 1. 每个比较点都得记得减一次死区 —— 候选栏减了,[FlyDetector] 抬手兜底忘了减,
         *    两处量纲对不上;
         * 2. 三档的台阶**被死区压扁**:40/60/90 减掉死区后只剩 8/28/58,最灵敏那档离死区
         *    只有 8px,想再加一档更灵敏的加不出来(死区已经是它的地板)。
         *
         * 改成"死区外"之后,档位就是用户真正在调的那个数:基线定在死区被跨过的那一帧,
         * 死区已经走完了,所以提交线直接拿来比,三档也终于均匀。
         *
         * **顺带得到一条独立性**:死区改小时提交距离不受影响(改之前两者纠缠在一起 ——
         * 死区一小,总行程那一列的等效提交距离就跟着变,等于暗中改了灵敏度)。
         * 2026-10-16 死区从 8dp 调到 12dp 时这条独立性就顶上了:档位表不必跟着动,
         * 只有"总行程"变长(见 DragGesture.DEAD_DP_SURFACE 里那张表)。
         *
         * ## 第二列 4/8/20 → 12/24/60(2026-10-16 用户「连横滑阈值档位一起调大」)
         *
         * 起因是误触:8px ≈ 0.5mm,指尖压键时滚一下就过线,等于"一碰就翻"。
         * **每档 ×3**,档间倍差(2× / 2.5×)不变 —— 那条被 [PageThresholdTierTest] 钉着,
         * 所以放大只能整列放大,不能顺手改台阶形状。
         *
         * 比例这一列改动要同步 [dev.hwkbd.ime.ui.PAGE_COMMIT_FRAC] 与
         * [dev.hwkbd.ime.ui.L4PanelView.L5_COMMIT_FRAC] —— 那是**无偏好时**的出厂值。
         * (本次只动第二列,比例那一列一个数都没改。)
         */
        private val PAGE_THRESHOLD_TIERS = mapOf(
            "sensitive" to (0.16f to 12f),
            "normal" to (0.22f to 24f),
            "conservative" to (0.33f to 60f),
        )

        /**
         * 读档位 → (行宽比例, 键盘表面 px)。**认不出的值一律回落默认档**,
         * 不抛异常:偏好文件是持久化的,将来改档位名时老机器上会读到旧值,
         * 那时静默回落比输入法起不来好。
         */
        private fun pageThreshold(p: android.content.SharedPreferences): Pair<Float, Float> =
            PAGE_THRESHOLD_TIERS[p.getString(PREF_PAGE_THRESHOLD, PAGE_THRESHOLD_DEFAULT)]
                ?: PAGE_THRESHOLD_TIERS.getValue(PAGE_THRESHOLD_DEFAULT)
        /**
         * 震动反馈开关(默认开)。四个反馈点:Caps 锁定/解锁、层锁定/解锁、翻页提交、飞字过阈值。
         * 锁定类比确认类重一点(14ms vs 12ms)—— "锁住了"比"过了"更需要实感。
         */
        const val PREF_HAPTIC = "haptic"

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
