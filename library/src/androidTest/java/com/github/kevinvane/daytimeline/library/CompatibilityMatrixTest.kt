package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.GridContext
import com.github.kevinvane.daytimeline.library.api.GridPainter
import com.github.kevinvane.daytimeline.library.api.TimelineColors
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import com.github.kevinvane.daytimeline.library.paint.DefaultGridPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12.2 兼容性矩阵里**此前零自动化覆盖**的三项：RTL、字体放大 200%、横竖屏旋转。
 *
 * ## 为什么这三条值得单独立文件
 *
 * 三者都是 PRD §12.2 / D13 的**硬性要求**，但在写本文件之前，
 * 全仓库**没有任何一条测试**设置过 `layoutDirection`、`fontScale` 或横屏尺寸。
 * 「支持」当时只由一行代码（`DayTimelineView.resolveAxisEnd`）和几段 KDoc 自证——
 * 这正是本仓库反复出现的第五类缺陷形态：**文档里承诺，没有任何东西守着**。
 *
 * ## 观测方式：不反射私有方法，用对外扩展点
 *
 * 三条的主要观测口是 [GridPainter]——它是 PRD §10.2 规定的**第二层定制入口**，
 * 每帧都会被调用一次，而它拿到的 [GridContext] 里已经带着
 * 布局方向与字体缩放解析后的结果（`axisAreaEnd` / `labelStep`）。
 * 业务方拿得到的东西，测试就拿得到；**不去反射 `resolveAxisEnd`**——
 * 私有方法一改名字测试就红，而那次红对缺陷毫无诊断价值。
 */
