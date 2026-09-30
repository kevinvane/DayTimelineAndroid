package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD §14.2 U1–U16：重叠分栏专项验收。
 *
 * 这是全项目最核心的回归集（D1），任何一条失败都不得发布。
 */
class OverlapLayoutEngineTest {

    private fun byId(layout: List<PlacedBlock>) = layout.associateBy { it.event.id }

    // ---------- U1 ----------
    @Test
    fun `U1 两条不重叠的日程上下排列且各占满宽度`() {
        val l = byId(LayoutCases.U1)
        assertEquals(2, l.size)
        assertEquals(1, LayoutCases.U1[0].columnCount) // W1：总列数 = 最大重叠数 = 1
        l.values.forEach {
            assertEquals(0, it.column)
            assertEquals(LayoutCases.WIDTH, it.width)
            assertEquals(0, it.left)
        }
    }

    // ---------- U2 ----------
    @Test
    fun `U2 两条部分重叠各占一半且互不遮挡`() {
        val l = byId(LayoutCases.U2)
        val a = l.getValue("U2_a")
        val b = l.getValue("U2_b")
        assertEquals(2, a.columnCount)
        assertEquals(0, a.column)
        assertEquals(1, b.column)
        assertEquals(LayoutCases.WIDTH / 2, a.width)
        assertEquals(LayoutCases.WIDTH / 2, b.width)
        // 互不遮挡
        assertTrue(a.right <= b.left)
    }

    // ---------- U3 ----------
    @Test
    fun `U3 长日程包含短日程时并排且短日程宽度足够显示内容`() {
        val l = byId(LayoutCases.U3)
        val long = l.getValue("U3_long")
        val short = l.getValue("U3_short")
        assertEquals(2, short.columnCount)
        assertEquals(LayoutCases.WIDTH / 2, short.width)
        assertTrue(long.right <= short.left)
    }

    // ---------- U4 ----------
    @Test
    fun `U4 三条互相重叠各占三分之一`() {
        val l = byId(LayoutCases.U4)
        assertEquals(3, l.getValue("U4_a").columnCount)
        val widths = l.values.map { it.width }.distinct()
        assertEquals(listOf(LayoutCases.WIDTH_3 / 3), widths)
        val lefts = l.values.map { it.left }.sorted()
        assertEquals(3, lefts.distinct().size)
    }

    // ---------- U5 空列复用（D1 核心回归项） ----------
    @Test
    fun `U5 前序日程结束后的空闲列被后续日程复用而不被挤到右侧`() {
        val l = byId(LayoutCases.U5)
        val a = l.getValue("U5_a")
        val b = l.getValue("U5_b")
        val c = l.getValue("U5_c")
        assertEquals(0, a.column)
        assertEquals(1, b.column)
        // C 与 A 重叠、与 B 不重叠 → 必须复用 B 空出的列 1，而不是被挤到列 2
        assertEquals(1, c.column)
        assertEquals(2, a.columnCount)
    }

    // ---------- U6 ----------
    @Test
    fun `U6 长日程加三条内部互不重叠短日程只需两列`() {
        val l = byId(LayoutCases.U6)
        assertEquals(2, l.getValue("U6_long").columnCount)
        val shorts = listOf("U6_s1", "U6_s2", "U6_s3").map { l.getValue(it) }
        // 三条短日程互不重叠，依次复用同一列纵向排列
        assertEquals(1, shorts.map { it.column }.distinct().size)
        shorts.forEach { assertEquals(1, it.column) }
    }

    // ---------- U7 首尾相接 ----------
    @Test
    fun `U7 首尾相接的日程判定为不重叠`() {
        val l = byId(LayoutCases.U7)
        assertEquals(1, l.getValue("U7_a").columnCount)
        assertEquals(LayoutCases.WIDTH, l.getValue("U7_a").width)
        assertEquals(LayoutCases.WIDTH, l.getValue("U7_b").width)
        assertEquals(0, l.getValue("U7_b").column)
    }

