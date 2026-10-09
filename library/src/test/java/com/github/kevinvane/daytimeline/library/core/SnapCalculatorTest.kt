package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §8.3 / D11：时间吸附。上下对称是硬要求。 */
class SnapCalculatorTest {

    @Test
    fun `吸附到最近刻度`() {
        assertEquals(600, SnapCalculator.snap(602, 15))
        assertEquals(600, SnapCalculator.snap(598, 15))
        assertEquals(615, SnapCalculator.snap(609, 15))
    }

    @Test
    fun `步长为 0 时关闭吸附`() {
        assertEquals(607, SnapCalculator.snap(607, 0))
    }

    @Test
    fun `关闭吸附后时长兜底仍然生效`() {
        // §8.3：设为 0 时不做对齐，但保留最短/最长时长兜底
        val r = SnapCalculator.snapMove(
            start = 600, end = 601, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 1440,
        )
        assertEquals(5, r.last - r.first)
    }

    /**
     * D11 上下对称：设某刻度线为 T，向上拖 d 分钟与向下拖 d 分钟，
     * 吸附结果相对 T 的偏移必须**大小相等、方向相反**。
     *
     * 这比「两者吸附到同一个值」更准确，也正是 §8.3 想要的手感：
     * 距刻度线半个步长以内会吸到该线，超过则吸到相邻线，且两个方向完全一致。
     *
     * 只在**两侧都有空间**的刻度上断言：00:00 之上 / 24:00 之下不存在可用时间，
     * 此时钳制到边界是正确行为而非不对称（`L10` 明确不支持跨天拖拽）。
     */
    @Test
    fun `D11 吸附上下对称`() {
        val step = 15
        val last = MinuteOfDay.END_OF_DAY_MINUTE
        var tick = step
        while (tick <= last - step) {
            val maxOffset = minOf(tick, last - tick)
            for (d in 0..maxOffset) {
                val up = SnapCalculator.snap(tick + d, step) - tick
                val down = SnapCalculator.snap(tick - d, step) - tick
                assertEquals(
                    "刻度 $tick 偏移 $d 分钟时上下不对称",
                    up,
                    -down,
                )
            }
            tick += step
        }
    }

    @Test
    fun `D11 边界处钳制到全天范围而非越界`() {
        // 00:00 之上：越界输入被钳到 0，不会产生负的分钟
        assertEquals(0, SnapCalculator.snap(-30, 15))
        // 24:00 之下：越界输入被钳到 1440
        assertEquals(1440, SnapCalculator.snap(1500, 15))
    }

    @Test
    fun `拖拽移动保持时长并在越界时整体平移`() {
        val r = SnapCalculator.snapMove(
            start = 607, end = 667, stepMinutes = 15,
            minDurationMinutes = 5, maxDurationMinutes = 1440,
        )
        assertEquals(60, r.last - r.first) // 时长保持
        assertEquals(0, r.first % 15) // 起点已吸附
    }

    @Test
    fun `拖拽移动到 24 点附近时整体回退而不裁剪时长`() {
        val r = SnapCalculator.snapMove(
            start = 1420, end = 1480, stepMinutes = 15,
            minDurationMinutes = 5, maxDurationMinutes = 1440,
        )
        assertEquals(60, r.last - r.first)
        assertTrue("结束不应超过 24 点", r.last <= 1440)
    }

    @Test
    fun `拖拽上边缘时结束时间吸附且不早于最短时长`() {
        // 开始 600，上边缘拖到 620，最短 5 分钟 → 不得早于 605
        val end = SnapCalculator.snapResizeEnd(start = 600, rawEnd = 620, stepMinutes = 15, minDurationMinutes = 5)
        assertEquals(615, end)
    }

    @Test
    fun `拖拽上边缘时短于最小时长自动兜底`() {
        val end = SnapCalculator.snapResizeEnd(start = 600, rawEnd = 602, stepMinutes = 0, minDurationMinutes = 5)
        assertEquals(605, end)
    }

    @Test
    fun `拖拽下边缘时结束时间吸附且不得晚于 24 点`() {
        val end = 1440
        val start = SnapCalculator.snapResizeStart(rawStart = 1430, end = end, stepMinutes = 15, minDurationMinutes = 5)
        assertTrue("开始时间应被吸附到刻度", start % 15 == 0)
        assertTrue("不得使时长短于最小时长", end - start >= 5)
    }

    @Test
    fun `新建默认时长 1 小时且跨 24 点时整体回退`() {
        val r = SnapCalculator.newEventRangeAt(
            tapMinute = 1430, stepMinutes = 15, defaultDurationMinutes = 60,
        )
        assertEquals(60, r.last - r.first)
        assertTrue(r.last <= 1440)
    }

    @Test
    fun `新建时起点吸附到刻度`() {
        val r = SnapCalculator.newEventRangeAt(
            tapMinute = 607, stepMinutes = 15, defaultDurationMinutes = 60,
        )
        assertEquals(0, r.first % 15)
    }

    // ===== applyRange：表单同时设定两端（PRD §8.3.1）=====

    @Test
    fun `applyRange 两端合法时原样返回`() {
        val r = SnapCalculator.applyRange(600, 660, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 1440)
        assertEquals(600, r.first)
        assertEquals(660, r.last)
    }

