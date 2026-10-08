package com.github.kevinvane.daytimeline.library.paint

import android.graphics.Canvas
import android.graphics.Paint
import com.github.kevinvane.daytimeline.library.api.BlockContext
import com.github.kevinvane.daytimeline.library.api.EventBlockPainter
import com.github.kevinvane.daytimeline.library.api.GridContext
import com.github.kevinvane.daytimeline.library.api.GridPainter
import com.github.kevinvane.daytimeline.library.api.TimelineColors
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay

/**
 * 网格默认绘制（25 条刻度线 + 25 个时间标签 + 当前时间线）。
 *
 * 25 而非 24：00:00 到 24:00 共 25 条分隔线（§1.4 术语约定）。
 * 标签密度按 [GridContext.labelStep] 降密度，但**刻度线始终完整**（PRD E14）。
 */
internal object DefaultGridPainter : GridPainter {

    override fun paint(
        canvas: Canvas,
        context: GridContext,
        colors: TimelineColors,
        defaultPaints: GridPainter.Paints,
    ) {
        // 背景：必须是纯色，§7.5 R3 依赖此前提（用当前时间线标签遮挡背景时）
        canvas.drawColor(colors.background)

        defaultPaints.gridLine.color = colors.gridLine
        defaultPaints.gridLine.strokeWidth = context.gridLineWidth.toFloat()

        defaultPaints.axisLabel.color = colors.axisLabel
        defaultPaints.nowLabel.color = colors.now

        val baselineOffset = context.topPadding
        val lastMinute = MinuteOfDay.END_OF_DAY_MINUTE
        val step = context.labelStep.coerceAtLeast(1)

        for (hour in 0..MinuteOfDay.HOURS_PER_DAY) {
            val y = context.minuteToY(hour * MinuteOfDay.MINUTES_PER_HOUR)
            // 刻度线：25 条，始终完整，不因字体放大而省略
            canvas.drawLine(
                context.axisAreaEnd.toFloat(),
                y.toFloat(),
                context.contentEnd.toFloat(),
                y.toFloat(),
                defaultPaints.gridLine,
            )

            // 时间标签：按 labelStep 降密度（PRD E14）
            if (hour % step == 0) {
                val textY: Float = if (hour == MinuteOfDay.HOURS_PER_DAY) {
                    // 24:00 的标签落在最后一条线下方，否则会贴到屏幕外
                    y + baselineOffset / 2f
                } else {
                    // 轴标签与整点刻度线垂直居中对齐
                    y - (defaultPaints.axisLabel.fontMetrics.ascent + defaultPaints.axisLabel.fontMetrics.descent) / 2
                }
                // 轴标签文字带与红字/红线文字带重叠时，跳过该轴标签，红字覆盖之（§7.6）
                if (!(context.skipOverlappingHourLabel && overlapsNowLabel(textY, y, context, defaultPaints))) {
                    canvas.drawText(
                        formatHour(hour),
                        (context.axisAreaEnd - context.gridLineWidth).toFloat(),
                        textY.toFloat(),
                        defaultPaints.axisLabel,
                    )
                }
            }
        }

        if (context.showNowIndicator) {
            val y = context.minuteToY(context.nowMinute)
            defaultPaints.nowLabel.color = colors.now
            defaultPaints.nowLabel.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                formatNow(context.nowMinute),
                (context.axisAreaEnd - context.gridLineWidth).toFloat(),
                // 红字必须与红线+圆点同一「时刻」对齐——文字垂直居中于红线 y
                y - (defaultPaints.nowLabel.fontMetrics.ascent + defaultPaints.nowLabel.fontMetrics.descent) / 2f,
                defaultPaints.nowLabel,
            )
        }
    }

    /**
     * 当前时间线前景（圆点 + 2dp 红线）。§7.6：
     * 必须画在日程块**之上**，否则会被块背景盖住。
     * 由 [DayTimelineView] 在块层之后调用，网格层 [paint] 只画时间文字。
     */
    fun paintNowForeground(
        canvas: Canvas,
        context: GridContext,
        colors: TimelineColors,
        defaultPaints: GridPainter.Paints,
    ) {
        if (!context.showNowIndicator) return
        val y = context.minuteToY(context.nowMinute)
        val line = defaultPaints.nowLine
        if (line != null) {
            line.color = colors.now
            line.strokeWidth = context.nowLineWidth.toFloat()
            canvas.drawLine(
                context.axisAreaEnd.toFloat(), y.toFloat(),
                context.contentEnd.toFloat(), y.toFloat(), line,
            )
        }
        val dot = defaultPaints.nowDot
        if (dot != null) {
            dot.color = colors.now
            canvas.drawCircle(
                context.axisAreaEnd.toFloat(), y.toFloat(),
                context.nowDotDiameter / 2f, dot,
            )
        }
    }

    /** 某轴标签基线 textY 是否与红字文字带重叠。 */
    private fun overlapsNowLabel(
        textY: Float,
        gridlineY: Int,
        context: GridContext,
        defaultPaints: GridPainter.Paints,
    ): Boolean {
        if (!context.showNowIndicator) return false
        val aFm = defaultPaints.axisLabel.fontMetrics
        val nFm = defaultPaints.nowLabel.fontMetrics
        val axisTop = textY + aFm.ascent
        val axisBottom = textY + aFm.descent
        val redY = context.minuteToY(context.nowMinute)
        val nowBaseline = redY - (nFm.ascent + nFm.descent) / 2f
        val nowTop = nowBaseline + nFm.ascent
        val nowBottom = nowBaseline + nFm.descent
        return axisTop < nowBottom && nowTop < axisBottom
    }

    /** 网格内的时间文字统一走 24 小时制；12 小时制由日程块副标题体现（FC-004）。 */
    private fun formatHour(hour: Int): String {
        val h = hour % MinuteOfDay.HOURS_PER_DAY
        return if (h < 10) "0$h:00" else "$h:00"
    }

    /** 当前时间文字：「几点几分」（§7.6），不能只显示整点——15:49 显示成 15:00 会误导。 */
    private fun formatNow(minuteOfDay: Int): String {
        val h = minuteOfDay / MinuteOfDay.MINUTES_PER_HOUR
        val m = minuteOfDay % MinuteOfDay.MINUTES_PER_HOUR
        return "${if (h < 10) "0$h" else h}:${if (m < 10) "0$m" else m}"
    }
}

