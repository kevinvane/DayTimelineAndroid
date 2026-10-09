package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.DataIssue
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

/**
 * 业务方可感知的事件（PRD §11.3 / §15）。
 *
 * **D3 硬性要求**：实现 [onEditCancelled] 时**绝不可以**修改业务方数据。
 * 该回调仅用于统计「编辑完成率」，组件不采集、不上报、不存储任何数据（§15 口径）。
 */
interface TimelineListener {

    /** 日程点击（用户短按日程块）。 */
    fun onEventClick(event: TimelineEvent) = Unit

    /** 日程长按（长按日程块进入编辑态）。 */
    fun onEventLongClick(event: TimelineEvent) = Unit

    /**
     * 日程新建完成。组件只通知，不落库——是否持久化由业务方决定（§11.4）。
     *
     * @param range 新建的起止时间（已吸附、已兜底、已钳制到全天范围）。
     * @param content 新建的显示内容；null 表示未填写（PRD §7.7.1 允许空标题）。
     */
    fun onEventCreated(range: IntRange, content: CharSequence? = null) = Unit

    /**
     * 日程修改完成。
     *
     * @param event 修改前的日程对象。
     * @param range 修改后的起止时间。
     * @param content 修改**后**的显示内容；null 表示原标题为空。
     *   与 [event.content] 相比即可得知标题是否被改动。
     * @param hasConflict 为 true 表示编辑期间业务方改动过同一条日程（PRD E28），
     *   组件已保留用户编辑态，是否覆盖由业务方决定。
     */
    fun onEventModified(
        event: TimelineEvent,
        range: IntRange,
        content: CharSequence? = null,
        hasConflict: Boolean,
    ) = Unit

    /** 日程删除（用户二次确认之后）。 */
    fun onEventDeleted(event: TimelineEvent) = Unit

    /**
     * 编辑取消。**不携带任何数据变更信息**（D3 / §11.3）。
     * 业务方无需、也不应据此修改数据。
     */
    fun onEditCancelled() = Unit

    /**
     * 数据异常（PRD §9.3）。组件修正了不合规数据时回调，修正对用户不可见。
     * 业务方可据此排查自身数据质量。
     */
    fun onDataIssues(issues: List<DataIssue>) = Unit

    /** 当前时间线定时刷新，携带当前时刻。 */
    fun onNowRefreshed(nowMinute: Int) = Unit
}

/** 便捷工厂：把一段起止时间还原成 [MinuteOfDay] 对。 */
fun IntRange.toMinuteRange(): Pair<MinuteOfDay, MinuteOfDay> =
    MinuteOfDay.ofMinute(first) to MinuteOfDay.ofMinute(last)
