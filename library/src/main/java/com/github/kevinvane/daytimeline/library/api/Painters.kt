package com.github.kevinvane.daytimeline.library.api

import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.ColorInt

/**
 * 时间轴网格的绘制上下文（提供给 [GridPainter]）。
 *
 * 全部坐标为 px。`axisAreaEnd` 与 `contentStart` 已按布局方向解析好，
 * 业务方无需关心 RTL——**镜像由组件完成**（AD-10）。
 *
 * ## 生命周期：这是可复用对象，不是快照
 *
 * 组件内部**只持有一份**该对象并在每帧复用，因此字段是可变的。
 * 实现 [GridPainter] 时**不得**把 `context` 或从它读出的对象存到字段里长期持有，
 * 只能在本次 `paint` 调用内使用。这样才能满足 §12.1「增量更新 ≤16ms」
 * 与「滚动不掉帧」——每帧新建一个对象在 200 条日程时是明显的 GC 压力。
 */
class GridContext {
    var width = 0
    var height = 0
    var scrollOffset = 0
    var axisAreaEnd = 0
    var contentStart = 0
    var contentEnd = 0
    var hourHeight = 0
    var topPadding = 0
    var bottomPadding = 0
    var gridLineWidth = 0
    var showNowIndicator = false
    var nowMinute = 0

    /** 当前时间线圆点直径（px）、红线粗细（px）（§7.6）。 */
    var nowDotDiameter = 0
    var nowLineWidth = 0

    /** 当前时间文字带重叠整点轴标签时跳过该轴标签（默认 true）。 */
    var skipOverlappingHourLabel = true

    /**
     * 标签显示密度：1 = 每小时都显示，2 = 隔 2 小时，3 = 隔 3 小时。
     * 字体放大时自动降密度（PRD E14），但刻度线始终完整。
     */
    var labelStep = 1

    /** 分钟 → 内容区内的 y 偏移（px）。 */
    fun minuteToY(minute: Int): Int = topPadding + (minute / 60f * hourHeight).toInt()
}

/** 语义色项，供绘制方取色（§7.3 共 17 项，此处暴露绘制常用的子集）。 */
data class TimelineColors(
    @ColorInt val background: Int,
    @ColorInt val gridLine: Int,
    @ColorInt val axisLabel: Int,
    @ColorInt val now: Int,
    @ColorInt val blockBgPast: Int,
    @ColorInt val blockBgOngoing: Int,
    @ColorInt val blockBgUpcoming: Int,
    @ColorInt val blockTextPast: Int,
    @ColorInt val blockText: Int,
    @ColorInt val blockAccentPast: Int,
    @ColorInt val blockAccent: Int,
    /** §7.3「日程块描边色」；默认绘制方常规态不描边，此色留给自定义 [EventBlockPainter]。 */
    @ColorInt val blockStroke: Int,
    @ColorInt val selected: Int,
    @ColorInt val editLayerBg: Int,
    @ColorInt val editLayerText: Int,
    @ColorInt val editLayerTime: Int,
    @ColorInt val editHandle: Int,
) {
    /** 按三态取块背景色（§9.4 样式选择）。 */
    fun blockBackground(state: com.github.kevinvane.daytimeline.library.core.EventState): Int =
        when (state) {
            com.github.kevinvane.daytimeline.library.core.EventState.PAST -> blockBgPast
            com.github.kevinvane.daytimeline.library.core.EventState.ONGOING -> blockBgOngoing
            com.github.kevinvane.daytimeline.library.core.EventState.UPCOMING -> blockBgUpcoming
        }

    /** 按三态取块文字色。「已过」单独弱化。 */
    fun blockText(state: com.github.kevinvane.daytimeline.library.core.EventState): Int =
        if (state == com.github.kevinvane.daytimeline.library.core.EventState.PAST) blockTextPast
        else blockText

    /** 按三态取左侧色条色。 */
    fun blockAccentColor(state: com.github.kevinvane.daytimeline.library.core.EventState): Int =
        if (state == com.github.kevinvane.daytimeline.library.core.EventState.PAST) blockAccentPast
        else blockAccent
}

/**
 * 第二层定制：完全替换时间轴网格的绘制样式（刻度线、时间标签、当前时间线）。
 *
 * PRD §10.2 硬性要求：业务方**不得**需要修改或复制组件源码（D7），
 * 因此这类需求一律通过本扩展点实现。
 */
interface GridPainter {
    /** 绘制网格。 */
    fun paint(canvas: Canvas, context: GridContext, colors: TimelineColors, defaultPaints: Paints)

    /** 网格绘制方需要用到的、组件已预建好的 Paint。 */
    class Paints(
        val gridLine: Paint,
        val axisLabel: Paint,
        val nowLabel: Paint,
        /** 当前时间线（红线）；为 null 时默认绘制方不画线。 */
        val nowLine: Paint? = null,
        /** 当前时间线圆点；为 null 时默认绘制方不画点。 */
        val nowDot: Paint? = null,
    )
}

/**
 * 单个日程块的绘制上下文（提供给 [EventBlockPainter]）。
 *
 * 与 [GridContext] 一样是**可复用对象**：组件持有一份，每帧改写字段后复用。
 * 实现 [EventBlockPainter] 时不得把 `context` 长期持有（见 [GridContext] 的说明）。
 */
@Suppress("TooManyFunctions")
class BlockContext {
    lateinit var event: com.github.kevinvane.daytimeline.library.core.SanitizedEvent
    var state: com.github.kevinvane.daytimeline.library.core.EventState =
        com.github.kevinvane.daytimeline.library.core.EventState.UPCOMING

    /** 块左边缘 x（内容区坐标，已镜像）。 */
    var left = 0

    /** 块顶边 y（视口坐标，已减去滚动偏移）。 */
    var top = 0
    var width = 0
    var height = 0
    var corner = 0
    var paddingHorizontal = 0
    var paddingVertical = 0
    var accentBarWidth = 0
    /** 选中态描边粗细（默认绘制方仅在选中态描边，常规态不描边）。 */
    var strokeWidth = 0
    var selected = false

    /**
     * 业务色（ARGB），来自 [com.github.kevinvane.daytimeline.library.core.TimelineEvent.color]。
     *
     * null 表示使用组件默认块配色。**独立于 §7.3 的 17 项主题色项**——
     * 那 17 项的深浅适配由资源承担，业务方覆盖会破坏该机制（PRD §7.7.1）。
     *
     * 自定义绘制方可用它替代默认的强调色；不关心则可忽略。
     */
    var accentColor: Int? = null

    var timeFormat: TimeFormat = TimeFormat.SYSTEM

    /** 已格式化的起止时间文本；副标题与读屏描述可直接使用，无需自行处理 12/24 小时制。 */
    var startText: String = ""
    var endText: String = ""

    /** 是否显示时间副标题。 */
    var showTimeSubtitle = false

    /** 块高是否不足以容纳两行文字（极短日程）。 */
    var tooShortForText = false
}


/**
 * 第三层定制：完全替换日程块内部显示什么、怎么显示（PRD US11）。
 *
 * 未定制时使用组件默认实现。
 */
interface EventBlockPainter {
    /** 绘制单个日程块。 */
    fun paint(canvas: Canvas, context: BlockContext, colors: TimelineColors, defaultPaints: Paints)

    /** 默认实现需要的 Paint。 */
    class Paints(
        val background: Paint,
        val text: Paint,
        val accent: Paint,
        val stroke: Paint,
    )
}