    // ---------- U8 ----------
    @Test
    fun `U8 空数据不报错且不产生任何色块`() {
        assertTrue(LayoutCases.U8.isEmpty())
    }

    // ---------- U9 ----------
    @Test
    fun `U9 仅一条日程占满可用宽度`() {
        val l = LayoutCases.U9
        assertEquals(1, l.size)
        assertEquals(LayoutCases.WIDTH, l[0].width)
        assertEquals(0, l[0].left)
    }

    // ---------- U10 顺序无关（D1④） ----------
    @Test
    fun `U10 同一组数据以不同顺序传入时布局结果完全一致`() {
        fun signature(l: List<PlacedBlock>) =
            l.map { "${it.event.id}:${it.column}:${it.left}:${it.width}" }.sorted()

        val expected = signature(LayoutCases.U10_forward)
        assertEquals(expected, signature(LayoutCases.U10_reversed))
        assertEquals(expected, signature(LayoutCases.U10_shuffled))
    }

    // ---------- U11 ----------
    @Test
    fun `U11 200 条日程的分栏不重叠且不越界`() {
        val layout = LayoutCases.U11
        assertEquals(200, layout.size)
        val total = layout.first().columnCount
        assertTrue("总列数应在合理范围", total in 1..200)
        // 同一列内的块在纵向不得重叠
        layout.groupBy { it.column }.forEach { (col, blocks) ->
            blocks.sortedBy { it.event.start.minuteOfDay }.let { sorted ->
                for (i in 0 until sorted.size - 1) {
                    val cur = sorted[i]
                    val next = sorted[i + 1]
                    assertTrue(
                        "列 $col 上 ${cur.event.id} 与 ${next.event.id} 纵向重叠",
                        !OverlapLayoutEngine.overlaps(
                            cur.event.start.minuteOfDay,
                            cur.event.end.minuteOfDay,
                            next.event.start.minuteOfDay,
                            next.event.end.minuteOfDay,
                        ),
                    )
                }
            }
        }
        // 宽度均不越界
        layout.forEach { assertTrue(it.right <= LayoutCases.WIDTH) }
    }

    // ---------- U12 ----------
    @Test
    fun `U12 200 条完全同时的日程分栏正确且不溢出屏幕`() {
        val layout = LayoutCases.U12
        assertEquals(200, layout.size)
        assertEquals(200, layout.first().columnCount)
        assertEquals(200, layout.map { it.column }.distinct().size)
        // 均分后总宽不超过可用宽
        val totalWidth = layout.sumOf { it.width }
        assertTrue("总宽 $totalWidth 不应超过 ${LayoutCases.WIDTH}", totalWidth <= LayoutCases.WIDTH)
        layout.forEach { assertTrue(it.right <= LayoutCases.WIDTH) }
    }

    // ---------- U14 向右扩展（W3） ----------
    @Test
    fun `U14 右侧相邻列纵向范围内为空时日程向右扩展且宽度大于基准列宽`() {
        val l = byId(LayoutCases.U14)
        val a = l.getValue("U14_a")
        assertEquals(2, a.columnCount)
        // A 扩展到整行
        assertEquals(2, a.span)
        assertEquals(LayoutCases.WIDTH, a.width)
        // W6：左边界不变
        assertEquals(0, a.left)
    }

    // ---------- U15 遇占用列即停（W5） ----------
    @Test
    fun `U15 扩展在遇到被占用的相邻列时立即停止不跨越到更右的空闲列`() {
        val l = byId(LayoutCases.U15)
        val a = l.getValue("U15_a")
        val b = l.getValue("U15_b")
        val c = l.getValue("U15_c")
        // 三列确实都被用到了，列2 存在且在 A 范围内空闲
        assertEquals(3, a.columnCount)
        assertEquals(0, a.column)
        assertEquals(1, b.column)
        assertEquals(2, c.column)
        // 但 A 遇到被占用的列1 就停，span=1，宽度 = 基准列宽
        assertEquals(1, a.span)
        assertEquals(LayoutCases.WIDTH_3 / 3, a.width)
    }

