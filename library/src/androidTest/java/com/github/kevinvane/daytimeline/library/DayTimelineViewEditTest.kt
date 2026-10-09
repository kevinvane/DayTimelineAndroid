package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.Geometry
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 编辑闭环的真机测试：`confirmEdit` / `cancelEdit` / `requestDelete` 三个出口 + 第四层接管。
 *
 * 这四个出口此前**零测试覆盖**，而它们正是 D3 红线（取消绝不产生数据变更通知）
 * 在 View 层的落点。`EditSession` 的纯 JVM 单测只能证明状态机本身干净，
 * 证不了「View 有没有在取消路径上顺手发了通知」——必须在这层兜住。
 *
 * 断言方式刻意用**「记录全部回调」**而非逐个回调计数：
 * 新增一个数据变更回调时，这个测试会立刻失败，而不是悄悄漏检。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewEditTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    /** 收到过的全部回调，按名字记录——任何一个都不该在取消路径上出现。 */
    private val fired = mutableListOf<String>()
    private var lastRange: IntRange? = null
    private var lastModifiedId: String? = null
    private var lastDeletedId: String? = null
    private var confirmTitle: String? = null

    private data class Ev(
        override val id: String,
        private val s: Int,
        private val e: Int,
        override val content: CharSequence? = null,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    @Before
    fun setUp() {
        fired.clear()
        lastRange = null
        lastModifiedId = null
        lastDeletedId = null
        confirmTitle = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        view.listener = object : TimelineListener {
            override fun onEventClick(event: TimelineEvent) { fired += "click" }

            override fun onEventLongClick(event: TimelineEvent) { fired += "longClick" }

            override fun onEventCreated(range: IntRange) {
                fired += "created"
                lastRange = range
            }

            override fun onEventModified(event: TimelineEvent, range: IntRange, hasConflict: Boolean) {
                fired += "modified"
                lastModifiedId = event.id
                lastRange = range
            }

            override fun onEventDeleted(event: TimelineEvent) {
                fired += "deleted"
                lastDeletedId = event.id
            }

            override fun onEditCancelled() { fired += "cancelled" }
        }
        view.submitEvents(
            listOf(
                Ev("morning", 9 * 60, 10 * 60, "会议评审"),
                Ev("afternoon", 14 * 60, 15 * 60, "深度工作"),
            ),
            notifyIssues = false,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    /**
     * D3 核心红线：**取消不得发出任何数据变更通知**。
     *
     * 此前这条只有 `EditSessionTest` 在纯 JVM 层验证过；View 层的
     * `cancelEdit()` 有没有顺手发通知，没有任何测试能抓住。
     */
    @Test
    fun cancelEmitsNoDataChangeAtAll() {
        enterEditByAccessibility("morning")
        assertTrue("前置条件：应已进入编辑态", view.isEditing())
        fired.clear() // 滤掉进入编辑态时的 longClick

        view.cancelEdit()

        assertFalse("取消后必须退出编辑态", view.isEditing())
        assertEquals("取消只允许触发 onEditCancelled，实际回调：$fired", listOf("cancelled"), fired)
    }

    /** 确认修改：必须上抛 onEventModified，且带上正确的 id 与新范围。 */
    @Test
    fun confirmModificationEmitsEventModified() {
        enterEditByAccessibility("morning")
        fired.clear() // 滤掉进入编辑态时的 longClick

        view.confirmEdit()

        assertTrue("应触发 onEventModified，实际回调：$fired", fired.contains("modified"))
        assertEquals("回传的应是修改前的那条日程", "morning", lastModifiedId)
        assertEquals(9 * 60..10 * 60, lastRange)
        assertFalse("确认后必须退出编辑态", view.isEditing())
        assertFalse("确认路径不应触发取消回调", fired.contains("cancelled"))
    }

    /**
     * 回归：`EditController.onDelete()` 返回 true（业务方接管）时，
     * 必须把 `editSession` / `selectedId` / `grabbedHandle` 三项状态一并清干净，
     * **并且要触发刷新**。
     *
     * 修复前该分支只清了 `editSession`：选中描边与编辑层会一直留在屏幕上，
     * 直到下一次无关刷新才消失；而 `requestDelete()` 是业务方按钮调的、
     * 不经过 `onTouchEvent`，所以不会有任何自动 invalidate 兜底。
     */
    @Test
    fun requestDeleteTakenOverByControllerClearsAllEditState() {
        enterEditByAccessibility("morning")
        view.editController = object : DayTimelineView.EditController {
            override fun onDelete(): Boolean = true
        }
        fired.clear()

        val signatureBefore = readLong("lastRenderSignature")
        view.requestDelete()

        assertFalse("接管后必须退出编辑态", view.isEditing())
        assertNull("接管后不得残留选中态，否则选中描边会留在屏幕上", readString("selectedId"))
        assertEquals("接管后不得残留手柄抓取状态", 0, readInt("grabbedHandle"))
        assertTrue(
            "接管后必须触发刷新（requestRefresh），否则界面不重绘；" +
                "签名未变说明 requestRefresh 没跑：before=$signatureBefore",
            readLong("lastRenderSignature") != signatureBefore,
        )
        assertTrue("接管删除不得发出任何删除/创建/修改事件，实际回调：$fired", fired.isEmpty())
    }

    /**
     * 未被接管时走二次确认 → 确认后真删并上抛 `onEventDeleted`。
     *
     * 这里覆写 `confirmDelete` 直接确认，避开真机上 `AlertDialog` 的时序，
     * 本测试只关心「确认之后」的状态与回调，不关心对话框本身。
     */
    @Test
    fun confirmedDeleteRemovesEventAndEmitsOnEventDeleted() {
        enterEditByAccessibility("morning")
        view.editController = object : DayTimelineView.EditController {
            override fun onDelete(): Boolean = false
            override fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit) {
                confirmTitle = eventTitle
                onConfirmed()
            }
        }
        fired.clear()

        view.requestDelete()

        assertTrue("确认后应触发 onEventDeleted，实际回调：$fired", fired.contains("deleted"))
        assertEquals("删除的应是当前编辑的那条", "morning", lastDeletedId)
        assertEquals("二次确认应带上日程标题", "会议评审", confirmTitle)
        assertFalse("删除后必须退出编辑态", view.isEditing())
        assertNull("删除后不得残留选中态", readString("selectedId"))
        // 断言数据层而非 visibleBlockSnapshot：默认格高下 14:00 的块在 1920px 视口内
        // 位于折叠线以下（scrollOffset=0），视口快照会是空的，与删除是否成功无关。
        assertEquals("被删的日程应从列表移除", listOf("afternoon"), readEventIds())
    }

    /** 空白处点击 → 新建草稿；完成后走 `onEventCreated`，范围应等于默认值。 */
    @Test
    fun tappingEmptySpaceCreatesDraftAndConfirmEmitsCreated() {
        view.setConfig(com.github.kevinvane.daytimeline.library.api.TimelineConfig(defaultNewDurationMinutes = 30))

        tapAt(viewYAtMinute(3 * 60))

        assertTrue("点空白应进入新建编辑态", view.isEditing())
        assertTrue("点空白不得触发长按回调，实际回调：$fired", !fired.contains("longClick"))
        fired.clear()

        view.confirmEdit()

        assertTrue("新建确认应触发 onEventCreated，实际回调：$fired", fired.contains("created"))
        assertEquals("新建范围应为 03:00 起、默认 30 分钟", 3 * 60..3 * 60 + 30, lastRange)
        assertFalse("新建确认后必须退出编辑态", view.isEditing())
    }

    /** 未处于编辑态时调用三个出口都必须是安全的空操作（不得崩、不得发事件）。 */
    @Test
    fun editApisAreSafeWhenNotEditing() {
        assertFalse(view.isEditing())
        view.confirmEdit()
        view.cancelEdit()
        view.requestDelete()

        assertTrue("非编辑态下三个出口都不应触发任何回调，实际回调：$fired", fired.isEmpty())
    }

    // ---- 进入编辑态的两种方式 ----

    /** 走无障碍长按入口（等价于用户长按日程块）。 */
    private fun enterEditByAccessibility(id: String) {
        val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == id }?.second
        requireNotNull(block) { "测试前置失败：视口内找不到日程 $id" }
        view.dispatchEventLongClickForAccessibility(block)
    }

    /** 在指定视口 y 处模拟一次「按下即抬起」的点击（不移动 → 判定为 Click）。 */
    private fun tapAt(y: Int) {
        val x = WIDTH / 2
        val down = System.currentTimeMillis()
        listOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
        ).forEach { action ->
            val e = MotionEvent.obtain(down, down, action, x.toFloat(), y.toFloat(), 0)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
            e.recycle()
        }
    }

    /** 视口坐标：分钟 → y。 */
    private fun viewYAtMinute(minute: Int): Int =
        readDimensInt("topPadding") +
            Geometry.minuteToOffset(minute.toFloat(), readDimensInt("effectiveHourHeight")).toInt() -
            view.getScrollOffset()

    // ---- 反射工具 ----

    private fun fieldOf(name: String) =
        DayTimelineView::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun readInt(name: String): Int = (fieldOf(name).get(view) as Number).toInt()

    private fun readLong(name: String): Long = (fieldOf(name).get(view) as Number).toLong()

    private fun readString(name: String): String? = fieldOf(name).get(view) as String?

    /** 数据层当前的日程 id 列表（不依赖视口可见性）。 */
    private fun readEventIds(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val list = fieldOf("events").get(view) as List<com.github.kevinvane.daytimeline.library.core.SanitizedEvent>
        return list.map { it.id }
    }

    private fun readDimensInt(name: String): Int {
        val dimens = fieldOf("dimens").get(view)!!
        val cls = dimens.javaClass
        val f = runCatching { cls.getDeclaredField(name) }.getOrNull()
        if (f != null) return (f.apply { isAccessible = true }.get(dimens) as Number).toInt()
        val getter = cls.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
            .apply { isAccessible = true }
        return (getter.invoke(dimens) as Number).toInt()
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
