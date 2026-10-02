package dev.hwkbd.ime.ui

import kotlin.math.max

/**
 * 越界橡皮筋的**拖动状态**:起手锚点、越界位移、方向。
 *
 * ## 为什么是独立的状态机(2026-10-02 真机报「滑到头的弹性动画没实现」)
 *
 * 修的是一个**每帧重置**的 bug:[CandidateBarView] 的 MOVE 分支里,橡皮筋的 `begin`
 * 会被**每个 MOVE 事件重新调一次**(外层判定每帧跑)。原先那个 `begin` 无条件执行
 * `raw = 0f; baseX = x`,于是同一次拖动的每一帧都把基线挪到当前手指位置、
 * 把进度清零,紧接着的 `move` 拿刚设的基线算出 `raw = 0` —— **橡皮筋的位移恒为 0**,
 * 而 [CandidateBarView.onDraw] 的守卫是 `raw > 0f`,于是那个分支一次都进不去。
 *
 * 数学([Rubber])一直是对的,坏的是状态被重复初始化。翻页那边没这个毛病,
 * 因为 `beginPageDrag` 在 `pageFrom/pageTo` 非空时**不**重置 —— 不对称就在这儿。
 *
 * 所以规矩写进类型:[begin] 对**同方向**的重复调用必须是空操作,由 [begin] 自己保证,
 * 调用方可以每帧无条件调。把状态收进纯类还有个好处:能 JVM 单测,不用真机。
 *
 * ## 方向在松手后仍保留
 *
 * [stop] 只停"手指不再驱动",不清 [dir]/[raw] —— 弹回动画还要用 [dir] 算往哪边退,
 * 用 [raw] 做动画的起点。真的要立刻归零(被打断)才用 [reset]。
 */
internal class RubberDrag {

    /** 越界方向:+1 拖向"没有下一页"那一侧,-1 另一侧。松手后保留,见类注释。 */
    var dir = 0f
        private set

    /** 手指超出边界的原始位移(px)。**不要直接拿去画** —— 显示位移经 [Rubber.shift] 阻尼。 */
    var raw = 0f
        private set

    /** 手指起手(第一次越过死区)时的 x;[move] 从这里算位移。 */
    private var baseX = 0f

    /** 手指是否正在驱动。弹回动画期间为 false,但 [dir]/[raw] 仍有效。 */
    var tracking = false
        private set

    /** 手指是否在驱动这条橡皮筋(调用方据此决定 MOVE 要不要继续喂)。 */
    val active: Boolean get() = tracking

    /**
     * 起手 / 每帧续上。
     *
     * **同方向已在拖时原样返回 false,不动任何状态** —— 这是本类存在的头号理由。
     * 反方向才重新锚定:手指往回拉,基准应该跟着回到 0。
     *
     * @return 本次是否(重新)锚定了基线
     */
    fun begin(newDir: Float, x: Float): Boolean {
        if (tracking && dir == newDir) return false
        dir = newDir
        baseX = x
        raw = 0f
        tracking = true
        return true
    }

    /** 跟手。往回拉会让 [raw] 变小,到 0 为止(那就是"收回来了")。 */
    fun move(x: Float) {
        if (!tracking) return
        raw = max(0f, -(x - baseX) * dir)
    }

    /** 松手:手指不再驱动。保留 [dir]/[raw] 给弹回动画。 */
    fun stop() {
        tracking = false
    }

    /** 弹回动画每帧写入。 */
    fun animateTo(v: Float) {
        raw = v
    }

    /** 被打断:立刻归零,不弹回。 */
    fun reset() {
        tracking = false
        dir = 0f
        raw = 0f
    }
}