    // ---------- U16 横跨全天占满整行（W4） ----------
    @Test
    fun `U16 纵向横跨全天的日程占满整行`() {
        val all = LayoutCases.U16.single()
        assertEquals(0, all.column)
        assertEquals(all.columnCount, all.span)
        assertEquals(LayoutCases.WIDTH, all.width)
        assertEquals(0, all.left)
    }

    /**
     * U16 的对照：横跨全天但右侧列被占用时，**不得**扩展。
     *
     * 这条容易被误实现成「全天日程特殊对待，直接占满整行」，从而遮挡其它日程（D1）。
     * §7.9 示例第 5 行明确：右侧列被占用时 A 只占 1/2。
     */
    @Test
    fun `U16 对照 横跨全天但右侧列被占用时不得扩展以免遮挡`() {
        val l = byId(
            OverlapLayoutEngine.layout(
                LayoutCases.sanitize(Triple("x", 0, 1440), Triple("y", 600, 660)),
                LayoutCases.WIDTH, 0,
            ),
        )
        val allDay = l.getValue("x")
        assertEquals(1, allDay.span)
        assertEquals(LayoutCases.WIDTH / 2, allDay.width)
    }

    // ---------- W1/W2 补充 ----------
    @Test
    fun `W1 总列数等于全天最大同时重叠条数且对全天统一生效`() {
        // 上午两条重叠、下午三条重叠 → 全天统一为 3 列，上午的块也按 3 列基准等分
        val layout = OverlapLayoutEngine.layout(
            LayoutCases.sanitize(
                Triple("a", 540, 600),
                Triple("b", 540, 600),
                Triple("c", 600, 660),
                Triple("d", 600, 660),
                Triple("e", 600, 660),
            ),
            LayoutCases.WIDTH_3, 0,
        )
        assertTrue(layout.all { it.columnCount == 3 })
        assertEquals(LayoutCases.WIDTH_3 / 3, layout.first().width)
    }

    @Test
    fun `W2 列宽基准等分并扣除日程块间距`() {
        // 两条完全同时的日程 → 2 列；可用宽 600、gap 10
        // usable = 600 - 10×(2-1) = 590 → base = 295，stride = 295 + 10 = 305
        val blocks = OverlapLayoutEngine.layout(
            LayoutCases.sanitize(Triple("a", 540, 600), Triple("b", 540, 600)),
            600, 10,
        )
        assertEquals(2, blocks.first().columnCount)
        assertEquals(295, blocks.first().width)
        assertEquals(0, blocks.first().left)
        val second = blocks.first { it.column == 1 }
        assertEquals(305, second.left)
        // 两者并排不重叠
        assertTrue(blocks.first().right <= second.left)
    }

    @Test
    fun `W6 扩展后左边界保持不动仅向右变宽`() {
        val layout = OverlapLayoutEngine.layout(
            LayoutCases.sanitize(Triple("a", 540, 600), Triple("b", 600, 660)),
            600, 0,
        )
        val a = layout.first { it.event.id == "a" }
        // 单列场景下 a 占满整行，但左边界仍是列 0 的位置
        assertEquals(0, a.column)
        assertEquals(0, a.left)
        assertEquals(600, a.width)
    }

    @Test
    fun `D4 任何情况下块宽不为负`() {
        // 可用宽为 0（尚未测量完成，E23）
        val layout = OverlapLayoutEngine.layout(
            LayoutCases.sanitize(Triple("a", 540, 600), Triple("b", 570, 600)),
            0, 0,
        )
        layout.forEach {
            assertTrue("宽度不应为负: ${it.width}", it.width >= 0)
            assertTrue("左边界不应为负: ${it.left}", it.left >= 0)
        }
    }
}
