package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.BlockContext
import com.github.kevinvane.daytimeline.library.api.EventBlockPainter
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineColors
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import com.github.kevinvane.daytimeline.library.paint.TimelineTypeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        // View 构造会创建 GestureDetector → Handler，必须在主线程（Looper 已 prepare），
        // 否则抛 "Can't create handler inside thread ... that has not called Looper.prepare()"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
            // 本文件的滚动用例（自身模式能否滚出视口、平滑滚动不回弹、外部模式不自滚）
            // 全部以「初始偏移为 0」为前提。FI-012 实现后会自动定位到当前时间，
            // 那些断言会随运行时刻变化而失败，因此在此显式关掉首次定位——
            // 首次定位本身由本文件末尾的 FI-012 专区用专用 view 覆盖。
            view.setConfig(TimelineConfig(autoLocateOnFirstShow = false))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHalfDay, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, viewportHalfDay)
        }
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
            // Paints 声明在 internal 嵌套类中，字段非 public，必须逐个打开访问权限
            f.isAccessible = true
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
     * 回归 3（M0-3 字体规格）：三支文字笔的字族 / 字重必须等于设计定稿。
     *
     * 依据 `docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`：
     * 轴标签 `sans-serif` / Regular，当前时间线时间 `sans-serif-medium` / Medium；
     * 日程块标题**默认 Regular**——PRD §7.4 只有选中态才「轻微加粗」到 Medium，
     * 设计回复 #3 把默认写成 Medium 与该条冲突，已按 §7.4 语义取默认 Regular。
     */
    @Test
    fun textPaintsUseFinalizedTypefaces() {
        val paints = DayTimelineView::class.java.getDeclaredField("paints")
            .apply { isAccessible = true }
            .get(view)!!

        fun typefaceOf(fieldName: String): Typeface {
            val field = paints.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            return (field.get(paints) as Paint).typeface
        }

        assertSame(
            "轴标签应为 sans-serif / Regular（M0-3 #1）",
            TimelineTypeface.regular, typefaceOf("axisLabel"),
        )
        assertSame(
            "当前时间线时间应为 sans-serif-medium / Medium（M0-3 #2）",
            TimelineTypeface.medium, typefaceOf("nowLabel"),
        )
        assertSame(
            "块标题默认应为 Regular——选中态才加粗（§7.4 / M0-3 #6）",
            TimelineTypeface.regular, typefaceOf("blockText"),
        )
    }

    /**
     * 块内文字放不下时必须被标记为「放不下文字」，否则副标题会溢出块外。
     *
     * 真机截图发现过两次同类缺陷：
     * - `19:00–19:02`（2 分钟）只有兜底高度，第二行「提醒」画到块外；
     * - `16:40–17:00`（20 分钟）原始高度 20dp+ 高于兜底高度，但**放不下两行**，
     *   副标题「并行任务 C」同样画到了块背景外。
     *
     * 根因是判据写成 `blockH < blockMinHeight`——`blockH` 已被 `Geometry.blockHeight`
     * 抬到兜底高度以上，该条件**恒为 false**，文档承诺的「块高不足以显示两行文字时
     * 只读屏不显示文字」从未触发。现按文字 Paint 实测的所需高度判定。
     *
     * 回滚验证：把判据改回 `blockH < dimens.blockMinHeight`，`ping` 与 `c20` 两栏
     * 立刻由 true 变 false，本用例转红。
     */
    @Test
    fun shortEventIsFlaggedTooShortForTextInsteadOfOverflowingTheBlock() {
        val recorder = RecordingBlockPainter()
        val v = createView {
            setConfig(
                TimelineConfig(
                    hourHeight = hourHeight,
                    topPadding = 0,
                    bottomPadding = 0,
                    showTimeSubtitle = true,
                    // 关掉首次定位，否则视口停到 12:00，下面这些清晨的日程根本画不到。
                    autoLocateOnFirstShow = false,
                ),
            )
            eventBlockPainter = recorder
            // 视口只有 5 小时（0–300 分钟），事件必须落在其中才会被绘制。
            // hourHeight 取真实 56dp：2 分钟 ≈ 1.9dp（被兜底到 20dp）；20 分钟 ≈ 18.7dp
            // （高于兜底高度，但放不下两行）；40 分钟 ≈ 37.3dp（够两行）；60 分钟高度充足。
            // 这些**比值**与密度无关，见 [hourHeight] 的 KDoc。
            submitEvents(
                listOf(
                    Ev("ping", 10, 12, "提醒"),
                    Ev("c20", 140, 160, "并行任务 C"),
                    Ev("b40", 200, 240, "并行任务 B"),
                    Ev("long", 60, 120, "长日程"),
                ),
            )
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            v.draw(Canvas(Bitmap.createBitmap(WIDTH, viewportFiveHours, Bitmap.Config.ARGB_8888)))
        }

        assertEquals(
            "2 分钟日程被抬到兜底高度绘制，放不下两行，必须标记放不下文字" +
                "（否则文字溢出块外），实际记录：${recorder.tooShort}",
            true,
            recorder.tooShort["ping"],
        )
        assertEquals(
            "20 分钟日程原始高度高于兜底高度，但仍放不下两行，必须标记放不下文字" +
                "（真机实测副标题溢出块外），实际记录：${recorder.tooShort}",
            true,
            recorder.tooShort["c20"],
        )
        assertEquals(
            "40 分钟日程够放两行，不应被标记为极短，实际记录：${recorder.tooShort}",
            false,
            recorder.tooShort["b40"],
        )
        assertEquals(
            "60 分钟日程高度充足，不应被标记为极短，实际记录：${recorder.tooShort}",
            false,
            recorder.tooShort["long"],
        )
    }

    /** 记录每个块拿到的 `tooShortForText`（`BlockContext` 是复用对象，必须当场拷值，AD-14）。 */
    private class RecordingBlockPainter : EventBlockPainter {
        val tooShort = HashMap<String, Boolean>()
        override fun paint(
            canvas: Canvas,
            context: BlockContext,
            colors: TimelineColors,
            defaultPaints: EventBlockPainter.Paints,
        ) {
            tooShort[context.event.id] = context.tooShortForText
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
            "全天内容高度应大于视口，否则无从滚动（contentHeight=${readInt("contentHeight")}, 视口=$viewportHalfDay）",
            readInt("contentHeight") > viewportHalfDay,
        )
        assertTrue("滚动到 10:00 后偏移应大于 0，实际 $after", after > top)
        assertTrue("偏移应被钳制在合法范围内", after in 0..readInt("contentHeight"))
    }

    @Test
    fun scrollOffsetIsClampedToContent() {
        view.setScrollOffset(Int.MAX_VALUE)
        val max = readInt("contentHeight") - viewportHalfDay
        assertEquals(max.coerceAtLeast(0), readScrollOffset())

        view.setScrollOffset(Int.MIN_VALUE)
        assertEquals(0, readScrollOffset())
    }

    @Test
    fun scrollToMinuteMovesContentAndStaysInRange() {
        view.scrollToMinute(0, smooth = false)
        assertEquals(0, readScrollOffset())

        view.scrollToMinute(1440, smooth = false)
        val max = (readInt("contentHeight") - viewportHalfDay).coerceAtLeast(0)
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
            View.MeasureSpec.makeMeasureSpec(viewportHalfDay, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, viewportHalfDay)

        val bmp = Bitmap.createBitmap(WIDTH, viewportHalfDay, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        val colors = HashSet<Int>()
        for (x in 0 until WIDTH step 3) {
            for (y in 0 until viewportHalfDay step 3) {
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
            View.MeasureSpec.makeMeasureSpec(viewportHalfDay, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, viewportHalfDay)
        val bmp = Bitmap.createBitmap(WIDTH, viewportHalfDay, Bitmap.Config.ARGB_8888)
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
        val deadline = System.currentTimeMillis() + 10_000
        while (frames < 200 && isScrollerRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(16) // OverScroller 按墙钟推进，不让帧间隔开会一直读到同一帧
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
        assertFalse("动画应在帧数上限内跑完", isScrollerRunning())

        val max = (readInt("contentHeight") - viewportHalfDay).coerceAtLeast(0)
        assertTrue("最终偏移应离开起点，实际 $previous == $start", previous != start)
        assertTrue("最终偏移应在合法范围内", previous in 0..max)
    }

    /** 惯性 fling 结束后偏移必须稳定在终点，不能每帧归零。 */
    @Test
    fun scrollOffsetStaysPutAfterGesture() {
        // 取合法范围的中点而非写死像素值——写死的 800 在 mdpi 下会越界被钳到 max。
        view.setScrollOffset((readInt("contentHeight") - viewportHalfDay).coerceAtLeast(0) / 2)
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

    // ================= FI-012 首次显示自动定位 =================

    /**
     * 造一个**尺寸与时间完全可控**的视图。
     *
     * 默认资源的 dp→px 换算依赖设备密度，直接断言像素值会得到「一台设备通过、
     * 另一台失败」的脆弱测试。这里把每小时格高与上下留白都钉成 px 整数，
     * 视口高度也由测试指定，于是断言结果与设备无关。
     *
     * [configure] 在**首次测量之前**执行——首次定位只发生在第一次布局，
     * 配置必须赶在那之前（这本身也是接入方应有的顺序）。
     */
    private fun createView(
        viewportHeight: Int = viewportFiveHours,
        configure: DayTimelineView.() -> Unit = {},
    ): DayTimelineView {
        var created: DayTimelineView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val v = DayTimelineView(context)
            v.setConfig(
                TimelineConfig(
                    hourHeight = hourHeight,
                    topPadding = 0,
                    bottomPadding = 0,
                ),
            )
            v.setNowMinute(NOW_MINUTE)
            v.configure()
            v.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, WIDTH, viewportHeight)
            created = v
        }
        return checkNotNull(created) { "runOnMainSync 未执行，测试本身失效" }
    }

    /**
     * FI-012 / §8.5：首次显示自动定位，当前时间线落在视口顶部往下三分之一屏处。
     *
     * 断言写成不变量而不是「照抄公式」：定位后 `当前时间线的内容坐标 - 偏移`
     * 必须正好等于 `视口高度 / 3`。
     */
    @Test
    fun firstShowAutoLocatesToOneThirdViewport() {
        val v = createView()
        val nowContentY = NOW_MINUTE / 60f * hourHeight
        assertEquals(viewportFiveHours / 3, nowContentY.toInt() - v.getScrollOffset())
    }

    /**
     * 自动定位与日程数据**无关**（FR-014：空数据时时间轴仍完整显示）。
     *
     * 接入方常在 onCreate 里先 setView / measure，数据是异步拉回来的。
     * 若定位依赖数据，这种「先显示后填数」的顺序会让首屏停在 00:00。
     */
    @Test
    fun firstShowAutoLocatesEvenWithoutEvents() {
        val v = createView(configure = {})
        val nowContentY = NOW_MINUTE / 60f * hourHeight
        assertEquals(viewportFiveHours / 3, nowContentY.toInt() - v.getScrollOffset())
    }

    /** FI-012 验收标准「可配置关闭」：关掉后必须停在 00:00 顶部。 */
    @Test
    fun autoLocateCanBeDisabled() {
        val v = createView {
            setConfig(
                TimelineConfig(
                    hourHeight = hourHeight,
                    topPadding = 0,
                    bottomPadding = 0,
                    autoLocateOnFirstShow = false,
                ),
            )
        }
        assertEquals("关闭自动定位后应停在顶部", 0, v.getScrollOffset())
    }

    /**
     * **业务方显式指定的位置优先于自动定位**（§11.2）。
     *
     * 业务方常在 `onCreate` 里自行恢复上次的位置；若自动定位随后覆盖它，
     * 表现为「刚恢复完就被弹走」，且没有任何报错。
     */
    @Test
    fun explicitScrollPositionWinsOverAutoLocate() {
        // 取合法范围的中点，而不是写死某个像素值——写死的值在低密度设备上会越界被钳到 max。
        val max = (readInt("contentHeight") - viewportFiveHours).coerceAtLeast(0)
        val target = max / 2
        val v = createView { setScrollOffset(target) }
        assertEquals(target, v.getScrollOffset())
    }

    @Test
    fun explicitScrollToMinuteWinsOverAutoLocate() {
        val v = createView { scrollToMinute(300, smooth = false) }
        // 05:00 的内容坐标是 5 × hourHeight；自动定位的结果（12 点处）必然大于它。
        // 原断言写死 1000px，那是「hourHeight=200」时代的数字，换密度后不再成立。
        assertTrue(
            "显式跳转的位置被自动定位覆盖了，实际 ${v.getScrollOffset()}",
            v.getScrollOffset() <= 5 * hourHeight,
        )
    }

    /**
     * §8.5「非今天 | 定位到 00:00（顶部）」。
     *
     * 非今天没有「当前时间线」可言，组件不假装有。
     */
    @Test
    fun nonTodayStaysAtTop() {
        val v = createView {
            setViewDate(System.currentTimeMillis() - 3 * DAY_MILLIS)
        }
        assertEquals("查看非今天时应停在 00:00 顶部", 0, v.getScrollOffset())
    }

    /**
     * §8.6：外部滚动模式下组件高度等于全天内容高度，可滚动上限为 0，
     * 首次定位因此**不产生任何位移**——滚动归外层容器负责（FI-002）。
     */
    @Test
    fun externalScrollModeLeavesScrollingToOuterContainer() {
        var created: DayTimelineView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val v = DayTimelineView(context)
            v.setConfig(
                TimelineConfig(
                    hourHeight = hourHeight,
                    topPadding = 0,
                    bottomPadding = 0,
                    scrollMode = ScrollMode.EXTERNAL,
                ),
            )
            v.setNowMinute(NOW_MINUTE)
            // 高度按 §8.6 约定给 UNSPECIFIED，组件自己按全天内容高度测量
            v.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            v.layout(0, 0, WIDTH, v.measuredHeight)
            created = v
        }
        val v = checkNotNull(created) { "runOnMainSync 未执行，测试本身失效" }
        assertEquals("外部滚动模式高度应等于全天内容高度", 24 * hourHeight, v.height)
        assertEquals("外部滚动模式首次定位不应产生位移", 0, v.getScrollOffset())
    }

    /**
     * 「首次显示」一辈子只发生一次：后续重新测量（旋转、容器高度变化）
     * 不得把用户已经滚到的位置拽回当前时间。
     */
    @Test
    fun autoLocateRunsOnlyOnce() {
        val v = createView()
        v.scrollToMinute(300, smooth = false)
        val afterUserJump = v.getScrollOffset()

        v.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportFiveHours, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, WIDTH, viewportFiveHours)
        assertEquals("重新布局不应重新定位", afterUserJump, v.getScrollOffset())
    }

    /**
     * §8.5「状态恢复：旋转屏幕后恢复原滚动位置」（D12）——**恢复优先于首次定位**。
     *
     * 旋转后组件是全新实例，首次定位照理会触发；若不把「已有结论」一并恢复，
     * 用户保存的滚动位置就会被当前时间覆盖。
     */
    @Test
    fun restoredScrollPositionIsNotOverwrittenByAutoLocate() {
        val before = createView { scrollToMinute(600, smooth = false) }
        before.id = STATE_VIEW_ID
        val savedOffset = before.getScrollOffset()
        assertTrue("前置条件：跳转后的偏移应非 0，实际 $savedOffset", savedOffset > 0)

        val container = SparseArray<Parcelable>()
        before.saveHierarchyState(container)

        var rotated: DayTimelineView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val v = DayTimelineView(context)
            v.id = STATE_VIEW_ID
            v.setConfig(
                TimelineConfig(hourHeight = hourHeight, topPadding = 0, bottomPadding = 0),
            )
            v.setNowMinute(NOW_MINUTE)
            v.restoreHierarchyState(container)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportFiveHours, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, WIDTH, viewportFiveHours)
            rotated = v
        }
        val v = checkNotNull(rotated) { "runOnMainSync 未执行，测试本身失效" }
        assertEquals("恢复出的位置应保留，不得被自动定位覆盖", savedOffset, v.getScrollOffset())
    }

    // ---- 工具 ----

    private fun readScrollOffset(): Int = view.getScrollOffset()

    private fun readInt(name: String): Int {
        val f = DayTimelineView::class.java.getDeclaredField("dimens").apply { isAccessible = true }
        val d = f.get(view)!!
        val cls = d.javaClass
        val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
        if (field != null) return (field.apply { isAccessible = true }.get(d) as Number).toInt()
        // contentHeight / effectiveHourHeight 是计算属性，只有 getter 没有字段
        val getter = cls.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
            .apply { isAccessible = true }
        return (getter.invoke(d) as Number).toInt()
    }

    /**
     * 每小时格高（px）——**读真实资源推导，不写死**。
     *
     * ## 为什么不能写死
     *
     * 此前本类把 `hourHeight = 200px`、`viewportHalfDay = 1920px`、`viewportFiveHours = 800px`
     * 全部写死，而产品的 `hourHeight` 来自 `56dp` 资源、`textSize` 来自 `12sp` 资源
     * ——**两者都随设备密度缩放**。于是这些常量隐含了一个从未写出的前提：
     * **「设备是 420dpi 的高密度手机」**。
     *
     * 在 mdpi(160dpi) 真机上该前提不成立，实测后果（2026-10-16）：
     * - `contentHeight = 24 × 56 × 160/160 = 1360px` **小于写死的 1920px 视口**
     *   → 整天比视口矮 → `selfScrollModeAllowsScrollingBeyondViewport` 与
     *   `smoothScrollDoesNotSnapBack` 两条转红；
     * - 块高写死 200px 而字号随密度缩放，160dpi 下 12sp = 12px、两行约 28.8px
     *   **小于** 20 分钟块的 66px → 放得下 → `shortEventIsFlaggedTooShort...` 转红。
     *
     * **测试红了，但产品行为是对的。** 改成读真实资源后，下面三者的**比值关系**
     * 与密度无关，任何 dpi 都成立：
     * - 20 分钟块高 `56/3 = 18.67dp`，去上下 padding 后 10.67dp < 两行 12sp 文字约 28.8dp → **放不下**
     * - 40 分钟块高 37.33dp，去 padding 后 29.33dp ≥ 28.8dp → **放得下**
     */
    private val hourHeight: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.day_timeline_hour_height)
    }

    /**
     * 主用例的视口高度（px）——**半日**。
     *
     * 取 `12 × hourHeight` 而非写死：全天内容恒为 `24 × hourHeight`，
     * 于是 `contentHeight > 视口` 这个**滚动前提在任何密度下都成立**。
     */
    private val viewportHalfDay: Int get() = hourHeight * 12

    /**
     * 覆盖 0–300 分钟的视口（px）——供需要画到 05:00 的用例使用。
     *
     * 取 `5 × hourHeight` 覆盖事件分钟区间上界（原写死 800px 恰好也是这个意图，
     * 只是换成了与密度绑死的数字）。
     */
    private val viewportFiveHours: Int get() = hourHeight * 5

    private companion object {
        const val WIDTH = 1080

        /** 指定「当前时间」为 12:00，使定位结果完全确定（FD-006）。 */
        const val NOW_MINUTE = 720
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val STATE_VIEW_ID = 0x7f01
    }
}
