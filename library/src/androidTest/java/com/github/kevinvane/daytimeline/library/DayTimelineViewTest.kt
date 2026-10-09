package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 视图构造与配置生效的真机测试。
 *
 * 上一轮的崩溃就发生在构造期（`Dimens.resolve` 读资源时抛
 * `Resources$NotFoundException: type #0x4 is not valid`），
 * 本测试是它的直接回归防线：只要构造路径上还有任何资源类型不匹配，这里立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewTest {

    private companion object {
        const val TEST_WIDTH = 320
        const val TEST_HOUR_HEIGHT = 100
        const val TEST_AXIS_WIDTH = 40
        const val TEST_START_MINUTE = 540
        const val BAR_EVENT_ID = "accent-bar"
        const val BAR_MARGIN_START = 8
        const val BAR_MARGIN_VERTICAL = 2

        /** 不透明的正红：色条取业务色时唯一可能被误判的颜色只有抗锯齿过渡像素。 */
        val ACCENT = 0xFFFF0000.toInt()
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 在主线程构造 View。
     *
     * View 构造会创建 GestureDetector → Handler，而 Instrumentation 的测试线程没有
     * `Looper.prepare()`，直接 new 会抛
     * `RuntimeException: Can't create handler inside thread ...`。
     */
    private fun newView(): DayTimelineView {
        var view: DayTimelineView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view = DayTimelineView(context) }
        return view!!
    }

    /** 构造不得抛异常——这正是上一轮真机崩溃的场景。 */
    @Test
    fun constructsWithoutCrash() {
        assertNotNull(newView())
    }

    /** 默认配置下应能完成测量与绘制，且真的画出了东西。 */
    @Test
    fun defaultConfigMeasuresAndDraws() {
        val view = newView()
        measureAndLayout(view, 480)

        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertTrue("绘制后位图不应全透明，说明什么都没画出来", hasNonTransparentPixel(bitmap))
    }

    /** 代码配置应真正生效（FC-005「代码配置」侧）。 */
    @Test
    fun codeConfigIsApplied() {
        val view = newView()
        view.setConfig(TimelineConfig(hourHeight = 100))
        assertEquals(100, readDimensInt(view, "effectiveHourHeight"))
    }

    /**
     * 合并语义回归：`setConfig` 不得把「先前已生效、但本次没提到」的字段清掉。
     *
     * 这条守的是真机上「XML 配置好像没生效」的坑——整体替换配置会让
     * XML 里其它字段悄悄退回资源默认值，且没有任何报错。
     */
    @Test
    fun setConfigMergesInsteadOfReplacing() {
        val view = newView()
        view.setConfig(TimelineConfig(hourHeight = 100, axisWidth = 77))
        // 第二次只改一个字段
        view.setConfig(TimelineConfig(blockCorner = 9))

        assertEquals("本次未提到的 hourHeight 应保留", 100, readDimensInt(view, "effectiveHourHeight"))
        assertEquals("本次未提到的 axisWidth 应保留", 77, readDimensInt(view, "axisWidth"))
        assertEquals("本次指定的 blockCorner 应生效", 9, readDimensInt(view, "blockCorner"))
    }

    /** 触摸热区不得小于 48dp（Q10 / UF-001 / FI-015）。 */
    @Test
    fun touchHotAreaIsAtLeast48dp() {
        val view = newView()
        val fortyEightDp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics,
        ).toInt()

        assertTrue(
            "最小触摸目标应 ≥ 48dp，实际 ${readDimensInt(view, "minTouchTarget")}px",
            readDimensInt(view, "minTouchTarget") >= fortyEightDp,
        )
        assertTrue(
            "编辑手柄热区应 ≥ 48dp，实际 ${readDimensInt(view, "handleTouchSize")}px",
            readDimensInt(view, "handleTouchSize") >= fortyEightDp,
        )
    }

    /** 手柄视觉尺寸允许小于热区（§7.2：视觉 6dp / 热区 48dp）。 */
    @Test
    fun handleVisualIsSmallerThanHotArea() {
        val view = newView()
        assertTrue(
            "手柄视觉直径应小于热区",
            readDimensInt(view, "handleVisualSize") < readDimensInt(view, "handleTouchSize"),
        )
    }

    /** 外部滚动模式下高度应等于全天内容高度（§8.6 高度约定）。 */
    @Test
    fun externalScrollModeUsesFullContentHeight() {
        val view = newView()
        view.setConfig(TimelineConfig(scrollMode = ScrollMode.EXTERNAL))
        measureAndLayout(view, View.MeasureSpec.UNSPECIFIED)

        val expected = 24 * readDimensInt(view, "effectiveHourHeight") +
            readDimensInt(view, "topPadding") +
            readDimensInt(view, "bottomPadding")
        assertEquals("外部滚动模式高度应为全天内容高度", expected, view.measuredHeight)
    }

    /** 格高被设成极小值时应兜底而非崩溃（PRD E17）。 */
    @Test
    fun tinyHourHeightIsFloored() {
        val view = newView()
        view.setConfig(TimelineConfig(hourHeight = 1))
        assertTrue(
            "极小格高应被抬升到可显示的最小值",
            readDimensInt(view, "effectiveHourHeight") >= readDimensInt(view, "hourHeightMin"),
        )
        measureAndLayout(view, 480)
    }

    /**
     * 左侧色条的内缩配置必须走通「资源默认 → 代码覆盖 → 脏值兜底」整条链路。
     */
    @Test
    fun accentBarMarginConfigIsApplied() {
        val view = newView()

        assertTrue(
            "默认应带左内缩（色条不贴块左边缘），实际 ${readDimensInt(view, "blockAccentBarMarginStart")}px",
            readDimensInt(view, "blockAccentBarMarginStart") > 0,
        )
        assertTrue(
            "默认应带上下内缩（色条不贴满块高），实际 ${readDimensInt(view, "blockAccentBarMarginVertical")}px",
            readDimensInt(view, "blockAccentBarMarginVertical") > 0,
        )

        view.setConfig(TimelineConfig(blockAccentBarMarginStart = 13, blockAccentBarMarginVertical = 5))
        assertEquals(13, readDimensInt(view, "blockAccentBarMarginStart"))
        assertEquals(5, readDimensInt(view, "blockAccentBarMarginVertical"))

        // 脏值兜底：负内缩会让色条越到块外，必须按 0 处理（与 D4 同类的越界防线）
        view.setConfig(TimelineConfig(blockAccentBarMarginStart = -9, blockAccentBarMarginVertical = -1))
        assertEquals(0, readDimensInt(view, "blockAccentBarMarginStart"))
        assertEquals(0, readDimensInt(view, "blockAccentBarMarginVertical"))
    }

    /**
     * 色条的内缩必须真的落到像素上：整体右移、上下压矮。
     *
     * 这条**只能**在真机上验证：JVM 单测既加载不了资源也画不了 Canvas，
     * 「内缩量算错」「色条压到文字上」这类缺陷对单测与 lint 都完全隐形。
     * 因此这里比对业务色的像素包围盒，而不是断言某个内部字段。
     */
    @Test
    fun accentBarIsInsetFromBlockEdges() {
        val view = newView()
        view.setConfig(
            TimelineConfig(
                hourHeight = TEST_HOUR_HEIGHT,
                axisWidth = TEST_AXIS_WIDTH,
                topPadding = 0,
                bottomPadding = 0,
                endMargin = 0,
                blockPaddingHorizontal = 0,
                blockAccentBarMarginStart = BAR_MARGIN_START,
                blockAccentBarMarginVertical = BAR_MARGIN_VERTICAL,
                showNowIndicator = false,
                scrollMode = ScrollMode.EXTERNAL,
            ),
        )
        view.submitEvents(
            listOf(TintedEvent(BAR_EVENT_ID, TEST_START_MINUTE, TEST_START_MINUTE + 60, ACCENT)),
            notifyIssues = false,
        )
        measureAndLayout(view, View.MeasureSpec.UNSPECIFIED)

        val bitmap = Bitmap.createBitmap(TEST_WIDTH, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val box = boundsOfColor(bitmap, ACCENT)
        assertNotNull("没找到色条像素：日程块根本没画出来", box)
        val blockTop = TEST_START_MINUTE / 60 * TEST_HOUR_HEIGHT
        val barLeft = TEST_AXIS_WIDTH + BAR_MARGIN_START
        assertEdge("色条左边缘应右移到「轴宽 + 左内缩」", barLeft, box!![0])
        assertEdge("色条宽度不应被内缩改变", barLeft + barWidthOf(view) - 1, box[2])
        assertEdge("色条顶边应下移「上下内缩」", blockTop + BAR_MARGIN_VERTICAL, box[1])
        assertEdge(
            "色条底边应上移「上下内缩」",
            blockTop + TEST_HOUR_HEIGHT - BAR_MARGIN_VERTICAL - 1,
            box[3],
        )
    }

    /**
     * 边界断言留 1px 容差。
     *
     * 圆角矩形的**最外一圈**像素是抗锯齿过渡色，与「恰好等于强调色」的比较差一档；
     * 但内缩一旦算错，偏差是 dp 级（8dp ≈ 22px），1px 容差不会掩盖任何真实缺陷。
     */
    private fun assertEdge(label: String, expected: Int, actual: Int) {
        assertTrue("$label：期望 $expected，实际 $actual（容差 1px）", abs(expected - actual) <= 1)
    }

    private fun barWidthOf(view: DayTimelineView): Int = readDimensInt(view, "blockAccentBarWidth")

    /** 带业务色的日程：色条取业务色，与三态主题色无关，断言才稳定。 */
    private data class TintedEvent(
        override val id: String,
        private val from: Int,
        private val to: Int,
        override val color: Int?,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(from)
        override val end get() = MinuteOfDay.ofMinute(to)
    }

    /** 精确颜色像素的包围盒 [left, top, right, bottom]；没找到返回 null。 */
    private fun boundsOfColor(bitmap: Bitmap, color: Int): IntArray? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != color) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < left) null else intArrayOf(left, top, right, bottom)
    }

    // ---- 工具 ----

    private fun measureAndLayout(view: View, heightSpec: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            if (heightSpec == View.MeasureSpec.UNSPECIFIED) {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            } else {
                View.MeasureSpec.makeMeasureSpec(heightSpec, View.MeasureSpec.EXACTLY)
            },
        )
        view.layout(0, 0, 320, view.measuredHeight)
    }

    /** 反射读取 internal Dimens 的整型属性，仅测试用。 */
    private fun readDimensInt(view: DayTimelineView, name: String): Int {
        val dimensField = DayTimelineView::class.java.getDeclaredField("dimens")
            .apply { isAccessible = true }
        val dimens = dimensField.get(view)!!
        val cls = dimens.javaClass
        val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
        if (field != null) return (field.apply { isAccessible = true }.get(dimens) as Number).toInt()
        // effectiveHourHeight 是计算属性，只有 getter 没有字段
        val getter = cls.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
            .apply { isAccessible = true }
        return (getter.invoke(dimens) as Number).toInt()
    }

    private fun hasNonTransparentPixel(bitmap: Bitmap): Boolean {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) != 0) return true
            }
        }
        return false
    }
}
