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

    /**
     * 「当前时间向上偏移约三分之一屏」的分母（PRD §8.5 / FI-012）。
     *
     * 为什么是代码常量而不是 `@dimen`：**它是无量纲比值，不是尺寸**。
     * `dimens.xml` 里用 `<item format="float" type="dimen">` 声明过同类值，
     * AAPT2 按 `format` 编译成 `TYPE_FLOAT(0x4)`，而 `Resources.getDimension()`
     * 只接受 `TYPE_DIMENSION(0x5)`，真机启动即抛 `NotFoundException`
     * （见该文件内的警示注释 / AD-15）。
     */
    const val FIRST_LOCATE_VIEWPORT_DIVISOR = 3

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
     * 首次定位（FI-012 / §8.5）：让**当前时间线**落在视口顶部往下约三分之一屏处。
     *
     * PRD §8.5 原文：「首次定位 | 默认定位到『当前时间向上偏移约三分之一屏』」。
     * 即当前时间之上留三分之一屏（看得见已经过去的时段），之下留三分之二屏
     * ——用户此刻真正关心的是接下来要发生的事，所以「下方多于上方」。
     * 这也是系统日历打开今天的默认行为。
     *
     * ## 为什么这里要显式带上 [topPaddingPx]
     *
     * [minuteToOffset] 刻意**不含**顶部留白（见其 KDoc），而内容坐标系里第 [minute] 分钟
     * 的真实纵向位置是 `topPadding + minuteToOffset(...)`——绘制与命中测试都是这么算的。
     * 漏掉它会让定位结果整体偏上整整一个顶部留白。
     *
     * ## 钳制
     *
     * 结果钳制在 `[0, maxScrollPx]`：凌晨（当前时间落在视口顶部之上）与深夜
     * （当前时间落在底部之下）都必须得到合法偏移，组件绝不越界（§11.4 / D4）。
     * 格高或视口高度非正（尚未测量完成）时返回 0，即停在顶部。
     */
    fun firstLocateOffset(
        minute: Float,
        hourHeightPx: Int,
        topPaddingPx: Int,
        viewportHeightPx: Int,
        maxScrollPx: Int,
    ): Int {
        if (hourHeightPx <= 0 || viewportHeightPx <= 0) return 0
        val nowTopPx = topPaddingPx + minuteToOffset(minute, hourHeightPx).toInt()
        val leadInPx = viewportHeightPx / FIRST_LOCATE_VIEWPORT_DIVISOR
        return (nowTopPx - leadInPx).coerceIn(0, maxScrollPx.coerceAtLeast(0))
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
