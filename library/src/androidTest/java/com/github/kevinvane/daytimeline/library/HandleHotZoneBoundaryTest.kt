package com.github.kevinvane.daytimeline.library

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §14.3 **Q10**「所有触摸热区 ≥ 48dp（视觉可小）」的**反向**判定。
 *
 * ## 为什么必须是反向的
 *
 * 既有 `DayTimelineViewTest` 已断言「热区 ≥ 48dp、手柄视觉 < 热区」——
 * 但那验的是**尺寸声明**。Q10 在 PRD 里的判定原文是
 * 「触摸注入落在热区边界**内外各测一次，边界外 1dp 应不命中**」。
 *
 * 「能抓到」不构成判定：热区画得再大也「能抓到」。
 * **只有「边界外 1dp 抓不到」才能证明热区既够大、又没大到乱抢别处的点击。**
 * 2026-10-14 人工走查给的也只是「捏住手柄附近也能拖动」这种正面观感。
 *
 * ## 观测方式
 *
 * `grabbedHandle` 在 `ACTION_DOWN` 时由 `HitTester.hitTest` 置位
 * （1 = 上手柄 / -1 = 下手柄 / 0 = 没抓到），在 `ACTION_UP` 清零，
 * 因此**必须在 DOWN 之后、UP 之前读**——读完立刻抬手复原。
 *
 * 手柄热区是 `|x - handleX| <= 24dp 且 |y - editingTop| <= 24dp` 的方形，
 * 故从编辑块上边缘**逐像素向上探**，找到第一个不再命中的 y，
 * 那个位置就是热区真实边界，据此算半高并验证边界外 1dp 确实落空。
 */
@RunWith(AndroidJUnit4::class)
class HandleHotZoneBoundaryTest {

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
            // 只放一条：块会占满内容区宽度，手柄 x 即块的水平中心
            view.submitEvents(listOf(StubEvent(TARGET_ID, 600, 720)), notifyIssues = false)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
        }
    }

    @Test
    fun handleHotZoneIsAtLeast48dpAndMissesOnePixelOutside() {
        val handleX = enterEditAndGetHandleX()
        val blockTop = blockTop()

        // 从编辑块上边缘（热区中心）**逐像素向上走，直到第一次落空**。
        //
        // 注意方向：第一版把条件写反了（`!= TOP_HANDLE` 时继续），
        // 于是**第一次命中就停下**，测出的 halfZone 恒为 0——
        // 一个「恒等于 0」的量拿去断言「≥63」必然红，红的却是循环不是实现。
        var probe = blockTop
        while (probe > 0 && probeGrabbed(handleX, probe) == TOP_HANDLE) {
            probe--
        }
        val firstMissY = probe
        val lastHitY = probe + 1
        val halfZone = blockTop - lastHitY

        val expectedHalf = context.resources.getDimensionPixelSize(R.dimen.day_timeline_handle_touch) / 2

        assertTrue(
            "热区半高应 ≥ ${expectedHalf}px（48dp 的一半），实测 ${halfZone}px（块顶=$blockTop 最后命中=$lastHitY 首个落空=$firstMissY）",
            halfZone >= expectedHalf,
        )
        assertEquals(
            "边界外 1px 必须抓不到手柄（PRD §14.3 Q10 原文判据）",
            0,
            probeGrabbed(handleX, firstMissY),
        )
        // 边界那一像素本身应当命中（热区是闭区间）
        assertEquals(
            "热区边界那一像素应当命中（`|dy| <= 24dp` 是闭区间）",
            TOP_HANDLE,
            probeGrabbed(handleX, firstMissY + 1),
        )
    }

    /**
     * 进入编辑态并返回手柄的水平中心。
     *
     * 只放一条日程时块占满内容区宽度，故块的水平中心即 `handleX`。
     */
    private fun enterEditAndGetHandleX(): Float {
        var x = 0f
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val block = view.visibleBlockSnapshot().first { it.second.event.id == TARGET_ID }.second
            view.dispatchEventLongClickForAccessibility(block)
            val bounds = requireNotNull(view.blockBoundsInParent(block)) { "取不到块边界" }
            x = (bounds.left + bounds.right) / 2f
        }
        return x
    }

    private fun blockTop(): Int {
        var top = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val block = view.visibleBlockSnapshot().first { it.second.event.id == TARGET_ID }.second
            top = requireNotNull(view.blockBoundsInParent(block)) { "取不到块边界" }.top
        }
        return top
    }

    /**
     * 在 (x, y) 注入一次「按下 → 抬手」，返回按下瞬间的 `grabbedHandle`。
     *
     * 编辑态可能被上一次探针的抬手收掉（FI-010：点块外即取消），
     * 故每次探针前先确认仍在编辑态，不在就重新进入——
     * 扫描点会在块上方逐像素移动，必然有落在块外的，不能假设编辑态一直活着。
     */
    private fun probeGrabbed(x: Float, y: Int): Int {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (fieldOf("editSession").get(view) == null) {
                val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == TARGET_ID }?.second
                if (block != null) view.dispatchEventLongClickForAccessibility(block)
            }
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

    private fun fieldOf(name: String) =
        DayTimelineView::class.java.getDeclaredField(name).apply { isAccessible = true }

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
        const val HEIGHT = 1920
        const val TARGET_ID = "handle-target"
        const val TOP_HANDLE = 1
    }
}
