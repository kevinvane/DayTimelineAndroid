package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **FC-002 换肤验收**：业务方覆盖同名资源后，组件是否真的用上了新取值。
 *
 * ## 为什么要这条测试
 *
 * 「业务方在自己资源里覆盖同名 `day_timeline_*` 即可换肤」是本组件对外承诺的
 * 核心能力之一，也是[主题定制指南](../../docs/DayTimeline-主题定制指南.md)的全部前提。
 * 但它此前**零测试覆盖**——102 条仪器测试里没有一条覆盖过资源覆盖路径。
 *
 * 这类「只靠文档与注释自证」的能力正是本仓库反复出现的故障形态：
 * 承诺写在文档里，没有任何东西守着，实现哪天断掉也不会有人知道。
 *
 * ## 测的是「贯穿到像素」，不是「能取到值」
 *
 * 断言只做到 `getColor()` 是不够的：资源能取到值，不代表组件真的用它绘制。
 * 中间隔着资源解析、`Dimens` → `TimelineColors`、再到 `onDraw` 取色。
 * 这里断言的是**真正画进 Bitmap 的像素**。
 *
 * 夹具见 `src/androidTest/res/values/colors.xml`——用资源覆盖实现，
 * 与业务方接入时**完全同构**（同样不碰组件源码，只声明同名项）。
 */