/**
 * 日程块默认绘制：底色 + 左侧色条 + 描边 + 内容 + 可选时间副标题。
 *
 * 三态样式由 [TimelineColors] 按状态取色实现（§9.4 样式选择），
 * 本类不含任何深浅色分支。
 */
internal object DefaultEventBlockPainter : EventBlockPainter {

    override fun paint(
        canvas: Canvas,
        context: BlockContext,
        colors: TimelineColors,
        defaultPaints: EventBlockPainter.Paints,
    ) {
        val radius = context.corner.toFloat()
        val left = context.left.toFloat()
        val top = context.top.toFloat()
        val right = (context.left + context.width).toFloat()
        val bottom = (context.top + context.height).toFloat()

        defaultPaints.background.color = colors.blockBackground(context.state)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, defaultPaints.background)

        // 左侧色条：已过/进行中/未到三色随状态切换
        if (context.accentBarWidth > 0) {
            defaultPaints.accent.color = colors.blockAccentColor(context.state)
            canvas.drawRoundRect(
                left,
                top,
                left + context.accentBarWidth,
                bottom,
                radius,
                radius,
                defaultPaints.accent,
            )
        }

        // 描边：选中态用强调色加粗，否则用常规描边色
        if (context.selected) {
            defaultPaints.stroke.color = colors.selected
            defaultPaints.stroke.strokeWidth = context.strokeWidth.toFloat()
        } else {
            defaultPaints.stroke.color = colors.blockStroke
            defaultPaints.stroke.strokeWidth = context.strokeWidth.toFloat()
        }
        if (defaultPaints.stroke.strokeWidth > 0f) {
            val half = defaultPaints.stroke.strokeWidth / 2f
            canvas.drawRoundRect(
                left + half, top + half, right - half, bottom - half,
                radius, radius, defaultPaints.stroke,
            )
        }

        // 文字：极短日程放不下时直接跳过，避免文字溢出（D4 / E14）
        if (context.tooShortForText) return

        val textLeft = left + context.accentBarWidth + context.paddingHorizontal
        val maxTextWidth = right - textLeft - context.paddingHorizontal
        if (maxTextWidth <= 0) return

        val content = context.event.content
        defaultPaints.text.color = colors.blockText(context.state)

        val topPaddingPx = context.paddingVertical
        val contentTop = top + topPaddingPx
        val baseline = contentTop - defaultPaints.text.fontMetrics.ascent

        if (context.showTimeSubtitle) {
            val subtitle = ellipsize("${context.startText} - ${context.endText}", maxTextWidth, defaultPaints.text)
            canvas.drawText(subtitle, textLeft, baseline, defaultPaints.text)
            canvas.drawText(
                ellipsize(content?.toString() ?: "", maxTextWidth, defaultPaints.text),
                textLeft,
                baseline + defaultPaints.text.textSize * 1.2f,
                defaultPaints.text,
            )
        } else {
            canvas.drawText(
                ellipsize(content?.toString() ?: "", maxTextWidth, defaultPaints.text),
                textLeft,
                baseline,
                defaultPaints.text,
            )
        }
    }

    /**
     * 按可用宽度截断文本并追加省略号（PRD §7.4「超出以省略号截断」）。
     *
     * 窄列（重叠分栏后可能只有 1/3 宽）下不截断会让文字直接压在相邻块上，
     * 是最容易被一眼看出的绘制缺陷。用 `measureText` 二分查找，单次 O(log n) 次测量。
     */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val budget = maxWidth - paint.measureText(ellipsis)
        if (budget <= 0f) return ""
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (paint.measureText(text.substring(0, mid)) <= budget) low = mid else high = mid - 1
        }
        return text.substring(0, low) + ellipsis
    }
}
