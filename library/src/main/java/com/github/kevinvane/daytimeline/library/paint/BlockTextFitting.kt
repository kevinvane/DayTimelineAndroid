package com.github.kevinvane.daytimeline.library.paint

import android.graphics.Paint

/**
 * 日程块文字的「放得下几行」判定。
 *
 * 依据接入方文档与 PRD §7.2「低于最小显示高度无法显示内容」的落地口径：
 * **块高不足以显示块内要画的文字行数时，文字整体降级为只读屏**（不绘制），
 * 否则排到块外的行就会溢出块背景。
 *
 * 为什么不放进 `core/Geometry`：这里必须读 Android `Paint` 的 `fontMetrics`
 * （行高由字号 / 字族决定），而 `core/` 不得 `import android.*`（AD-02）。
 * 判据与绘制共用同一支文字 `Paint`，避免两处各算一套、日后漂移。
 *
 * ## 判据口径（真机实测的取舍）
 *
 * 用「**最后一行的基线是否落在块内**」：块内文字自 `paddingVertical` 起排，
 * 第 n 行基线 = `paddingVertical - ascent + (n-1) * 行距`。基线落在块底之上即认为放得下，
 * **不再额外为 descender 留白**——Pixel / 64dp 格高实测：30 分钟的块（84px）第二行基线
 * 正好落在块内、视觉完好；若把整段 descender 也算进去会把它误判为放不下。20 分钟的块
 * （56px）第二行基线已越过块底，副标题确实画到了块外。
 */
internal object BlockTextFitting {

    /** 副标题行距倍数：设计定稿副标题行高 16sp ÷ 块文字字号 12sp（M0-3）。 */
    const val SUBTITLE_LINE_SPACING = 16f / 12f

    /**
     * 块内绘制 [lineCount] 行文字所需的**最小块高**（像素）。
     *
     * [lineCount] 小于 2 时不加行距，即只保证首行基线落在块内。
     */
    fun requiredHeightPx(textPaint: Paint, lineCount: Int, paddingVerticalPx: Int): Int {
        val fm = textPaint.fontMetrics
        val firstBaseline = paddingVerticalPx - fm.ascent
        val extra = if (lineCount >= 2) textPaint.textSize * SUBTITLE_LINE_SPACING else 0f
        return (firstBaseline + extra).toInt()
    }
}
