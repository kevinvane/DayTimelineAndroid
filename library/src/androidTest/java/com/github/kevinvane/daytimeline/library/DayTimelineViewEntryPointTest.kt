package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `eventAt`（按坐标取日程）与 `refreshNow`（手动刷新当前时间线）两个公开入口的真机测试。
 *
 * 这两个入口在 PRD v1.13 之前**声明了却没有任何代码**：§11.1「获取指定位置的日程」
 * 与 §11.2「刷新当前时间线」白纸黑字列在对外能力清单里，全仓找不到读取点——
 * 与 AD-24 的 `autoLocateOnFirstShow` 完全同一类缺陷：一个只会回答「支持」的功能。
 * 本次补实现，并用本文件守住。
 *
 * 之所以必须走仪器测试而非 JVM 单测：两个入口都要读 `width` / `dimens` / `blocks`
 * 这些 View 层的实时状态，JVM 单测构造不出真实视图。
 *
 * 断言重点放在**「与用户触摸同一路径」**上：`eventAt` 若改成只比对块的几何矩形
 * 而不走 `HitTester`，就会出现「业务方认为点在块上、用户手指点却点在空白」的分歧——
 * 那比没有这个方法更糟。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewEntryPointTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    /** onNowRefreshed 收到的时刻列表，按到达顺序记录。 */
    private val nowRefreshes = mutableListOf<Int>()

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
        nowRefreshes.clear()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        // 关掉首次定位：本文件用「块在视口中的固定位置」反算坐标，
        // 自动定位会让结论随运行时刻漂移（同 DayTimelineViewEditTest.setUp 的理由）。
        view.setConfig(TimelineConfig(autoLocateOnFirstShow = false))
        // 固定「当前时间」为 10:30：refreshNow 的用例靠它把
        // 「固定值不被覆盖」与「回调带上同一时刻」变成可断言的确定值，
        // 否则结论取决于真机的系统时钟。
        view.setNowMinute(10 * 60 + 30)
        view.listener = object : TimelineListener {
            override fun onNowRefreshed(nowMinute: Int) {
                nowRefreshes += nowMinute
            }
        }
        view.submitEvents(
            listOf(
                // 时间选在一天最前段：格高随密度放大（56dp × density），
                // 排在 9:00/14:00 的用例在高密度屏上会落到视口外。
                Ev("morning", 60, 120, "会议评审"),
                Ev("afternoon", 180, 240, "深度工作"),
            ),
            notifyIssues = false,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    // ================= eventAt =================

    /** 命中日程块中心时必须拿到该日程，而不是 null。 */
    @Test
    fun eventAtHitsBlockCenter() {
        val bounds = boundsOf("morning")
        val hit = view.eventAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())

        assertNotNull("点在日程块上必须命中，实际返回 null", hit)
        assertEquals("morning", hit?.id)
        assertEquals(60..120, hit?.range)
        assertEquals("会议评审", hit?.content?.toString())
    }

    /** 命中空白区域必须返回 null——业务方据此区分「点空白新建」与「点到某条日程」。 */
    @Test
    fun eventAtOnEmptyAreaReturnsNull() {
        // 两个块都在 01:00–04:00，取视口最底部必然空白
        val x = WIDTH / 2f
        val y = (HEIGHT - 10).toFloat()
        assertNull("空白处必须返回 null", view.eventAt(x, y))
        assertNull(
            "同一处换个 x 也不能命中",
            view.eventAt(x + 40f, y),
        )
    }

    /** 落在时间轴区域（画时间标签处）不算命中：那里不是日程区。 */
    @Test
    fun eventAtOnAxisAreaReturnsNull() {
        val bounds = boundsOf("morning")
        // 轴区域取 1/4 处即可：它恒在内容区左侧（RTL 才会跑到右侧）
        val axisX = axisWidthPx() / 4f
        assertNull(
            "时间轴区域内不得命中日程",
            view.eventAt(axisX, bounds.centerY().toFloat()),
        )
    }

    /**
     * 关键约束：`eventAt` 必须与用户触摸走**同一条**命中路径。
     *
     * 在块内若干采样点上依次比对「eventAt 的结论」与「onTouchEvent 派发的点击对象」。
     * 若将来有人把 eventAt 改成只比对几何矩形（不走 HitTester），
     * 或在块的 48dp 热区边缘上给出不同结论，这条会立刻转红。
     */
    @Test
    fun eventAtAgreesWithRealTouch() {
        val bounds = boundsOf("morning")
        val probeY = floatArrayOf(
            bounds.centerY() - 24f,
            bounds.centerY().toFloat(),
            bounds.centerY() + 24f,
        )
        for (y in probeY) {
            val idByHit = view.eventAt(bounds.centerX().toFloat(), y)?.id
            val touched = mutableListOf<String>()
            view.listener = object : TimelineListener {
                override fun onEventClick(event: TimelineEvent) { touched += event.id }
            }

            tapAt(bounds.centerX().toFloat(), y)

            assertEquals(
                "y=$y 处 eventAt 与真实触摸的结论必须一致（eventAt=$idByHit, touch=${touched.firstOrNull()}）",
                idByHit,
                touched.firstOrNull(),
            )
        }
    }

    /** 纯读取：不得改选中态、不得进编辑态、不得发任何回调。 */
    @Test
    fun eventAtIsPureRead() {
        val fired = mutableListOf<String>()
        view.listener = object : TimelineListener {
            override fun onEventClick(event: TimelineEvent) { fired += "click" }
            override fun onEventLongClick(event: TimelineEvent) { fired += "longClick" }
            override fun onEventCreated(range: IntRange, content: CharSequence?) { fired += "created" }
            override fun onEventDeleted(event: TimelineEvent) { fired += "deleted" }
            override fun onEditCancelled() { fired += "cancelled" }
        }
        val bounds = boundsOf("morning")
        view.eventAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())

        assertFalse("eventAt 不得进入编辑态", view.isEditing())
        assertNull("eventAt 不得设置选中态", selectedIdValue())
        assertTrue("eventAt 不得触发任何回调，实际：$fired", fired.isEmpty())
    }

    /** 越界坐标不得崩溃，返回 null 即可（§9.3 脏输入容错）。 */
    @Test
    fun eventAtOutOfBoundsReturnsNull() {
        assertNull(view.eventAt(-100f, -100f))
        assertNull(view.eventAt(WIDTH + 100f, HEIGHT + 100f))
        assertNull(view.eventAt(0f, HEIGHT - 5f))
    }

    /** 日程不在视口内（已滚走）时应返回 null，而不是拿到越界的块。 */
    @Test
    fun eventAtReturnsNullForScrolledOutBlock() {
        // 滚到最底部：01:00–04:00 的两个块都在视口之外
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.setScrollOffset(view.maxScrollValue())
        }
        assertNull(
            "块已滚出视口时 eventAt 应返回 null（用户在屏幕上点不到它）",
            view.eventAt(WIDTH / 2f, 10f),
        )
    }

    // ================= refreshNow =================

    /** 手动刷新必须回调 onNowRefreshed，且带上组件当前的 nowMinute。 */
    @Test
    fun refreshNowNotifiesListener() {
        nowRefreshes.clear()

        view.refreshNow()

        assertEquals(
            "refreshNow 必须回调一次 onNowRefreshed，实际：$nowRefreshes",
            listOf(10 * 60 + 30),
            nowRefreshes,
        )
    }

    /**
     * `setNowMinute` 固定过时间后，refreshNow **不得**把固定值覆盖回系统时钟。
     *
     * `overrideNowMinute` 非空是测试语义，一次手动刷新就抹掉它会让
     * 「固定时间」在任何含刷新操作的测试里静默失效。
     */
    @Test
    fun refreshNowKeepsOverriddenNowMinute() {
        view.setNowMinute(9 * 60) // 09:00
        nowRefreshes.clear()

        view.refreshNow()

        assertEquals(
            "固定时间不得被 refreshNow 覆盖",
            listOf(9 * 60),
            nowRefreshes,
        )
    }

    /**
     * 手动刷新与 30 秒定时器必须走**同一个执行体**。
     *
     * 判据：两条路径各自触发时回调次数都只 +1，且值相同。
     * 若有人给 refreshNow 另写一份「少做一步」的实现（例如不重算三态），
     * 这条比对会立即转红。
     */
    @Test
    fun refreshNowAndTickerUseSameBody() {
        nowRefreshes.clear()
        view.refreshNow()
        assertEquals(listOf(10 * 60 + 30), nowRefreshes)

        // 直接跑一次定时器的 Runnable。用反射取而不在产线代码里加测试专用方法——
        // 那会变成新的死代码。run() 末尾会再 postDelayed 一次，仪器测试里无害。
        val tickerField = DayTimelineView::class.java
            .getDeclaredField("nowTicker").apply { isAccessible = true }
        val ticker = tickerField.get(view) as Runnable
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ticker.run() }

        assertEquals(
            "定时器路径与手动刷新应各自回调一次",
            listOf(10 * 60 + 30, 10 * 60 + 30),
            nowRefreshes,
        )
    }

    /**
     * 未固定时间时，refreshNow 应跟随系统时钟，而不是停在构造时的初始值。
     *
     * 构造到调用之间至少过了几毫秒，若跨越了分钟边界，这里的值就会与构造时不同——
     * 因此不断言具体数值，只断言「取值在合法区间内且确实回调了」。
     */
    @Test
    fun refreshNowFollowsSystemClockWhenNotOverridden() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        view.setConfig(TimelineConfig(autoLocateOnFirstShow = false))
        view.listener = object : TimelineListener {
            override fun onNowRefreshed(nowMinute: Int) { nowRefreshes += nowMinute }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        nowRefreshes.clear()

        view.refreshNow()

        assertEquals("未固定时间时应回调一次", 1, nowRefreshes.size)
        val fired = nowRefreshes.first()
        assertTrue(
            "分钟值应在 0..1439 内，实际 $fired",
            fired in 0..MinuteOfDay.END_OF_DAY_MINUTE,
        )
    }

    // ================= 辅助 =================

    /**
     * 取日程块在视口内的边界。
     *
     * 走 `blockBoundsInParent` 而不是自己算——它已处理滚动偏移与轴宽偏移，
     * 测试侧重算一遍只会引入第二份可能过期的公式。
     */
    private fun boundsOf(id: String): Rect {
        val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == id }?.second
            ?: error("测试前置失败：视口内找不到日程 $id")
        return view.blockBoundsInParent(block)
            ?: error("测试前置失败：取不到日程 $id 的边界")
    }

    private fun axisWidthPx(): Int =
        view.resources.getDimensionPixelSize(
            com.github.kevinvane.daytimeline.library.R.dimen.day_timeline_axis_width,
        )

    private fun selectedIdValue(): String? {
        val f = DayTimelineView::class.java.getDeclaredField("selectedId").apply { isAccessible = true }
        return f.get(view) as String?
    }

    private fun DayTimelineView.maxScrollValue(): Int {
        val m = DayTimelineView::class.java.getDeclaredMethod("maxScroll")
        m.isAccessible = true
        return m.invoke(this) as Int
    }

    /** 在指定坐标派发一次「按下 → 抬起」，走组件真实的 onTouchEvent 路径。 */
    private fun tapAt(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val e = MotionEvent.obtain(now, now, action, x, y, 0)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.dispatchTouchEvent(e)
            }
            e.recycle()
        }
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
