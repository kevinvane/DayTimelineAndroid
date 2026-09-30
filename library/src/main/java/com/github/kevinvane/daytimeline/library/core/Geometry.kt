package com.github.kevinvane.daytimeline.library.core

/**
 * 分钟坐标 ↔ 像素坐标换算（纯函数，AD-02 / T2：不得 import `android.*`）。
 *
 * 全部尺寸以参数形式传入而非读资源，这样布局算法能在 JVM 单测里直接跑。
 * 资源读取由 `internal/Dimens` 负责，绘制层负责把本类结果映射到 Canvas。
 *
 * 逻辑纵向坐标约定（AD-01）：
 * ```
 * 0                        ← 顶部留白之后的第一行，即 00:00 所在位置
 * topPadding + 24*hourH    ← 24:00 所在位置
 * + bottomPadding          ← 内容总高度（外部滚动模式的组件高度，§8.6）
 * ```
 */
object Geometry {

    /** 全天内容总高度 = 24 × 每小时格高 + 顶部留白 + 底部留白（§19.2 高度约定）。 */
    fun contentHeight(hourHeightPx: Int, topPaddingPx: Int, bottomPaddingPx: Int): Int =
        MinuteOfDay.MINUTES_PER_DAY / MinuteOfDay.MINUTES_PER_HOUR * hourHeightPx +
            topPaddingPx +
            bottomPaddingPx

    /**
     * 分钟 → 纵向偏移（像素，浮点以支持拖拽中的亚分钟精度）。
     *
     * 内部刻意**不加**顶部留白：留白属于内容区之外的边距，
     * 由绘制层统一处理，避免「偏移」与「绝对位置」两套含义混用。
     */
    fun minuteToOffset(minute: Float, hourHeightPx: Int): Float =
        minute / MinuteOfDay.MINUTES_PER_HOUR * hourHeightPx

    /** 纵向偏移（像素）→ 分钟（整数）。超出 [0, 1440] 时钳制。 */
    fun offsetToMinute(offsetPx: Float, hourHeightPx: Int): Int {
        if (hourHeightPx <= 0) return 0
        val minute = (offsetPx / hourHeightPx * MinuteOfDay.MINUTES_PER_HOUR).toInt()
        return minute.coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE)
    }

    /**
     * 日程块绘制高度（像素）。
     *
     * 三个守卫，逐条对应 PRD 里的质量缺陷：
     * - E17：格高被设成极小值时不产生 0 高度色块；
     * - D4 / Q2：**任何情况下高度不为负**；
     * - 极短日程按 `minBlockHeight` 兜底（§7.2 日程块最小显示高度 20dp）。
     */
    fun blockHeight(
        startMinute: Int,
        endMinute: Int,
        hourHeightPx: Int,
        minBlockHeightPx: Int,
    ): Int {
        if (hourHeightPx <= 0) return 0
        val raw = minuteToOffset((endMinute - startMinute).toFloat(), hourHeightPx).toInt()
        return raw.coerceAtLeast(minBlockHeightPx.coerceAtLeast(0))
    }

    /** 判断一个纵向区间 [blockTop, blockTop + blockHeight) 是否与视口相交。 */
    fun intersectsViewport(
        blockTopPx: Float,
        blockHeightPx: Int,
        scrollOffsetPx: Int,
        viewportHeightPx: Int,
    ): Boolean {
        if (viewportHeightPx <= 0) return false
        val viewTop = scrollOffsetPx.toFloat()
        val viewBottom = viewTop + viewportHeightPx
        val blockTop = blockTopPx
        val blockBottom = blockTopPx + blockHeightPx
        return blockBottom > viewTop && blockTop < viewBottom
    }
}
