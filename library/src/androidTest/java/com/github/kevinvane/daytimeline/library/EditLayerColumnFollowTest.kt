package com.github.kevinvane.daytimeline.library

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 拖拽 / 编辑态的选框与手柄跟随**被编辑日程块所在列**——
 * 重叠分栏时只覆盖该块那一列，不再占满整行。
 *
 * 几何计算本身由纯 JVM 的 `Geometry.editLayerBounds` 单测守住；这里补的是
 * **View 把手柄热区接到了哪一列**。绘制与命中若各用一套中心，手柄会画在一处、
 * 点起来却在另一处（UF-001）——这类「声明一套、用另一套」的缺陷只有真机点得出来。
 *
 * 观测点用 `grabbedHandle`（DOWN 时由 `HitTester` 置位，UP 清零，必须在两者之间读）：
 * - 在**被编辑块所在列中心**按下 → 应抓到上手柄；
 * - 在**另一列中心**按下 → 不应抓到（证明中心确实随列移动，而非停在内容区中心）。
 */
@RunWith(AndroidJUnit4::class)
class EditLayerColumnFollowTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
            view.setConfig(
                TimelineConfig(
                    hourHeight = 120,
                    axisWidth = 120,
                    topPadding = 0,
                    bottomPadding = 0,
                    snapMinutes = 0,
                    autoLocateOnFirstShow = false,
                    showNowIndicator = false,
                ),
            )
            // 两条**完全同时**的日程 → 必分两列：a 在左列、b 在右列（§7.9 W1）
            view.submitEvents(
                listOf(StubEvent("a", 600, 720), StubEvent("b", 600, 720)),
                notifyIssues = false,
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
        }
    }

    @Test
    fun handleHotZoneFollowsEditedBlockColumn() {
        val blockB = block("b")
        val topB = bounds(blockB).top
        val centerB = centerX(blockB)
        val centerA = centerX(block("a"))

        assertTrue(
            "两列中心必须拉得足够开（否则本用例证明不了什么）：A=$centerA B=$centerB",
            kotlin.math.abs(centerA - centerB) > MIN_SEPARATION_PX,
        )

        assertEquals(
            "被编辑块所在列的中心应抓到手柄",
            TOP_HANDLE,
            probeGrabbed("b", centerB, topB),
        )
        assertTrue(
            "另一列的中心不应抓到手柄（手柄中心必须随被编辑块移动）",
            probeGrabbed("b", centerA, topB) != TOP_HANDLE,
        )
    }

    // ---- 探针 ----

    /**
     * 在 (x, y) 注入一次「按下 → 抬手」，返回按下瞬间的 `grabbedHandle`。
     *
     * 抬手可能经 FI-010 收掉编辑态（点块外即取消），故每次探针前**无条件**重进目标块的编辑态。
     */
    private fun probeGrabbed(editId: String, x: Float, y: Int): Int {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchEventLongClickForAccessibility(block(editId))
        }
        return downThenRead(x, y)
    }

    private fun downThenRead(x: Float, y: Int): Int {
        var grabbed = Int.MIN_VALUE
        val down = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y.toFloat(), 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
        e.recycle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            grabbed = fieldOf("grabbedHandle").getInt(view)
        }
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y.toFloat(), 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(up) }
        up.recycle()
        return grabbed
    }

    private fun block(id: String) =
        view.visibleBlockSnapshot().first { it.second.event.id == id }.second

    private fun bounds(block: com.github.kevinvane.daytimeline.library.core.PlacedBlock) =
        requireNotNull(view.blockBoundsInParent(block)) { "取不到块边界" }

    private fun centerX(block: com.github.kevinvane.daytimeline.library.core.PlacedBlock): Float {
        val b = bounds(block)
        return (b.left + b.right) / 2f
    }

    private fun fieldOf(name: String) =
        DayTimelineView::class.java.getDeclaredField(name).apply { isAccessible = true }

    private data class StubEvent(
        override val id: String,
        private val s: Int,
        private val e: Int,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val TOP_HANDLE = 1

        /** 48dp 热区直径在 420dpi 上约 126px；两列中心至少拉开这个数才具区分度。 */
        const val MIN_SEPARATION_PX = 160f
    }
}
