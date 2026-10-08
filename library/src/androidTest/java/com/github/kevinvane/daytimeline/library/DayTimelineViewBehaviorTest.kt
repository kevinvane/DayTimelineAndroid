package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.EventSanitizer
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机行为回归（此前发现的两类缺陷 + 滚动与绘制的基本正确性）。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewBehaviorTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

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
        view = DayTimelineView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    /**
     * 回归 1：**所有用于绘制文字的 Paint 都必须有非零 textSize**。
     *
     * 曾因 `blockText` 漏设 textSize 而使用 `Paint` 的默认值 12 **原始像素**，
     * 真机上块内文字小到几乎看不见（density=3 时应为 36px）。
     * 遍历全部 Paint 比逐个断言更防回归——下次新增 Paint 漏设也会被抓到。
     */
    @Test
    fun everyTextPaintHasSaneTextSize() {
        val paintsField = DayTimelineView::class.java.getDeclaredField("paints")
            .apply { isAccessible = true }
        val paints = paintsField.get(view)!!

        val textSized = mutableListOf<Pair<String, Float>>()
        paints.javaClass.declaredFields.forEach { f ->
            val value = f.get(paints)
            if (value is android.graphics.Paint) {
                val name = f.name
                // 找出「会绘制文字」的 Paint：名字含 Label 或 Text
                if (name.contains("label", true) || name.contains("text", true)) {
                    textSized += name to value.textSize
                }
            }
        }
        assertTrue("未找到任何文字 Paint，测试本身失效", textSized.isNotEmpty())
        textSized.forEach { (name, size) ->
            assertTrue("$name 的 textSize=$size 小于 8px，几乎不可见", size >= 8f)
        }
    }

    /**
     * 回归 2：**自身滚动模式必须真的能滚**。
     *
     * 曾出现 arbiter 判定出 `Intent.Scroll` 后直接 `return false`，
     * 完全没有实现拖拽滚动，结果只能看到一屏内容。
     */
    @Test
    fun selfScrollModeAllowsScrollingBeyondViewport() {
        val top = readScrollOffset()
        view.scrollToMinute(600, smooth = false)
        val after = readScrollOffset()

        assertTrue(
            "全天内容高度应大于视口，否则无从滚动（contentHeight=${readInt("contentHeight")}, height=$HEIGHT）",
            readInt("contentHeight") > HEIGHT,
        )
        assertTrue("滚动到 10:00 后偏移应大于 0，实际 $after", after > top)
        assertTrue("偏移应被钳制在合法范围内", after in 0..readInt("contentHeight"))
    }

    @Test
    fun scrollOffsetIsClampedToContent() {
        view.setScrollOffset(Int.MAX_VALUE)
        val max = readInt("contentHeight") - HEIGHT
        assertEquals(max.coerceAtLeast(0), readScrollOffset())

        view.setScrollOffset(Int.MIN_VALUE)
        assertEquals(0, readScrollOffset())
    }

    @Test
    fun scrollToMinuteMovesContentAndStaysInRange() {
        view.scrollToMinute(0, smooth = false)
        assertEquals(0, readScrollOffset())

        view.scrollToMinute(1440, smooth = false)
        val max = (readInt("contentHeight") - HEIGHT).coerceAtLeast(0)
        assertEquals(max, readScrollOffset())
    }

    /**
     * 外部滚动模式下组件自身不应再滚动（高度已等于全天内容，§8.6）。
     */
    @Test
    fun externalScrollModeDoesNotScrollItself() {
        view.setConfig(TimelineConfig(scrollMode = ScrollMode.EXTERNAL))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(readInt("contentHeight"), view.measuredHeight)
        assertEquals("外部滚动模式自身偏移应为 0", 0, readScrollOffset())
    }

    /** 绘制必须有内容，且不是整屏同色（说明网格线/色块都画出来了）。 */
    @Test
    fun drawsDistinctContentNotBlankCanvas() {
        view.submitEvents(
            listOf(
                Ev("a", 9 * 60, 10 * 60, "会议评审"),
                Ev("b", 9 * 60 + 15, 10 * 60, "并行任务"),
                Ev("c", 14 * 60, 15 * 60, "深度工作"),
            ),
            notifyIssues = false,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        val colors = HashSet<Int>()
        for (x in 0 until WIDTH step 3) {
            for (y in 0 until HEIGHT step 3) {
                colors += bmp.getPixel(x, y)
            }
        }
        assertTrue("绘制结果应包含多种颜色（网格线/背景/色块/文字），实际 ${colors.size} 种", colors.size >= 3)
    }

    /** 脏数据不得导致崩溃（Q4），且异常要上抛（§9.3）。 */
    @Test
    fun dirtyDataIsToleratedAndReported() {
        val issues = mutableListOf<com.github.kevinvane.daytimeline.library.core.DataIssue>()
        view.listener = object : com.github.kevinvane.daytimeline.library.api.TimelineListener {
            override fun onDataIssues(list: List<com.github.kevinvane.daytimeline.library.core.DataIssue>) {
                issues.addAll(list)
            }
        }
        view.submitEvents(
            listOf(
                Ev("bad", 600, 500), // 结束早于开始
                Ev("over", 1400, 2000), // 超过 24:00
                Ev("a", 9 * 60, 10 * 60, "正常"),
            ),
            notifyIssues = true,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp)) // 不应抛异常

        assertTrue("脏数据应产生数据异常事件，实际 ${issues.size} 条", issues.isNotEmpty())
    }

    /**
     * 回归 3：**平滑滚动后不能被弹回原处**。
     *
     * 曾出现 `fling()` / `startScroll()` 把参数填到 X 轴，而 `computeScroll()` 读的是
     * `currY` —— `currY` 恒为 0，于是惯性期间每帧把 `scrollOffset` 写回 0，
     * 表现为「拖得动，一松手就弹回顶部」。
     *
     * 这里用带动画的 [DayTimelineView.scrollToMinute] 走完整条
     * `startScroll → computeScroll` 链路，逐帧推进直到动画结束，
     * 断言偏移单调走到目标附近且不会中途回落到起点。
     */
    @Test
    fun smoothScrollDoesNotSnapBack() {
        val target = 14 * 60
        view.scrollToMinute(target, smooth = true)

        val start = readScrollOffset()
        var previous = start
        var frames = 0
        while (frames < 200 && isScrollerRunning()) {
            view.computeScroll()
            val now = readScrollOffset()
            // 动画必须朝目标单向推进，不得回落到更小的偏移
            assertTrue(
                "动画期间偏移回退了：$now < 上一帧 $previous（起点 $start，目标 $target）",
                now >= previous,
            )
            previous = now
            frames++
        }
        assertTrue("动画帧数为 0，说明 startScroll 根本没启动", frames > 0)

        val max = (readInt("contentHeight") - HEIGHT).coerceAtLeast(0)
        assertTrue("最终偏移应离开起点，实际 $previous == $start", previous != start)
        assertTrue("最终偏移应在合法范围内", previous in 0..max)
    }

    /** 惯性 fling 结束后偏移必须稳定在终点，不能每帧归零。 */
    @Test
    fun scrollOffsetStaysPutAfterGesture() {
        view.setScrollOffset(800)
        view.computeScroll()
        val afterFirst = readScrollOffset()
        // 连续多次调用 computeScroll 不应改变一个已经稳定的偏移
        repeat(3) { view.computeScroll() }
        assertEquals("未在动画中时 computeScroll 不应改动偏移", afterFirst, readScrollOffset())
    }

    /** 组件内部的 OverScroller 是否仍在跑动画。 */
    private fun isScrollerRunning(): Boolean {
        val f = DayTimelineView::class.java.getDeclaredField("scroller").apply { isAccessible = true }
        val s = f.get(view) as android.widget.OverScroller
        // isFinished 是只读查询，不会推进动画状态，因此可以安全调用
        return !s.isFinished
    }

    // ---- 工具 ----

    private fun readScrollOffset(): Int = view.getScrollOffset()

    private fun readInt(name: String): Int {
        val f = DayTimelineView::class.java.getDeclaredField("dimens").apply { isAccessible = true }
        val d = f.get(view)!!
        return (d.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(d) as Number).toInt()
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
