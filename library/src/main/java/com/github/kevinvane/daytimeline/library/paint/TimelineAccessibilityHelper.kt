package com.github.kevinvane.daytimeline.library.paint

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.R
import com.github.kevinvane.daytimeline.library.core.EventState
import com.github.kevinvane.daytimeline.library.core.PlacedBlock

/**
 * 无障碍虚拟视图（PRD Q8 / UF-002 / UF-003 / FI-016 / AD-05）。
 *
 * ## 为什么需要它
 *
 * AD-01 要求全量自绘、不创建子 View，于是**没有真实 View** 可供屏幕阅读器遍历。
 * Android 的无障碍框架只认 View 树，因此必须用 [ExploreByTouchHelper]
 * 为每个**视口内可见**的日程块提供虚拟节点。
 *
 * ## 范围限制
 *
 * 只为**可见**的块生成节点。视口外的块不生成——否则屏幕阅读器会念出
 * 用户看不到的内容（Q8 要求「可逐条读出」可见日程）。
 *
 * 代价：需要实现三组回调，且 R7 已登记「无障碍容易被压缩排期」。
 * 这是 M5 出口标准（Q8）锁死的部分，不可省略。
 */
internal class TimelineAccessibilityHelper(
    private val host: DayTimelineView,
) : ExploreByTouchHelper(host) {

    /** 虚拟节点 id：直接用 block 下标 + 1（0 保留给根节点）。 */
    private fun virtualIdFor(index: Int): Int = index + 1

    private fun indexFor(virtualViewId: Int): Int = virtualViewId - 1

    /** 当前可见且可交互的块。 */
    private fun visibleBlocks(): List<Pair<Int, PlacedBlock>> =
        host.visibleBlockSnapshot()

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        visibleBlocks().forEach { (index, block) ->
            val rect = host.blockBoundsInParent(block) ?: return@forEach
            if (rect.contains(x.toInt(), y.toInt())) return virtualIdFor(index)
        }
        return HOST_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        visibleBlocks().forEach { (index, _) -> virtualViewIds.add(virtualIdFor(index)) }
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat,
    ) {
        val snapshot = visibleBlocks()
        val index = indexFor(virtualViewId)
        val entry = snapshot.getOrNull(index)
        if (entry == null) {
            // 块可能已随滚动离开视口：给一个空节点而不是抛异常（Q4 不崩溃）
            node.contentDescription = ""
            node.setBoundsInScreen(Rect(0, 0, 0, 0))
            return
        }
        val (_, block) = entry
        val ctx: Context = host.context
        val state = host.blockStateOf(block)
        val stateText = when (state) {
            EventState.PAST -> ctx.getString(R.string.day_timeline_a11y_state_past)
            EventState.ONGOING -> ctx.getString(R.string.day_timeline_a11y_state_ongoing)
            EventState.UPCOMING -> ctx.getString(R.string.day_timeline_a11y_state_upcoming)
        }
        val title = block.event.content?.toString()
            ?: ctx.getString(R.string.day_timeline_a11y_no_content)
        node.contentDescription = ctx.getString(
            R.string.day_timeline_a11y_event,
            title,
            block.event.start.toString(),
            block.event.end.toString(),
            stateText,
        )
        node.className = View::class.java.name
        node.isClickable = true
        node.isFocusable = true
        // 点击事件块（FI-003）；长按进入编辑（FI-004）通过 ACTION_LONG_CLICK
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
        host.blockBoundsInParent(block)?.let { node.setBoundsInScreen(it) }
            ?: node.setBoundsInScreen(Rect(0, 0, 0, 0))
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean {
        val snapshot = visibleBlocks()
        val entry = snapshot.getOrNull(indexFor(virtualViewId)) ?: return false
        return when (action) {
            AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                host.dispatchEventClickForAccessibility(entry.second)
                true
            }
            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                host.dispatchEventLongClickForAccessibility(entry.second)
                true
            }
            else -> false
        }
    }

    /** 根节点：描述整体时间轴（FI-016 焦点遍历的落点）。 */
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val ctx = host.context
        info.className = DayTimelineView::class.java.name
        info.contentDescription = ctx.getString(
            R.string.day_timeline_a11y_grid,
            (host as DayTimelineView).currentHourRangeText(),
        )
    }

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(host, event)
        event.className = DayTimelineView::class.java.name
    }
}

/** 挂载无障碍 helper。 */
internal object InstallAccessibility {
    fun install(host: DayTimelineView) {
        ViewCompat.setAccessibilityDelegate(host, TimelineAccessibilityHelper(host))
    }
}
