package com.github.kevinvane.daytimeline.library

import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 状态保存载体的完整往返测试（AD-11 / D12 / E9 / AD-28 的拆分）。
 *
 * ## 为什么单独建一个文件
 *
 * AD-28 把 `SavedState` 从 `DayTimelineView` 的嵌套类提成了文件级私有类
 * （为了把类从 detekt `LargeClass` 阈值附近拉回来）。提出去之后**最该确认的是
 * 三个字段有没有真的被存、被读**——而当时的巡检发现：既有的
 * `restoredScrollPositionIsNotOverwrittenByAutoLocate` 只覆盖了 `scrollOffset`
 * 一个字段，`viewDate` 与 `selectedId` 的读写**完全没有任何测试守着**。
 *
 * 实测确认过这个缺口：把 `viewDate = source.readLong()` 改成 `= 0L`、
 * 把 `selectedId` 的写出改成固定 `null`，两次 93 条测试**全绿**，一条都没转红。
 * 这两个字段一旦在拆分中被手改错，没有任何东西会报错。
 *
 * 因此这里逐字段钉住「写进去什么，就读回来什么」。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewSavedStateTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        // 与 DayTimelineViewBehaviorTest 用同一组配置，避免「同一行为两套基准」
        view.setConfig(
            TimelineConfig(
                hourHeight = 120,
                topPadding = 0,
                bottomPadding = 0,
                autoLocateOnFirstShow = false,
            ),
        )
    }

    /**
     * 走真实的 `saveHierarchyState` / `restoreHierarchyState`，不是直接碰 SavedState。
     *
     * 新建实例一律在 `runOnMainSync` 里：`DayTimelineView` 的构造函数要建 `Handler`，
     * 在测试线程上构造会抛「Can't create handler inside thread that has not called
     * Looper.prepare()」——这是测试自己的问题，不是组件的缺陷。
     */
    private fun restoreInto(clone: DayTimelineView, saved: DayTimelineView) {
        val container = SparseArray<Parcelable>()
        saved.saveHierarchyState(container)
        clone.id = STATE_VIEW_ID
        clone.restoreHierarchyState(container)
        clone.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT, View.MeasureSpec.EXACTLY),
        )
        clone.layout(0, 0, WIDTH, VIEWPORT)
    }

    /** `scrollOffset` 必须原样恢复，且不被首次定位覆盖。 */
    @Test
    fun scrollOffsetSurvivesRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.scrollToMinute(600, smooth = false)
            view.id = STATE_VIEW_ID
        }
        val savedOffset = view.getScrollOffset()
        assertTrue("前置条件：偏移应非 0，实际 $savedOffset", savedOffset > 0)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clone = DayTimelineView(context)
            restoreInto(clone, view)
            assertEquals("scrollOffset 必须原样恢复", savedOffset, clone.getScrollOffset())
        }
    }

    @Test
    fun viewDateSurvivesRoundTrip() {
        val threeDaysAgo = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.setViewDate(threeDaysAgo)
            view.id = STATE_VIEW_ID
        }

        var restored: Long? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clone = DayTimelineView(context)
            restoreInto(clone, view)
            restored = readViewDate(clone)
        }
        assertEquals(
            "viewDate 必须原样恢复（改成 0 时既有测试全绿，缺口即此）",
            threeDaysAgo,
            restored,
        )
    }

    /**
     * `selectedId`（选中态）同样要 survives。
     *
     * 与 `viewDate` 同源：写出固定 `null` 时也是 93 条全绿。
     * 选中态不恢复会让「选中一条 → 旋转 → 描边消失」，属于可见的状态丢失。
     */
    @Test
    fun selectedIdSurvivesRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // 先测量再取块：blocks / blockTops 在 measure 之后才有内容
            measureAndLayout(view)
            view.submitEvents(
                listOf(StubEvent(STUB_ID, 60, 120)),
                notifyIssues = false,
            )
            view.id = STATE_VIEW_ID
            // 走真实点击选中，而不是反射写字段：
            // 用「用户怎么选中」的方式，顺带验证选中确实写进了 selectedId
            val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == STUB_ID }
            requireNotNull(block) { "前置条件：视口内找不到 $STUB_ID" }
            view.dispatchEventClickForAccessibility(block.second)
        }
        requireNotNull(readSelectedId(view)) {
            "点击后 selectedId 应被置上，否则本用例自身失效"
        }

        var restored: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clone = DayTimelineView(context)
            restoreInto(clone, view)
            restored = readSelectedId(clone)
        }
        assertEquals(
            "selectedId 必须原样恢复（写成 null 时既有测试全绿，缺口即此）",
            STUB_ID,
            restored,
        )
    }

    /** 反向对照：没有选中、没有改日期时，恢复出来的也应是干净的默认值。 */
    @Test
    fun defaultsSurviveRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            measureAndLayout(view)
            view.id = STATE_VIEW_ID
        }
        val expected = readViewDate(view)

        var restoredDate: Long? = null
        var restoredId: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clone = DayTimelineView(context)
            restoreInto(clone, view)
            restoredDate = readViewDate(clone)
            restoredId = readSelectedId(clone)
        }
        assertEquals("构造时的默认日期应与恢复值一致", expected, restoredDate)
        assertNull("未选中时恢复出的 selectedId 应为 null", restoredId)
    }

    /** measure + layout：拿 blocks / blockTops 之前必须先做，否则取不到块。 */
    private fun measureAndLayout(target: DayTimelineView) {
        target.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT, View.MeasureSpec.EXACTLY),
        )
        target.layout(0, 0, WIDTH, VIEWPORT)
    }

    // ---- 反射读取（SavedState 是 private，字段在视图上） ----

    private fun readViewDate(target: DayTimelineView): Long =
        DayTimelineView::class.java.getDeclaredField("viewDate")
            .apply { isAccessible = true }
            .get(target) as Long

    private fun readSelectedId(target: DayTimelineView): String? =
        DayTimelineView::class.java.getDeclaredField("selectedId")
            .apply { isAccessible = true }
            .get(target) as String?

    private data class StubEvent(
        override val id: String,
        private val s: Int,
        private val e: Int,
    ) : com.github.kevinvane.daytimeline.library.core.TimelineEvent {
        override val start
            get() = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(s)
        override val end
            get() = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(e)
    }

    private companion object {
        const val WIDTH = 1080
        const val VIEWPORT = 1920
        const val STATE_VIEW_ID = 0x7f0a00aa
        const val STUB_ID = "stub-event"
    }
}