@RunWith(AndroidJUnit4::class)
class ThemeOverrideTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 当前是否为夜间模式。
     *
     * 换肤夹具的浅色与深色**用了不同的取值**，所以断言必须知道此刻是哪一套
     * 才会生效——否则同一份代码在 CI（通常固定 `night no`）与本地夜间模式下
     * 表现不一致，就会变成一条只在某个时段成立的红灯。
     */
    private val isNight: Boolean
        get() = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val expectedBackground: Int
        get() = if (isNight) OVERRIDE_BG_NIGHT else OVERRIDE_BG_LIGHT

    private val expectedBlockBg: Int
        get() = if (isNight) OVERRIDE_BLOCK_BG_NIGHT else OVERRIDE_BLOCK_BG_LIGHT

    private val designBackground: Int
        get() = if (isNight) DESIGN_BG_NIGHT else DESIGN_BG_LIGHT

    private data class TintedEvent(
        override val id: String,
        val from: Int,
        val to: Int,
        override val color: Int?,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(from)
        override val end get() = MinuteOfDay.ofMinute(to)
    }

    @Test
    fun overriddenColorIsPresentInMergedResources() {
        assertTrue(
            "夹具没生效：当前模式(${if (isNight) "夜" else "日"})里 background 应当是 " +
                "${hex(expectedBackground)}，实际 ${hex(actualBackground)}。" +
                "若本条失败，先检查 src/androidTest/res/values 与 values-night 两个 colors.xml " +
                "是否都进了测试 APK——只覆盖一套的话，另一种模式下会取到 main 里的设计定稿值。",
            ContextCompat.getColor(context, R.color.day_timeline_background) == expectedBackground,
        )
    }

    private val actualBackground: Int
        get() = ContextCompat.getColor(context, R.color.day_timeline_background)

    /**
     * 核心用例：覆盖值必须**贯穿到真正画出来的像素**。
     *
     * 只断言 `getColor()` 会漏掉「资源解析对了但绘制没跟上」这类断链——
     * 颜色可能在中途某一步被重新解析回默认值。
     */
    @Test
    fun overriddenColorsReachTheRenderedPixels() {
        val view = buildView()
        // 10:00 为「现在」，09:30~10:30 这条即为「进行中」
        view.setNowMinute(600)
        view.submitEvents(listOf(TintedEvent("ongoing", 570, 630, null)), notifyIssues = false)

        val bmp = render(view)
        assertTrue(
            "背景应画成业务方覆盖的色值 ${hex(expectedBackground)}，" +
                "而画布里没有——覆盖值没贯穿到绘制。",
            bmp.containsColor(expectedBackground),
        )
        assertTrue(
            "进行中块底色应画成业务方覆盖的色值 ${hex(expectedBlockBg)}，而画布里没有。",
            bmp.containsColor(expectedBlockBg),
        )
        assertFalse(
            "画布里不应再出现当前模式下的设计定稿原色 ${hex(designBackground)}——" +
                "说明覆盖没有真正生效，只是恰好画了别的颜色。",
            bmp.containsColor(designBackground),
        )
    }

    /**
     * 业务色通道与 17 项主题色是**两条独立通道**，换肤不应影响业务色。
     *
     * 这是接入方最常误解的一点（见主题定制指南 §2.3），
     * 因此用像素把它钉住：块底色来自被覆盖的主题色，色条来自 `TimelineEvent.color`。
     */
    @Test
    fun businessAccentColorIsIndependentFromThemeOverride() {
        val view = buildView()
        view.setNowMinute(600)
        val businessAccent = 0xFF123456.toInt()
        view.submitEvents(
            listOf(TintedEvent("tinted", 570, 630, businessAccent)),
            notifyIssues = false,
        )

        val bmp = render(view)
        assertTrue(
            "块底色应是被覆盖的主题色 ${hex(expectedBlockBg)}",
            bmp.containsColor(expectedBlockBg),
        )
        assertTrue(
            "左侧色条应来自 TimelineEvent.color ${hex(businessAccent)}，" +
                "业务色通道不应被主题覆盖影响。",
            bmp.containsColor(businessAccent),
        )
    }

    private fun buildView(): DayTimelineView {
        lateinit var view: DayTimelineView
        // View 构造会创建 Handler，必须在主线程（详见 library 的 DayTimelineViewTest）
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
            view.setConfig(
                TimelineConfig(
                    // 外部滚动模式下 maxScroll() 恒为 0，scrollOffset 被钉在 0，
                    // 整天的内容一次性全画出来——这样断言就不依赖「此刻几点」，
                    // 也不会因为设备密度不同而把 09:30 那条挤出视口。
                    scrollMode = ScrollMode.EXTERNAL,
                    // FI-012 的自动定位会按**真实时钟**滚动；
                    // 本例已用外部滚动钉住偏移，这里关掉以免多一份不确定性。
                    autoLocateOnFirstShow = false,
                    // 避免当前时间线参与像素断言
                    showNowIndicator = false,
                ),
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(FULL_DAY_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, FULL_DAY_HEIGHT)
        }
        return view
    }

    private fun render(view: DayTimelineView): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, FULL_DAY_HEIGHT, Bitmap.Config.ARGB_8888)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.draw(Canvas(bmp))
        }
        return bmp
    }

    private fun Bitmap.containsColor(color: Int): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) == color) return true
            }
        }
        return false
    }

    private fun hex(color: Int): String =
        "#%06X".format(color and 0xFFFFFF)

    private companion object {
        const val WIDTH = 1080

        /**
         * 足够装下整天的高度。
         *
         * 外部滚动模式下组件高度 = 24 × 格高 + 上下留白，格高是 56dp——
         * density 3.5 时约 4704px、density 4.4 时约 6000px，7000 两头都够。
         * 写死一个与设备无关的高度，是为了让断言不依赖 density、也不依赖当前时间。
         */
        const val FULL_DAY_HEIGHT = 7000

        /**
         * 与 `src/androidTest/res/values/` 保持一致。
         *
         * 刻意选得很扎眼（品红 / 橙 / 绿），在设计定稿里都不存在，
         * 断言不可能与真实取值混淆。
         */
        const val OVERRIDE_BG_LIGHT = 0xFFFF00FF.toInt()
        const val OVERRIDE_BLOCK_BG_LIGHT = 0xFFFF6600.toInt()

        /** 与 `src/androidTest/res/values-night/` 保持一致（青 / 紫 / 黄）。 */
        const val OVERRIDE_BG_NIGHT = 0xFF00FFFF.toInt()
        const val OVERRIDE_BLOCK_BG_NIGHT = 0xFF6633FF.toInt()

        /** 设计定稿（品牌蓝版）的原始取值，用于证明「原色确实消失了」。 */
        const val DESIGN_BG_LIGHT = 0xFFFFFFFF.toInt()
        const val DESIGN_BG_NIGHT = 0xFF101318.toInt()
    }
}
