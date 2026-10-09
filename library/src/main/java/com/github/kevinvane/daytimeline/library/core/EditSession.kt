package com.github.kevinvane.daytimeline.library.core

/**
 * 编辑会话（PRD §8.1 状态流转 / M4）。
 *
 * 做成**纯状态机**而非 View 内部字段，原因有二：
 * 1. D3 是本产品最严重的质量红线（取消不得发出任何数据变更通知），
 *    必须能用普通 JUnit 逐条验证，而不是靠真机手点；
 * 2. E20 / E21 / E28 三条「编辑态 × 数据变更」规则本质是状态转移，
 *    放在纯函数里最容易写对。
 *
 * 本类**不做任何事件通知**，只产出"应该做什么"的结果；
 * 由 `DayTimelineView` 负责翻译成回调。这样从结构上就杜绝了
 * "取消路径顺手发了个通知"这类事故（见 [commit] / [cancel] 的设计）。
 */
class EditSession private constructor(
    /** 编辑前的原始日程；新建态为 null。 */
    val origin: SanitizedEvent?,
    /** 草稿开始分钟。 */
    var startMinute: Int,
    /** 草稿结束分钟。 */
    var endMinute: Int,
    /** 拖拽模式，用于绘制不同的手柄与提示。 */
    var dragMode: DragMode,
) {
    /** 是否为「新建」而非「修改」。 */
    val isCreating: Boolean get() = origin == null

    /** 是否处于编辑态（新建或修改）。 */
    val isEditing: Boolean get() = true

    /** 起止范围。 */
    val range: IntRange get() = startMinute..endMinute

    /**
     * 业务方在编辑期间改过同一条日程（PRD E28）。
     * 此时保留用户编辑态，完成时把该标记带给业务方，由其决定是否覆盖。
     */
    var hasConflict: Boolean = false

    /**
     * 待提交的显示内容（标题）。
     *
     * ## 为什么草稿要持有内容
     *
     * 第四层接管的表单会带回新标题（[applyEdit]），而 `onEventModified` 此前
     * 只能回传**修改前**的对象——标题在编辑路径上根本改不了（AD-22 缺口 G5）。
     * 草稿持有它，`commit()` 才能把它一并交出去。
     *
     * 语义：修改态初始化为原标题，`applyEdit` 传入 null 表示「不改标题」
     * 故沿用原值；新建态初始为 null。
     */
    var pendingContent: CharSequence? = null

    val start: MinuteOfDay get() = MinuteOfDay.ofMinute(startMinute)
    val end: MinuteOfDay get() = MinuteOfDay.ofMinute(endMinute)

    /**
     * 取消的结果。**刻意做成一个无字段的对象**，因此类型上无法承载任何数据变更信息。
     * 这是 D3 的结构性保证：不靠"记得别发通知"，而是根本表达不出来。
     */
    object CancelResult

    // ---------- 拖拽 ----------

    /** 整体移动：吸附后平移，保持时长（§8.3）。 */
    fun moveTo(rawStartMinute: Int, snapMinutes: Int, minDuration: Int, maxDuration: Int) {
        val moved = SnapCalculator.snapMove(
            start = rawStartMinute,
            end = rawStartMinute + (endMinute - startMinute),
            stepMinutes = snapMinutes,
            minDurationMinutes = minDuration,
            maxDurationMinutes = maxDuration,
        )
        startMinute = moved.first
        endMinute = moved.last
    }

    /** 拖下边缘：结束时间吸附并保证最短时长（§8.3）。 */
    fun resizeEndTo(rawEndMinute: Int, snapMinutes: Int, minDuration: Int) {
        endMinute = SnapCalculator.snapResizeEnd(startMinute, rawEndMinute, snapMinutes, minDuration)
    }

    /**
     * 业务方表单一次性设定起止时间与标题（PRD §8.3.1，AD-22）。
     *
     * **不发任何事件**，只改草稿——提交仍必须由调用方显式触发（View 层的
     * `confirmEdit()`），这保证了 D3「完成是唯一数据变更出口」不被削弱。
     *
     * 起止时间走 [SnapCalculator.applyRange] 与拖拽同一套规则，
     * **两端对称**（D11）；业务方填的越界值、填反的两端都会被兜底。
     *
     * [newContent] 为 null 表示「不改标题」，沿用当前 [pendingContent]。
     */
    fun applyEdit(
        rawStartMinute: Int,
        rawEndMinute: Int,
        snapMinutes: Int,
        minDuration: Int,
        maxDuration: Int,
        newContent: CharSequence?,
    ) {
        val range = SnapCalculator.applyRange(
            rawStart = rawStartMinute,
            rawEnd = rawEndMinute,
            stepMinutes = snapMinutes,
            minDurationMinutes = minDuration,
            maxDurationMinutes = maxDuration,
        )
        startMinute = range.first
        endMinute = range.last
        if (newContent != null) pendingContent = newContent
    }

    /** 拖上边缘：开始时间吸附并保证最短时长、不得晚于 24:00（§8.3）。 */
    fun resizeStartTo(rawStartMinute: Int, snapMinutes: Int, minDuration: Int) {
        startMinute = SnapCalculator.snapResizeStart(
            rawStart = rawStartMinute,
            end = endMinute,
            stepMinutes = snapMinutes,
            minDurationMinutes = minDuration,
        )
    }

    // ---------- 结束 ----------

    /**
     * 确认完成。返回应当上抛给业务方的动作。
     *
     * **只有这里会产出数据变更结果。** [cancel] 是它的镜像分支，恒返回 null。
     */
    fun commit(): Commit? {
        val o = origin
        return if (o == null) {
            Commit.Create(startMinute, endMinute, pendingContent)
        } else {
            Commit.Modify(
                event = o,
                range = startMinute..endMinute,
                hasConflict = hasConflict,
                content = pendingContent,
            )
        }
    }

    /**
     * 取消。**恒返回 [CancelResult]，不含任何数据**。
     *
     * 这是 D3 的落点：取消路径在类型上就没有能力表达"数据变更"，
     * 因此「点了取消却误改了数据」在编译期就被排除了。
     */
    fun cancel(): CancelResult = CancelResult


    /**
     * 业务方提交了新列表后调用。
     *
     * @param latest 按 id 索引的最新数据；id 重复时保留首条。
     * @return 保留的编辑态；为 null 表示**已自动取消编辑**（E20）。
     */
    fun onDataChanged(latest: Map<String, SanitizedEvent>): EditSession? {
        val o = origin ?: return this // 新建态与列表无关，不受影响（E21）
        val updated = latest[o.id]
        // E20：被编辑的日程已不在列表中 → 静默取消编辑态
            ?: return null
        // E28：仍在列表中但时间变了 → 保留编辑态并置冲突标记
        if (!EventDiff.sameRange(o, updated)) {
            hasConflict = true
        }
        return this
    }

    enum class DragMode {
        /** 尚未拖拽。 */
        NONE,

        /** 整体上下移动。 */
        MOVE,

        /** 拖上边缘。 */
        RESIZE_TOP,

        /** 拖下边缘。 */
        RESIZE_BOTTOM,
    }

    /** 确认后的动作。 */
    sealed class Commit {
        data class Create(
            val startMinute: Int,
            val endMinute: Int,
            /** 新建的显示内容；null 表示未填（PRD §7.7.1 允许空标题）。 */
            val content: CharSequence?,
        ) : Commit()

        data class Modify(
            val event: SanitizedEvent,
            val range: IntRange,
            val hasConflict: Boolean,
            /** 修改后的显示内容；null 表示原标题为空。 */
            val content: CharSequence?,
        ) : Commit()
    }

    companion object {
        /** 空白处点击 → 新建（§6.2 FI-005，默认时长 1 小时）。 */
        fun beginCreate(
            tapMinute: Int,
            snapMinutes: Int,
            defaultDurationMinutes: Int,
        ): EditSession {
            val r = SnapCalculator.newEventRangeAt(tapMinute, snapMinutes, defaultDurationMinutes)
            return EditSession(
                origin = null,
                startMinute = r.first,
                endMinute = r.last,
                dragMode = DragMode.NONE,
            )
        }

        /** 长按已有日程 → 编辑（§6.2 FI-004）。 */
        fun beginEdit(event: SanitizedEvent): EditSession = EditSession(
            origin = event,
            startMinute = event.start.minuteOfDay.coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE),
            endMinute = event.end.minuteOfDay.coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE),
            dragMode = DragMode.NONE,
        ).apply {
            // 草稿以原标题起步；表单传 null 时即为「不改标题」（AD-22）
            pendingContent = event.content
        }
    }
}