    /**
     * D11 的核心：**两端必须走同一套规则**。
     *
     * 若实现里只对开始时间吸附（或只对结束时间吸附），本断言立刻失败。
     */
    @Test
    fun `applyRange 两端对称吸附`() {
        val r = SnapCalculator.applyRange(602, 657, stepMinutes = 15, minDurationMinutes = 5, maxDurationMinutes = 1440)
        assertEquals(600, r.first)
        assertEquals(660, r.last)
        assertEquals("两端都应落在刻度上", 0, r.first % 15)
        assertEquals("两端都应落在刻度上", 0, r.last % 15)
    }

    @Test
    fun `applyRange 两端填反时自动交换`() {
        val r = SnapCalculator.applyRange(660, 600, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 1440)
        assertEquals(600, r.first)
        assertEquals(660, r.last)
    }

    @Test
    fun `applyRange 不足最短时长时把结束往后推`() {
        val r = SnapCalculator.applyRange(600, 602, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 1440)
        assertEquals(600, r.first)
        assertEquals(605, r.last)
    }

    /**
     * 起点贴着 24:00 且需要补足最小时长：此时**推不动结束**，
     * 必须改为把开始往前拉，且仍要满足 `end <= 1440`。
     */
    @Test
    fun `applyRange 起点贴近 24 点时改为把开始往前拉`() {
        val r = SnapCalculator.applyRange(
            1439, 1440, stepMinutes = 0, minDurationMinutes = 30, maxDurationMinutes = 1440,
        )
        assertEquals(1410, r.first)
        assertEquals(1440, r.last)
        assertTrue("结束不得越过 24:00", r.last <= 1440)
    }

    @Test
    fun `applyRange 超最长时长时从开始处截断`() {
        val r = SnapCalculator.applyRange(600, 1200, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 60)
        assertEquals(600, r.first)
        assertEquals(660, r.last)
    }

    @Test
    fun `applyRange 越界值被钳制到全天范围`() {
        val r = SnapCalculator.applyRange(-50, 9999, stepMinutes = 0, minDurationMinutes = 5, maxDurationMinutes = 1440)
        assertTrue("开始不得早于 00:00，实际 ${r.first}", r.first >= 0)
        assertTrue("结束不得晚于 24:00，实际 ${r.last}", r.last <= 1440)
    }

    /**
     * 不变式兜底：任意输入都必须落在合法区间内。
     *
     * 逐条断言比「逐个输入断言结果」更能防回归——新增兜底分支时若破坏
     * 任何一条不变式，这里立刻失败。
     */
    @Test
    fun `applyRange 任意输入都满足不变式`() {
        val inputs = listOf(
            0 to 0, -100 to 100, 100 to -100, 1439 to 1440, 1440 to 1440,
            700 to 705, 1200 to 600, 0 to 1440, 720 to 720,
        )
        for ((s, e) in inputs) {
            val r = SnapCalculator.applyRange(s, e, stepMinutes = 15, minDurationMinutes = 5, maxDurationMinutes = 90)
            assertTrue("输入 $s..$e 得到 ${r.first}..${r.last}：开始越界", r.first >= 0)
            assertTrue("输入 $s..$e 得到 ${r.first}..${r.last}：结束越界", r.last <= 1440)
            assertTrue("输入 $s..$e 得到 ${r.first}..${r.last}：时长不足", r.last - r.first >= 5)
            assertTrue("输入 $s..$e 得到 ${r.first}..${r.last}：时长达标", r.last - r.first <= 90)
        }
    }

    /**
     * 回归：**最短时长被设成大于全天时不得抛异常**。
     *
     * `minDurationMinutes` 来自业务方的 `TimelineConfig`，组件不校验其取值，
     * 而 PRD §11.4 要求任何输入都不得把异常抛给业务方。
     * 曾经的写法是 `maxDur = max.coerceIn(minDur, MINUTES_PER_DAY)`，
     * 当 minDur(2000) > 1440 时 `Int.coerceIn` 会抛
     * `IllegalArgumentException: Cannot coerce value to an empty range`。
     */
    @Test
    fun `applyRange 最短时长超过全天时不抛异常`() {
        val r = SnapCalculator.applyRange(
            rawStart = 600, rawEnd = 700,
            stepMinutes = 0, minDurationMinutes = 2000, maxDurationMinutes = 1440,
        )
        assertTrue("开始不得越界，实际 ${r.first}", r.first >= 0)
        assertTrue("结束不得越界，实际 ${r.last}", r.last <= 1440)
        assertTrue("两端不得颠倒，实际 $r", r.last > r.first)
    }

    /** 最短时长为负或 0 时按 1 分钟兜底，不得抛异常。 */
    @Test
    fun `applyRange 非法时长参数按 1 分钟兜底`() {
        for (bad in listOf(-100, 0)) {
            val r = SnapCalculator.applyRange(
                rawStart = 600, rawEnd = 600,
                stepMinutes = 0, minDurationMinutes = bad, maxDurationMinutes = -50,
            )
            assertEquals("非法最小时长 $bad 应兜底为 1 分钟", 1, r.last - r.first)
        }
    }
}
