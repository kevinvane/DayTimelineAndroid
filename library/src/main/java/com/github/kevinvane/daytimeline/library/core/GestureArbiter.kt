package com.github.kevinvane.daytimeline.library.core

/**
 * 手势判定状态机（PRD §8.2 / M4）。
 *
 * §8.2 要求三种意图严格互斥：
 * | 意图 | 触发 |
 * |---|---|
 * | 滚动 | 位移超过阈值（系统标准阈值 × 0.3） |
 * | 长按 | 已触发长按且**全程未移动** |
 * | 点击 | 未触发长按且位移很小 |
 *
 * 一旦判为滚动就**不可回退**为长按/点击——否则「滑动列表时误触发长按」
 * 会造成误编辑。同理，已进入编辑态的拖拽不会退回滚动。
 *
 * 纯状态机，无 Android 依赖，可直接单测。
 */
class GestureArbiter(
    /** 拖拽触发阈值（px）= 系统标准阈值 × ratio。 */
    private val dragThresholdPx: Int,
) {

    /** 当前意图。 */
    sealed class Intent {
        /** 尚未判定。 */
        object Pending : Intent()

        /** 已判定为滚动（不消费垂直手势，交外层容器，见 AD-04）。 */
        object Scroll : Intent()

        /** 已触发长按且全程未移动 → 进入编辑态。 */
        object LongPress : Intent()

        /** 未触发长按且位移很小 → 视为点击。 */
        object Click : Intent()

        /** 编辑态中拖动块整体（移动时间）。 */
        object DragMove : Intent()

        /** 编辑态中拖上边缘（改开始时间）。 */
        object DragResizeTop : Intent()

        /** 编辑态中拖下边缘（改结束时间）。 */
        object DragResizeBottom : Intent()
    }

    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private var editing = false

    /** 当前判定结果。 */
    var intent: Intent = Intent.Pending
        private set

    /**
     * 重置到未判定状态。每次新的 ACTION_DOWN 调用。
     *
     * @param editingTouching 本次按下是否落在编辑态上——落在编辑态的按下
     *   即使拖动也不应判为滚动（AD-04：长按进入编辑态后的垂直移动必须被消费）。
     */
    fun onDown(x: Float, y: Float, editingTouching: Boolean) {
        downX = x
        downY = y
        longPressFired = false
        editing = editingTouching
        intent = Intent.Pending
    }

    /**
     * 系统长按超时回调。返回 true 表示应进入编辑态。
     *
     * 规则：已判为滚动则忽略；已经触发过则忽略；否则标记为已触发。
     * 注意**此处不立刻返回"进入编辑"**——§8.2 明确「已触发长按但全程未移动」
     * 才进入编辑，所以真正的判定在 [onUp]。
     */
    fun onLongPressTimeout(): Boolean {
        if (intent is Intent.Scroll || longPressFired) return false
        longPressFired = true
        return true
    }

    /**
     * 移动。
     *
     * @param grabbed `0` 表示未抓到手柄；`1` 上边缘；`-1` 下边缘（与 `HitTester.Hit` 对应）。
     */
    fun onMove(x: Float, y: Float, grabbed: Int) {
        if (intent is Intent.Scroll || intent is Intent.Click) return
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy <= dragThresholdPx.toFloat() * dragThresholdPx) return

        intent = when {
            grabbed > 0 -> Intent.DragResizeTop
            grabbed < 0 -> Intent.DragResizeBottom
            editing -> Intent.DragMove
            // 非编辑态的纵向移动判为滚动，交给外层容器（AD-04 / E29）
            else -> Intent.Scroll
        }
    }

    /**
     * 抬起。返回最终意图。
     *
     * §8.2：已触发长按但全程未移动 → 进入编辑态（原地选中）；
     * 未触发长按且位移很小 → 视为点击。
     */
    fun onUp(): Intent {
        if (intent !is Intent.Pending) return intent
        intent = if (longPressFired) Intent.LongPress else Intent.Click
        return intent
    }

    /** 是否应消费本次手势（滚动模式下不消费垂直手势）。 */
    fun consumesGesture(): Boolean = intent !is Intent.Scroll
}
