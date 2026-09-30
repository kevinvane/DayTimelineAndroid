package com.github.kevinvane.daytimeline.library.core

/**
 * 布局后的日程块：一个日程在屏幕上的横向位置（PRD §7.9 W1–W6 的输出）。
 *
 * 坐标为相对组件内容区左边缘的像素值，已扣除时间轴区域宽度。
 * RTL 由绘制层镜像（AD-10），因此这里只产出「逻辑位置」。
 */
data class PlacedBlock(
    val event: SanitizedEvent,
    /** 所属列号，从 0 开始。 */
    val column: Int,
    /** 全天总列数（= 最大同时重叠条数，W1）。 */
    val columnCount: Int,
    /** 实际占用的列数（含因向右扩展而并入的空闲列，W3）。 */
    val span: Int,
    /** 左边界。扩展后**保持不动**（W6）。 */
    val left: Int,
    /** 宽度。 */
    val width: Int,
) {
    val right: Int get() = left + width
}

/**
 * 重叠日程分栏与宽度分配（PRD D1 / §7.9 W1–W6 / §14.2 U1–U16）。
 *
 * 本产品最核心也最易出错的一块，因此刻意做成**纯函数**：无 Android 依赖、无状态、
 * 结果只由入参决定，因而可以用普通 JUnit 逐条验证 U1–U16（这是 T2/T4 覆盖率达标的基础）。
 *
 * 规则到实现的对应：
 * | 规则 | 实现位置 |
 * | |---|
 * | W1 总列数固定 | [maxConcurrency] |
 * | W2 基准等分 | [columnStride] / [columnBase] |
 * | W3 允许向右扩展 | [expandRight] |
 * | W4 扩展上限占满整行 | span 上界即总列数 |
 * | W5 不向左扩展 | 只向右探测 |
 * | W6 左边界不变 | left 只由 column 决定 |
 */
object OverlapLayoutEngine {

    /** 两个半开区间 `[s1,e1)`、`[s2,e2)` 是否重叠。首尾相接（e1 == s2）不算重叠（U7）。 */
    fun overlaps(
        startA: Int,
        endA: Int,
        startB: Int,
        endB: Int,
    ): Boolean = startA < endB && startB < endA

    /**
     * 全天最大同时重叠条数（W1）。
     *
     * 扫描线：用 `t * 2` 表示「在 t 结束」、`t * 2 + 1` 表示「在 t 开始」。
     * 排序后同一时刻的**结束点天然排在开始点之前**（因为 `2t < 2t + 1`），
     * 于是「前者结束时间 == 后者开始时间」不会被误判为重叠（U7）。
     */
    fun maxConcurrency(events: List<SanitizedEvent>): Int {
        if (events.isEmpty()) return 0
        val points = ArrayList<Int>(events.size * 2)
        for (e in events) {
            points.add(e.end.minuteOfDay * 2)
            points.add(e.start.minuteOfDay * 2 + 1)
        }
        points.sort()
        var current = 0
        var max = 0
        for (p in points) {
            if (p % 2 == 0) current-- else current++
            if (current > max) max = current
        }
        return max
    }

    /**
     * 主入口：计算全部日程块的横向位置。
     *
     * @param events 已兜底修正的数据（必来自 [EventSanitizer]）。
     * @param availableWidth 可用宽度（已扣除时间轴区域与右侧边距）。
     * @param gapPx 相邻列之间的「日程块间距」（W2 要求扣除）。
     */
    fun layout(
        events: List<SanitizedEvent>,
        availableWidth: Int,
        gapPx: Int,
    ): List<PlacedBlock> {
        if (events.isEmpty()) return emptyList()

        // D1④ / U10：内部排序，列分配只取决于时间重叠关系，与传入顺序无关。
        // 排序键必须含 id —— 否则 E25「多条完全同时」的排序不稳定，U10 会失败。
        val sorted = events.sortedWith(
            compareBy({ it.start.minuteOfDay }, { it.end.minuteOfDay }, { it.id }),
        )

        val total = maxConcurrency(sorted)
        if (total <= 0) return emptyList()

        val columns = assignColumns(sorted, total)
        val occupancy = buildOccupancy(sorted, columns, total)

        val gap = gapPx.coerceAtLeast(0)
        val usable = (availableWidth - gap * (total - 1)).coerceAtLeast(0)
        val base = if (total > 0) usable / total else 0
        val stride = base + gap

        return sorted.mapIndexed { i, event ->
            val col = columns[i]
            val span = expandRight(event, col, total, occupancy)
            // D4 / Q2：宽度绝不为负。可用宽不足时退化为 0，而不是负值或溢出屏幕。
            val width = (span * base + (span - 1) * gap).coerceAtLeast(0)
            PlacedBlock(
                event = event,
                column = col,
                columnCount = total,
                span = span,
                left = (col * stride).coerceAtLeast(0),
                width = width,
            )
        }
    }

