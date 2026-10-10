package com.github.kevinvane.daytimeline.r8test

import android.content.Context
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.DayTimelineView.EditController
import com.github.kevinvane.daytimeline.library.api.EditDraft
import com.github.kevinvane.daytimeline.library.api.endMinute
import com.github.kevinvane.daytimeline.library.api.startMinute
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

/**
 * 以**业务方视角**触碰组件的全部对外能力。
 *
 * 这个类本身不做事，存在的意义是**被引用**：R8 保留规则写漏了哪个入口，
 * 这里就会在混淆后失联，从而被 `:r8test:connectedReleaseAndroidTest` 抓到。
 *
 * 它也顺带证明「接入方代码在混淆后仍能正常调用组件」——也就是 PRD §12.5 承诺的
 * 「业务方开启混淆后功能正常」。
 */
object ConsumerApiSmoke {

    /** 通过 XML 反射实例化路径构造：类名与构造器签名必须原名保留。 */
    fun newView(context: Context): DayTimelineView = DayTimelineView(context)

    /** 实现数据契约：接口方法签名必须在混淆后仍可被实现。 */
    fun event(id: String, content: String?): TimelineEvent = object : TimelineEvent {
        override val id: String = id
        override val start = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(540)
        override val end = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(600)
        override val content: CharSequence? = content
    }

    /** 实现监听契约：覆盖所有回调，确认签名未被裁剪。 */
    fun listener(onCreated: (IntRange) -> Unit): TimelineListener = object : TimelineListener {
        override fun onEventCreated(range: IntRange, content: CharSequence?) = onCreated(range)
    }

    /**
     * **Java 调用方**实现数据契约（OQ-7 / 风险 R12）。
     *
     * `JavaBusinessEvent` 是普通 Java 类：它编译不过就意味着 `TimelineEvent` 的签名
     * 又变得 Java 不可实现了（value class 会把方法名混淆成 `getStart-ZruiD9E()`）。
     * 经这里引用后，它同时进入混淆后的 release 仪器测试路径。
     */
    fun javaEvent(
        id: String,
        content: String?,
        startMinute: Int,
        endMinute: Int,
    ): TimelineEvent = JavaBusinessEvent(id, content, startMinute, endMinute)

    /** 第四层接管契约（AD-22）。 */
    fun controller(onEnter: (EditDraft) -> Unit): EditController = object : EditController {
        override fun onEnterEditing(draft: EditDraft): Boolean {
            onEnter(draft)
            return true
        }
    }

    /**
     * 详情弹窗契约（AD-23）。
     *
     * 这三个方法正是业务方渲染详情 + 发起操作的全部入口：
     * `detailOf` 读快照、`enterEditMode` 进编辑态（否则三个出口静默无效）、
     * `clearSelection` 清描边。字段名一旦被混淆，`.range` / `.content` 访问会抛
     * NoSuchFieldError。
     */
    fun detailApi(
        view: DayTimelineView,
        onDetail: (String, Int) -> Unit,
    ): Boolean {
        val detail = view.detailOf("e1") ?: return false
        // 读字段：range / content / color / 两个值类型扩展
        onDetail(detail.content?.toString().orEmpty(), detail.range.last)
        check(detail.startMinute.minuteOfDay >= 0)
        check(detail.endMinute.minuteOfDay >= 0)
        view.clearSelection()
        return true
    }
}