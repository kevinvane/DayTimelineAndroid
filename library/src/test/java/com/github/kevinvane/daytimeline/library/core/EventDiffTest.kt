package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD FD-003 / AC-06：数据变更后滚动位置不跳变。 */
class EventDiffTest {

    private val hourHeight = 56
    private val topPadding = 8

    private fun blocks(vararg entries: Triple<String, Int, Int>, width: Int = 600): List<PlacedBlock> =
        OverlapLayoutEngine.layout(LayoutCases.sanitize(*entries), width, 0)

    @Test
    fun `空数据时没有锚点`() {
        assertNull(EventDiff.anchorOf(emptyList(), 100, hourHeight, topPadding))
    }

    @Test
    fun `格高非法时没有锚点`() {
        val b = blocks(Triple("a", 540, 600))
        assertNull(EventDiff.anchorOf(b, 100, 0, topPadding))
    }

    @Test
    fun `锚点记录视口顶部那条日程及其相对偏移`() {
        val b = blocks(Triple("a", 540, 600), Triple("b", 700, 800))
        // 视口顶部 100px，对应 a 的顶边 = 8 + (540/60)*56 = 512
        val anchor = EventDiff.anchorOf(b, 100, hourHeight, topPadding)
        requireNotNull(anchor)
        assertEquals("a", anchor.eventId)
        assertEquals(512 - 100, anchor.offsetInViewport)
    }

    @Test
    fun `数据变更后按锚点精确还原滚动位置`() {
        val before = blocks(Triple("a", 540, 600), Triple("b", 700, 800))
        val offset = 100
        val anchor = EventDiff.anchorOf(before, offset, hourHeight, topPadding)
        requireNotNull(anchor)

        // 数据更新：b 的时间变了，但 a 还在且位置没变
        val after = blocks(Triple("a", 540, 600), Triple("b", 900, 950))
        val restored = EventDiff.restoreOffset(anchor, after, hourHeight, topPadding, fallback = 999)
        assertEquals(offset, restored)
    }

    @Test
    fun `锚点日程消失时退化为保持绝对偏移`() {
        val before = blocks(Triple("a", 540, 600), Triple("b", 700, 800))
        val anchor = EventDiff.anchorOf(before, 100, hourHeight, topPadding)
        requireNotNull(anchor)
        // a 被删掉了
        val after = blocks(Triple("b", 700, 800))
        val fallback = 42
        assertEquals(fallback, EventDiff.restoreOffset(anchor, after, hourHeight, topPadding, fallback))
    }

    @Test
    fun `锚点为空时返回回退值`() {
        val b = blocks(Triple("a", 540, 600))
        assertEquals(7, EventDiff.restoreOffset(null, b, hourHeight, topPadding, 7))
    }

    @Test
    fun `还原结果不会为负`() {
        val before = blocks(Triple("a", 0, 600))
        val anchor = EventDiff.anchorOf(before, 0, hourHeight, topPadding)
        requireNotNull(anchor)
        val after = blocks(Triple("a", 0, 600))
        val restored = EventDiff.restoreOffset(anchor, after, hourHeight, topPadding, 0)
        assertTrue("不应为负", restored >= 0)
    }

    // ---------- sameRange（E28 冲突判定的基础） ----------

    @Test
    fun `时间范围相同则视为未变更`() {
        val a = EventSanitizer.sanitize(listOf(TestEvent("x", 540, 600))).events.single()
        val b = EventSanitizer.sanitize(listOf(TestEvent("x", 540, 600))).events.single()
        assertTrue(EventDiff.sameRange(a, b))
    }

    @Test
    fun `时间范围不同则视为已变更`() {
        val a = EventSanitizer.sanitize(listOf(TestEvent("x", 540, 600))).events.single()
        val b = EventSanitizer.sanitize(listOf(TestEvent("x", 540, 660))).events.single()
        assertFalse(EventDiff.sameRange(a, b))
    }

    @Test
    fun `一方缺失时按不同处理`() {
        val a = EventSanitizer.sanitize(listOf(TestEvent("x", 540, 600))).events.single()
        assertFalse(EventDiff.sameRange(a, null))
        assertFalse(EventDiff.sameRange(null, a))
        assertTrue(EventDiff.sameRange(null, null))
    }
}

/** PRD §9.1 / E6：跨越全天。 */
class GeometryTest {

    private val hourHeight = 56

    @Test
    fun `内容高度为 24 倍格高加上下留白`() {
        assertEquals(24 * 56 + 8 + 8, Geometry.contentHeight(56, 8, 8))
    }

    @Test
    fun `分钟与像素互转`() {
        assertEquals(0f, Geometry.minuteToOffset(0f, hourHeight), 0.01f)
        assertEquals(56f, Geometry.minuteToOffset(60f, hourHeight), 0.01f)
        assertEquals(0, Geometry.offsetToMinute(0f, hourHeight))
        assertEquals(60, Geometry.offsetToMinute(56f, hourHeight))
    }