@RunWith(AndroidJUnit4::class)
class CompatibilityMatrixTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    // ---------- 数据与夹具 ----------

    private data class Ev(
        override val id: String,
        private val s: Int,
        private val e: Int,
        override val content: CharSequence? = null,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    /**
     * 记录每次绘制收到的 [GridContext]，随后照常委托给默认绘制方。
     *
     * **必须委托**：不画网格的话，字体缩放那条就只剩结构断言而没有任何像素可查；
     * 而且「业务方换掉 GridPainter 后组件是否仍正常绘制」本身就是对外承诺的一部分。
     *
     * 按 [GridContext] 的 KDoc，这里**不能**把 context 存成字段长期持有——
     * 组件每帧复用同一个对象。因此每次把需要的几个 int 复制出来。
     */
    private class RecordingGridPainter : GridPainter {
        var axisAreaEnd = -1
        var contentStart = -1
        var contentEnd = -1
        var labelStep = -1
        var background = 0
        var frames = 0

        override fun paint(
            canvas: Canvas,
            context: GridContext,
            colors: TimelineColors,
            defaultPaints: GridPainter.Paints,
        ) {
            axisAreaEnd = context.axisAreaEnd
            contentStart = context.contentStart
            contentEnd = context.contentEnd
            labelStep = context.labelStep
            background = colors.background
            frames++
            DefaultGridPainter.paint(canvas, context, colors, defaultPaints)
        }
    }

    /** View 的构造必须发生在主线程（会建 Handler / GestureDetector）。 */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun newView(ctx: Context = context): DayTimelineView = DayTimelineView(ctx).apply {
        setConfig(TimelineConfig(autoLocateOnFirstShow = false, showNowIndicator = false))
        setNowMinute(FIXED_NOW)
    }

    private fun measureAndLayout(view: DayTimelineView, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun draw(view: DayTimelineView, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bitmap
    }

    private fun dimenPx(name: Int): Int = context.resources.getDimensionPixelSize(name)

    /** 分钟 → 视口 y（内容坐标减去当前滚动偏移）。 */
    private fun viewportYOf(minute: Int, scrollOffset: Int): Float {
        val hourHeight = dimenPx(R.dimen.day_timeline_hour_height)
        val topPadding = dimenPx(R.dimen.day_timeline_top_padding)
        return (topPadding + (minute / 60f * hourHeight).toInt() - scrollOffset + hourHeight / 4f).toFloat()
    }

    /** `viewDate` 是私有字段，只读读取（与 `DayTimelineViewSavedStateTest` 同一手法）。 */
    private fun readViewDate(target: DayTimelineView): Long =
        DayTimelineView::class.java.getDeclaredField("viewDate")
            .apply { isAccessible = true }
            .get(target) as Long

    // ---------- 1. RTL：时间轴镜像到右侧 ----------


    /**
     * PRD §12.2「阿拉伯语、希伯来语等从右到左的语言正确镜像」（D13 / AD-10）。
     *
     * 断言三件事，缺一不可：
     *
     * 1. **绘制层**：RTL 下 `axisAreaEnd` 必须为 0，即轴区右移到屏幕最右侧；
     *    内容区从屏幕最左端开始。镜像没做的话这里会取到轴宽。
     * 2. **命中层**：RTL 下屏幕最左端属于内容区，`eventAt` 应当命中日程；
     *    LTR 下最左端是轴区，必须不命中。
     *    **只看绘制层是不够的**——`resolveAxisEnd` 同时被 `onDraw`（:461）与
     *    `eventAt`（:857）使用，「绘制对了、命中没对」是「看起来正常、点起来错位」的典型缺陷。
     *
     * ## 回滚验证
     *
     * 把 `resolveAxisEnd` 改回「无条件返回轴宽」（去掉 RTL 分支）后，
     * 绘制层断言转红（期望 `axisAreaEnd` 0，实得 196 = 56dp × density 3.5）。
     *
     * ## 本用例第一版就红，且红的不是组件
     *
     * 首版加进 androidTest 清单后，这条直接失败：「期望 RTL 实得 LTR」。
     * 探针分清了责任——**连原生 `View` 在同一环境里读回来也是 LTR**：
     *
     * | 写法 | `getLayoutDirection()` |
     * |---|:--:|
     * | 游离 View + 属性赋值 | 0（LTR） |
     * | 游离 View + `setLayoutDirection(1)` | 0（LTR） |
     * | 挂进 ViewGroup 后再设 | 0（LTR） |
     *
     * 原因是清单里少了一行 `android:supportsRtl="true"`：
     * **不声明它，Android 框架强制 LTR，`setLayoutDirection(RTL)` 是空操作**。
     * demo 应用声明了这一项，**接入方也必须声明**——组件库无法替接入方写进清单。
     * 补上之后探针四行全部返回 1，用例随即转绿。
     *
     * 也就是说：AD-10 的镜像实现一直是对的，但在此之前**没有任何一处让它真正生效过**——
     * 组件库自己的测试 APK 缺这一行，恰好也说明了为什么这条缺陷能潜伏这么久。
     */
    @Test
    fun rtlMirrorsTheAxisToTheRightSide() {
        val recorder = RecordingGridPainter()
        var rtlHit: String? = null
        var rtlRightMiss: String? = "not-run"
        var ltrLeftMiss: String? = "not-run"
        var rawAfterSet = -1
        var rawBeforeDraw = -1

        onMain {
            val view = newView()
            view.gridPainter = recorder
            view.submitEvents(listOf(Ev("e", 540, 600, content = "会议")))
            measureAndLayout(view, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            rawAfterSet = view.layoutDirection
            rawBeforeDraw = view.layoutDirection
            draw(view, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

            val y = viewportYOf(540, view.getScrollOffset())
            rtlHit = view.eventAt(2f, y)?.id
            rtlRightMiss = view.eventAt((view.width - 2).toFloat(), y)?.id

            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
            ltrLeftMiss = view.eventAt(2f, y)?.id
        }

        assertEquals(
            "前置条件：设置 RTL 后视图自身应报告 RTL（诊断：设完=$rawAfterSet，画之前=$rawBeforeDraw）",
            View.LAYOUT_DIRECTION_RTL,
            rawBeforeDraw,
        )
        assertTrue(
            "轴宽应为正数，否则 LTR 对照断言失去意义（读到 ${dimenPx(R.dimen.day_timeline_axis_width)} px）",
            dimenPx(R.dimen.day_timeline_axis_width) > 0,
        )
        assertEquals(
            "RTL 下时间轴应镜像到右侧，轴区右边界为 0（AD-10）",
            0,
            recorder.axisAreaEnd,
        )
        assertEquals("RTL 下内容区应从屏幕最左端开始", 0, recorder.contentStart)
        assertEquals("命中层同样要镜像：RTL 下屏幕最左端属于内容区，应命中日程", "e", rtlHit)
        assertNull("RTL 下屏幕最右端是轴区，不应命中日程", rtlRightMiss)
        assertNull("LTR 下屏幕最左端是轴区，不应命中日程", ltrLeftMiss)
    }

    // ---------- 2. 字体放大 200% ----------

    /**
     * PRD §12.2「系统放大到 200% 时文字不溢出、不重叠」（E14 / §7.4）。
     *
     * 断言两条，一条结构、一条像素：
     *
     * 1. **标签降密度**：字体 200% 时 `labelStep` 从 1 变成 3——
     *    这正是「轴标签不重叠」的实现机制（刻度线始终完整，只降标签密度）。
     * 2. **块内文字不溢出**：把标题写得足够长，按 200% 渲染后逐列扫描，
     *    **块右边界之外不得有任何墨迹**。默认绘制方用 `measureText` + 省略号截断，
     *    这条断言守的就是那个截断。
     *
     * 之所以关掉当前时间线（[TimelineConfig.showNowIndicator]）：那条红线
     * **本来就应该**横贯整个宽度，它落在块右边界之外属于正确绘制，
     * 与「文字溢出」混在一起就无法分辨了。关掉之后，边界之外只剩网格线与块内文字。
     *
     * ## 为什么用 `createConfigurationContext` 而不是改系统设置
     *
     * 改系统字体大小会影响整台设备上的其他进程与 CI 环境；
     * 而 `fontScaleOf()` 读的是 `context.resources.configuration.fontScale`，
     * 因此**构造一个 fontScale=2.0 的 Context 即可**——无需权限、无需真机设置、不影响其他用例。
     *
     * ## 回滚验证
     *
     * - 把 `LABEL_DENSITY_HIGH_SCALE` 调到 2.5f（200% 不再触发降密度）
     *   → 断言 1 转红（期望 `labelStep` 3，实得 2）。
     * - 去掉 `ellipsize(...)` 直接 `drawText` → 断言 2 转红，
     *   长标题越过内容区右边界（实测越界列自 1025 起，即 `contentEnd` 之后整条右边距）。
     *
     * ## 本用例第一版就红，红的是我自己的基准
     *
     * 首版用「像素 == 白色」当作「无墨」基准，结果右边距 56 列全被判成有墨。
     * 组件背景是设计定稿的浅色（`#F1F4F7` 一类），**整块画布都不等于白色**。
     * 改为以 [TimelineColors.background] 为基准后才对——
     * 基准必须取自组件自己刚画上去的那个底色，而不是凭「画布默认是白」的直觉。
     */
    @Test
    fun fontScale200KeepsLabelsFromCollidingAndTextFromOverflowing() {
        val normal = RecordingGridPainter()
        val large = RecordingGridPainter()
        val longTitle = "这是一个刻意写得很长很长的日程标题，用来验证字体放大之后文字会被截断而不是溢出到相邻的块上面"
        var overflowInk: List<Int> = emptyList()

        onMain {
            val scaled = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { fontScale = FONT_SCALE_200 },
            )

            val normalView = newView()
            normalView.gridPainter = normal
            normalView.submitEvents(listOf(Ev("e", 540, 600, content = longTitle)))
            measureAndLayout(normalView, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
            draw(normalView, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

            val largeView = newView(scaled)
            largeView.gridPainter = large
            largeView.submitEvents(listOf(Ev("e", 540, 600, content = longTitle)))
            measureAndLayout(largeView, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
            val bitmap = draw(largeView, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
            overflowInk = inkedColumnsOutsideRightEdge(bitmap, large.background, large.contentEnd)
        }

        assertEquals("字体 100% 时轴标签应每小时都显示", 1, normal.labelStep)
        assertEquals(
            "字体放大到 200% 时轴标签应自动降密度（PRD E14），否则相邻标签会互相重叠",
            3,
            large.labelStep,
        )
        assertTrue(
            "200% 字体下块内文字越过了内容区右边界（越界列：$overflowInk）——" +
                "§7.4 要求超出以省略号截断，窄列下不截断会把文字压到相邻块上",
            overflowInk.isEmpty(),
        )
    }

    /**
     * 找出「内容区右边界之外」仍与背景色不同的列。
     *
     * 两个容易踩的坑，都是本用例第一版踩过的：
     *
     * 1. **不能拿白色当「无墨」基准**——组件背景是设计定稿的浅色（`#F1F4F7` 一类），
     *    整块画布都不等于白色，用白色比对会把**每一列**都判成有墨。
     *    基准必须取自 [TimelineColors.background]，即组件自己刚画上去的那个底色。
     * 2. **边界要多让 1px**——块背景是圆角矩形，右边缘的描边与抗锯齿会落在
     *    `contentEnd` 那一列上，那是**正确**绘制，不是溢出。
     */
    private fun inkedColumnsOutsideRightEdge(bitmap: Bitmap, background: Int, contentEnd: Int): List<Int> {
        assertTrue("内容区右边界应落在画布内：$contentEnd", contentEnd in 1 until bitmap.width)
        val inked = ArrayList<Int>()
        for (x in contentEnd + 1 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) != background) {
                    inked += x
                    break
                }
            }
        }
        return inked
    }

    // ---------- 3. 横竖屏旋转 ----------

    /**
     * PRD §12.2「竖屏、横屏均正常；旋转后状态保持」（D12 / Q9 / E9 / E12）。
     *
     * 与既有 `DayTimelineViewSavedStateTest` 的分工：那条验的是**同尺寸**下
     * `scrollOffset` / `viewDate` / `selectedId` 的往返；这里验的是**尺寸变了之后**：
     *
     * - 旋转后滚动位置与查看日期仍然保持；
     * - 旋转后**按新宽度重新分栏**，且命中测试与新几何一致——
     *   也就是「横屏下点日程点得准」，而不是沿用竖屏的坐标。
     *
     * ## 为什么用「新建视图 + 恢复状态」模拟旋转
     *
     * Android 的旋转等价于「保存层级状态 → 用新的 Configuration 重建 Activity → 恢复」。
     * 直接改 `view.rotation` 不会触发重新测量与分栏，测不到真实路径；
     * 而真的去旋转 Activity 会打断测试的执行流。
     *
     * ## 回滚验证
     *
     * 把 `onSizeChanged` 里的 `relayout()` 去掉 → 「横屏下应能命中日程」转红。
     *
     * ## 本用例第一版就红，红的是我漏了一步
     *
     * 首版旋转后 `eventAt` 恒为 null。原因是新建的视图**没有重新提交日程**——
     * `SavedState` 只保存滚动位置 / 查看日期 / 选中态，**不保存日程数据**（那属于业务方，
     * 见 §11.1）。真实旋转也是 Activity 重建后由业务方重新 `submitEvents`，
     * 用例照实补上这一步才测到真实路径。
     */
    @Test
    fun rotationPreservesStateAndRelaysOutForTheNewWidth() {
        val threeDaysAgo = System.currentTimeMillis() - 3 * DAY_MILLIS
        val container = SparseArray<Parcelable>()
        var landscapeScroll = -1
        var landscapeDate = -1L
        var landscapeHit: String? = "not-run"
        var landscapeFrames = 0
        var landscapeContentWidth = -1

        onMain {
            val source = newView()
            source.id = STATE_VIEW_ID
            source.submitEvents(listOf(Ev("e", 540, 660, content = "跨小时会议")))
            measureAndLayout(source, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
            source.setScrollOffset(PORTRAIT_SCROLL)
            source.setViewDate(threeDaysAgo)
            source.saveHierarchyState(container)

            // 旋转：同一份状态灌进按横屏尺寸重新测量布局的新视图。
            // 数据必须重新提交——SavedState 只保存滚动位置 / 查看日期 / 选中态，
            // **不保存日程数据**（那属于业务方，见 §11.1），真实旋转时也是
            // Activity 重建后由业务方重新 submitEvents。
            val rotated = newView()
            rotated.id = STATE_VIEW_ID
            rotated.submitEvents(listOf(Ev("e", 540, 660, content = "跨小时会议")))
            rotated.restoreHierarchyState(container)
            measureAndLayout(rotated, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
            landscapeScroll = rotated.getScrollOffset()
            landscapeDate = readViewDate(rotated)

            val recorder = RecordingGridPainter()
            rotated.gridPainter = recorder
            draw(rotated, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
            landscapeFrames = recorder.frames
            landscapeContentWidth = recorder.contentEnd - recorder.contentStart

            // 横屏几何：按新宽度算出的内容区中心 + 该日程在视口里的 y
            val x = (recorder.contentStart + recorder.contentEnd) / 2f
            landscapeHit = rotated.eventAt(x, viewportYOf(540, rotated.getScrollOffset()))?.id
        }

        assertEquals("旋转后查看日期必须保持（D12 / E9）", threeDaysAgo, landscapeDate)
        assertEquals(
            "旋转后滚动位置必须保持（D12）：竖屏设了 $PORTRAIT_SCROLL",
            PORTRAIT_SCROLL,
            landscapeScroll,
        )
        assertTrue("旋转后应当真的绘制了网格（实测 $landscapeFrames 帧）", landscapeFrames > 0)
        assertTrue(
            "旋转后内容区应按横屏宽度重新计算（实测 $landscapeContentWidth px，竖屏为 $PORTRAIT_WIDTH）",
            landscapeContentWidth > PORTRAIT_WIDTH,
        )
        assertNotNull("横屏几何下应能命中日程", landscapeHit)
        assertEquals("横屏下命中的应是那条 09:00–11:00 的日程", "e", landscapeHit)
    }

    private companion object {
        /** 固定「当前时间」，让三态推导不随运行时刻变化。 */
        const val FIXED_NOW = 720

        const val PORTRAIT_WIDTH = 1080
        const val PORTRAIT_HEIGHT = 1920
        const val LANDSCAPE_WIDTH = 1920
        const val LANDSCAPE_HEIGHT = 1080

        /** 旋转前后必须保持的滚动位置（远离边界，便于察觉被夹取）。 */
        const val PORTRAIT_SCROLL = 1234

        /** §12.2 要求的系统最大字体缩放档。 */
        const val FONT_SCALE_200 = 2.0f

        /** 状态容器靠 view id 配对，两个视图必须同 id。 */
        const val STATE_VIEW_ID = 0x0D71

        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
