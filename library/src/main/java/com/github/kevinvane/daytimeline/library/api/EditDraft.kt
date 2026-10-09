package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

/**
 * 进入编辑态时交给业务方的快照，用于**预填表单**（PRD §7.7.1 / AD-22）。
 *
 * ## 解决什么问题
 *
 * 第四层接管此前只能拿到 `onDone(range, isCreating)`：既不知道在改哪一条，
 * 也读不到草稿（`EditSession` 是 View 私有字段），业务方**无法预填表单**。
 * 本对象把「组件此刻认为的草稿」如实交出去，表单拿它做初值。
 *
 * ## 不可变
 *
 * 拿到的是**进入编辑态那一刻**的值。用户在时间轴上拖拽后草稿会变，
 * 但那是组件内部状态，不回推给本对象——需要最新值请在提交时读 [EditResult]。
 *
 * @property isCreating 新建为 true，修改为 false。
 * @property event 修改态：修改**前**的日程对象；新建态为 null。
 * @property range 当前起止时间，已吸附、已兜底、已钳制到 `[00:00, 24:00]`。
 * @property content 当前显示内容；新建态为 null。
 */
data class EditDraft(
    val isCreating: Boolean,
    val event: TimelineEvent?,
    val range: IntRange,
    val content: CharSequence?,
)

/** 草稿开始时刻的值类型形式，避免业务方接触裸分钟数（PRD D18/D19）。 */
val EditDraft.startMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.first)

/** 草稿结束时刻的值类型形式。 */
val EditDraft.endMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.last)