    @Test
    fun `越界的偏移被钳制到全天范围`() {
        assertEquals(0, Geometry.offsetToMinute(-100f, hourHeight))
        assertEquals(1440, Geometry.offsetToMinute(99999f, hourHeight))
    }

    @Test
    fun `格高为 0 时不崩溃`() {
        assertEquals(0, Geometry.offsetToMinute(100f, 0))
        assertEquals(0, Geometry.blockHeight(0, 60, 0, 20))
    }

    @Test
    fun `极短日程按最小显示高度兜底`() {
        // 2 分钟在 56dp 格高下不足 1dp
        assertEquals(20, Geometry.blockHeight(0, 2, hourHeight, 20))
    }

    @Test
    fun `正常时长按比例换算`() {
        assertEquals(56, Geometry.blockHeight(0, 60, hourHeight, 20))
        assertEquals(112, Geometry.blockHeight(0, 120, hourHeight, 20))
    }

    @Test
    fun `视口相交判定`() {
        // 块在 [100, 150]，视口 [0, 200] → 相交
        assertTrue(Geometry.intersectsViewport(100f, 50, 0, 200))
        // 块在 [100, 150]，视口 [200, 400] → 不相交
        assertFalse(Geometry.intersectsViewport(100f, 50, 200, 200))
        // 视口高度为 0 → 视为不可见，避免除零与误绘制
        assertFalse(Geometry.intersectsViewport(100f, 50, 0, 0))
    }
}

/**
 * FI-012 / §8.5「首次定位」。
 *
 * 断言写成**不变量**（「定位后当前时间线落在视口顶部往下三分之一屏」）而不是
 * 「拿公式再算一遍」——后者只是把实现抄到测试里，实现错了测试也会跟着错。
 */
class FirstLocateTest {

    private val hourHeight = 200
    private val topPadding = 8
    private val viewport = 1800
    private val contentHeight = 24 * hourHeight + topPadding + 8
    private val maxScroll = contentHeight - viewport

    private fun locate(minute: Float, lead: Int = topPadding) = Geometry.firstLocateOffset(
        minute = minute,
        hourHeightPx = hourHeight,
        topPaddingPx = lead,
        viewportHeightPx = viewport,
        maxScrollPx = maxScroll,
    )

    @Test
    fun `当前时间落在视口顶部往下三分之一屏处`() {
        val offset = locate(720f) // 12:00
        val nowContentY = topPadding + (720f / 60f * hourHeight).toInt()
        assertEquals(viewport / 3, nowContentY - offset)
    }

    /** 顶部留白必须计入：漏掉它定位会整体偏上，留白越大偏得越多。 */
    @Test
    fun `顶部留白计入定位`() {
        assertEquals(40, locate(720f, lead = topPadding + 40) - locate(720f))
        assertEquals(0, locate(720f, lead = 0) + 600 - 2400) // 12:00 恰好落在三分之一屏
    }

    /** 凌晨：当前时间在视口顶部之上，钳到 0（顶部），不得为负。 */
    @Test
    fun `凌晨钳到顶部`() {
        assertEquals(0, locate(0f))
        assertEquals(0, locate(30f)) // 00:30 仍不足三分之一屏
    }

    /** 深夜：当前时间在视口底部之下，钳到可滚动的最大值，不得越界。 */
    @Test
    fun `深夜钳到底部`() {
        assertEquals(maxScroll, locate(1440f))
    }

    /**
     * 外部滚动模式下组件高度等于全天内容高度（§8.6），可滚动上限为 0，
     * 因此首次定位必须不产生任何位移——滚动归外层容器负责。
     */
    @Test
    fun `无滚动余量时不产生位移`() {
        val offset = Geometry.firstLocateOffset(
            minute = 720f,
            hourHeightPx = hourHeight,
            topPaddingPx = topPadding,
            viewportHeightPx = contentHeight,
            maxScrollPx = 0,
        )
        assertEquals(0, offset)
    }

    /** 脏参数不抛异常且退回顶部（§11.4 绝不崩溃）。 */
    @Test
    fun `非法尺寸退回顶部而不抛异常`() {
        val bad = arrayOf(
            Triple(0, viewport, maxScroll),      // 格高为 0
            Triple(hourHeight, 0, maxScroll),   // 视口未测量
            Triple(hourHeight, viewport, -100), // 上限为负
        )
        bad.forEach { (hh, vh, ms) ->
            assertEquals(
                0,
                Geometry.firstLocateOffset(720f, hh, topPadding, vh, ms),
            )
        }
    }

    /** 三分之一屏的分母是 3（PRD §8.5），把它钉住以免被误改成常量 48dp。 */
    @Test
    fun `分母为 3`() {
        assertEquals(3, Geometry.FIRST_LOCATE_VIEWPORT_DIVISOR)
    }
}
