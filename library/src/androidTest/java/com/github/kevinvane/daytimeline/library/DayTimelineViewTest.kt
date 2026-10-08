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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 视图构造与配置生效的真机测试。
 *
 * 上一轮的崩溃就发生在构造期（`Dimens.resolve` 读资源时抛
 * `Resources$NotFoundException: type #0x4 is not valid`），
 * 本测试是它的直接回归防线：只要构造路径上还有任何资源类型不匹配，这里立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 构造不得抛异常——这正是上一轮真机崩溃的场景。 */
    @Test
    fun constructsWithoutCrash() {
        assertNotNull(DayTimelineView(context))
    }

    /** 默认配置下应能完成测量与绘制，且真的画出了东西。 */
    @Test
    fun defaultConfigMeasuresAndDraws() {
        val view = DayTimelineView(context)
        measureAndLayout(view, 320, 480)

        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertTrue("绘制后位图不应全透明，说明什么都没画出来", hasNonTransparentPixel(bitmap))
    }

    /** 代码配置应真正生效（FC-005「代码配置」侧）。 */
    @Test
    fun codeConfigIsApplied() {
        val view = DayTimelineView(context)
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
        val view = DayTimelineView(context)
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
        val view = DayTimelineView(context)
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
        val view = DayTimelineView(context)
        assertTrue(
            "手柄视觉直径应小于热区",
            readDimensInt(view, "handleVisualSize") < readDimensInt(view, "handleTouchSize"),
        )
    }

    /** 外部滚动模式下高度应等于全天内容高度（§8.6 高度约定）。 */
    @Test
    fun externalScrollModeUsesFullContentHeight() {
        val view = DayTimelineView(context)
        view.setConfig(TimelineConfig(scrollMode = ScrollMode.EXTERNAL))
        measureAndLayout(view, 320, View.MeasureSpec.UNSPECIFIED)

        val expected = 24 * readDimensInt(view, "effectiveHourHeight") +
            readDimensInt(view, "topPadding") +
            readDimensInt(view, "bottomPadding")
        assertEquals("外部滚动模式高度应为全天内容高度", expected, view.measuredHeight)
    }

    /** 格高被设成极小值时应兜底而非崩溃（PRD E17）。 */
    @Test
    fun tinyHourHeightIsFloored() {
        val view = DayTimelineView(context)
        view.setConfig(TimelineConfig(hourHeight = 1))
        assertTrue(
            "极小格高应被抬升到可显示的最小值",
            readDimensInt(view, "effectiveHourHeight") >= readDimensInt(view, "hourHeightMin"),
        )
        measureAndLayout(view, 320, 480)
    }

    // ---- 工具 ----

    private fun measureAndLayout(view: View, widthSpec: Int, heightSpec: Int) {
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
        val target = dimens.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return (target.get(dimens) as Number).toInt()
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
