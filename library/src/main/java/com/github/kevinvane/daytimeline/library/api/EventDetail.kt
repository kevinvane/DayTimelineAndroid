package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay

/**
 * 某条日程的**只读**详情快照，供业务方渲染详情界面（AD-23 / PRD §7.7.1）。
 *
 * ## 定位：这是「读」，不是「写」
 *
 * 本快照存在的唯一目的是让业务方不必自己去 [com.github.kevinvane.daytimeline.library.core.TimelineEvent]
 * 上拼凑展示数据，也不需要为了显示详情而改动组件的数据契约。
 *
 * **它不含任何输入控件，也不承担写回职责**——修改标题 / 时间 / 颜色仍走
 * AD-22 的第四层接管（`EditController` + `DayTimelineView.applyEdit`）。
 * 这一点是刻意的：详情弹窗由业务方实现（[com.github.kevinvane.daytimeline.library.DayTimelineView]
 * 零新增 View），而表单输入是业务数据的一部分，组件不持有。
 *
 * ## 字段全部取自组件已持有的数据
 *
 * 没有一个字段是新引入的业务字段：
 *
 * | 字段 | 来源 |
 * |---|---|
 * | [id] | `TimelineEvent.id` |
 * | [range] | 组件内部的兜底修正**之后**的值 |
 * | [content] | `TimelineEvent.content` |
 * | [color] | `TimelineEvent.color`（AD-22 业务色第三通道） |
 *
 * @property id 日程唯一标识。
 * @property range 起止时间。已按 §9.3 兜底修正并钳制到 `[00:00, 24:00]`，
 *   因此可直接用于展示，不需要业务方再做合法性判断。
 * @property content 显示内容（标题）；允许为 null（§7.7.1 空标题）。
 * @property color 业务色；null 表示使用组件默认块配色。
 *   **深浅适配责任在业务方**（§7.7.1）。
 */
data class EventDetail(
    val id: String,
    val range: IntRange,
    val content: CharSequence?,
    val color: Int?,
)

/** 详情快照的开始时刻值类型形式，避免业务方接触裸分钟数（PRD D18/D19）。 */
val EventDetail.startMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.first)

/** 详情快照的结束时刻值类型形式。 */
val EventDetail.endMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.last)