    /**
     * 列分配：贪心首次适配 + 空列复用。
     *
     * `nextFreeAt[c]` 记录第 c 列已分配日程中最晚的结束时间。
     * 某日程可以放进第 c 列，当且仅当 `nextFreeAt[c] <= 该日程开始时间`——
     * 这正是 U5「前序日程结束后的空余列必须被后续日程复用」的实现：
     * 优先取**列号最小**的可用列，列被腾空后立刻可被再次使用，不会把日程挤到右侧。
     */
    private fun assignColumns(sorted: List<SanitizedEvent>, total: Int): IntArray {
        val nextFreeAt = IntArray(total) { Int.MIN_VALUE }
        val out = IntArray(sorted.size)
        for (i in sorted.indices) {
            val start = sorted[i].start.minuteOfDay
            var chosen = -1
            for (c in 0 until total) {
                if (nextFreeAt[c] <= start) {
                    chosen = c
                    break
                }
            }
            // 理论不会发生（total 已由 maxConcurrency 保证足够），兜底取最后一列，
            // 绝不抛异常（Q4）。
            if (chosen < 0) chosen = total - 1
            nextFreeAt[chosen] = sorted[i].end.minuteOfDay
            out[i] = chosen
        }
        return out
    }

    /** 每列的占用区间 + 前缀最大结束时间，供扩展判定在 O(log n) 内完成。 */
    private class ColumnOccupancy(
        val starts: IntArray,
        val maxEndPrefix: IntArray,
    ) {
        fun hasOverlap(start: Int, end: Int): Boolean {
            val n = starts.size
            if (n == 0) return false
            // 找最后一个 starts[j] < end 的下标
            var lo = 0
            var hi = n - 1
            var last = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < end) {
                    last = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (last < 0) return false
            return maxEndPrefix[last] > start
        }
    }

    private fun buildOccupancy(
        sorted: List<SanitizedEvent>,
        columns: IntArray,
        total: Int,
    ): Array<ColumnOccupancy> {
        val buckets = Array(total) { ArrayList<SanitizedEvent>() }
        for (i in sorted.indices) {
            buckets[columns[i]] += sorted[i]
        }
        return Array(total) { idx ->
            val list = buckets[idx]
            val n = list.size
            val starts = IntArray(n)
            val prefix = IntArray(n)
            var maxEnd = Int.MIN_VALUE
            for (i in 0 until n) {
                starts[i] = list[i].start.minuteOfDay
                if (list[i].end.minuteOfDay > maxEnd) maxEnd = list[i].end.minuteOfDay
                prefix[i] = maxEnd
            }
            ColumnOccupancy(starts, prefix)
        }
    }

    /**
     * 向右扩展（W3 / W4 / W5 / W6 / U14 / U15 / U16）。
     *
     * 从本列的右侧列开始逐列探测：若该列在 `[start, end)` 区间内**完全空闲**则并入，
     * 一旦遇到被占用的列**立即停止**——不跨越占用列继续向右（U15 / W5）。
     * 上界为总列数，即最多占满整行（W4 / U16）。
     */
    private fun expandRight(
        event: SanitizedEvent,
        column: Int,
        total: Int,
        occupancy: Array<ColumnOccupancy>,
    ): Int {
        val start = event.start.minuteOfDay
        val end = event.end.minuteOfDay
        var span = 1
        var c = column + 1
        while (c < total) {
            if (occupancy[c].hasOverlap(start, end)) break
            span++
            c++
        }
        return span
    }
}
