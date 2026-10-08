package com.github.kevinvane.daytimeline.library.internal

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.annotation.StyleableRes
import com.github.kevinvane.daytimeline.library.R
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimeFormat
import com.github.kevinvane.daytimeline.library.api.TimelineConfig

/**
 * XML 配置 → [TimelineConfig]（PRD §10.1 / FC-005「界面配置」侧）。
 *
 * ## 与代码配置的关系
 *
 * [TimelineConfig] 的每个可空字段与 `attrs.xml` 中的属性**一一对应**，两侧效果完全一致：
 * - 业务方写 `app:dtHourHeight="80dp"` → 这里填 `hourHeight`，单位已换算为 px
 * - 业务方调 `setConfig(TimelineConfig(hourHeight = …))` → 同一字段
 *
 * ## 关键约定：`hasValue` 决定「是否被覆盖」
 *
 * 未在 XML 中书写的属性必须保持 `null`，而不是落成 `0` 或 `false`——
 * 因为 `null` 才表示「用资源默认值」，`0` 会被当成业务方显式要求取 0。
 * 因此每个字段都用 `hasValue()` 把关，**不要**改成直接 `getXxx`。
 *
 * ## 未映射的属性
 *
 * `dtShowEditActions` / `dtShowDeleteAction` 已在 `attrs.xml` 中移除：
 * 内置「完成/取消/删除」按钮尚未实现（PRD §7.7），声明一个不生效的属性
 * 只会让业务方误以为配了有用。实现后再一并加回。
 */
internal object ConfigFromAttrs {

    fun read(context: Context, attrs: AttributeSet?): TimelineConfig {
        if (attrs == null) return TimelineConfig()
        val ta = context.obtainStyledAttributes(attrs, R.styleable.DayTimelineView)
        return try {
            TimelineConfig(
                hourHeight = dimOrNull(ta, R.styleable.DayTimelineView_dtHourHeight),
                axisWidth = dimOrNull(ta, R.styleable.DayTimelineView_dtAxisWidth),
                topPadding = dimOrNull(ta, R.styleable.DayTimelineView_dtTopPadding),
                bottomPadding = dimOrNull(ta, R.styleable.DayTimelineView_dtBottomPadding),
                endMargin = dimOrNull(ta, R.styleable.DayTimelineView_dtEndMargin),
                gridLineWidth = dimOrNull(ta, R.styleable.DayTimelineView_dtGridLineWidth),
                axisLabelSize = dimOrNull(ta, R.styleable.DayTimelineView_dtAxisLabelSize),
                nowLabelSize = dimOrNull(ta, R.styleable.DayTimelineView_dtNowLabelSize),

                blockGap = dimOrNull(ta, R.styleable.DayTimelineView_dtBlockGap),
                blockCorner = dimOrNull(ta, R.styleable.DayTimelineView_dtBlockCorner),
                blockPaddingHorizontal = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtBlockPaddingHorizontal,
                ),
                blockPaddingVertical = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtBlockPaddingVertical,
                ),
                blockMinHeight = dimOrNull(ta, R.styleable.DayTimelineView_dtBlockMinHeight),
                blockAccentBarWidth = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtBlockAccentBarWidth,
                ),
                blockStrokeWidth = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtBlockStrokeWidth,
                ),
                showTimeSubtitle = boolOrNull(ta, R.styleable.DayTimelineView_dtShowTimeSubtitle),

                showNowIndicator = boolOrNull(ta, 
                    R.styleable.DayTimelineView_dtShowNowIndicator,
                ),
                nowIndicatorTodayOnly = boolOrNull(ta, 
                    R.styleable.DayTimelineView_dtNowIndicatorTodayOnly,
                ),
                nowDotDiameter = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtNowDotDiameter,
                ),
                nowLineWidth = dimOrNull(ta, R.styleable.DayTimelineView_dtNowLineWidth),

                timeFormat = enumOrNull(ta, 
                    R.styleable.DayTimelineView_dtTimeFormat,
                    arrayOf(TimeFormat.SYSTEM, TimeFormat.H24, TimeFormat.H12),
                ),
                snapMinutes = intOrNull(ta, R.styleable.DayTimelineView_dtSnapMinutes),
                minDurationMinutes = intOrNull(ta, 
                    R.styleable.DayTimelineView_dtMinDurationMinutes,
                ),
                maxDurationMinutes = intOrNull(ta, 
                    R.styleable.DayTimelineView_dtMaxDurationMinutes,
                ),
                defaultNewDurationMinutes = intOrNull(ta, 
                    R.styleable.DayTimelineView_dtDefaultNewDurationMinutes,
                ),

                scrollMode = enumOrNull(ta, 
                    R.styleable.DayTimelineView_dtScrollMode,
                    arrayOf(ScrollMode.SELF, ScrollMode.EXTERNAL),
                ),
                edgeAutoScrollEnabled = boolOrNull(ta, 
                    R.styleable.DayTimelineView_dtEdgeAutoScrollEnabled,
                ),
                edgeScrollTriggerSize = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtEdgeScrollTriggerSize,
                ),
                edgeScrollStepSize = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtEdgeScrollStepSize,
                ),
                dragThresholdRatio = floatOrNull(ta, 
                    R.styleable.DayTimelineView_dtDragThresholdRatio,
                ),

                editHandleVisualSize = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtEditHandleVisualSize,
                ),
                editHandleTouchSize = dimOrNull(ta, 
                    R.styleable.DayTimelineView_dtEditHandleTouchSize,
                ),
                minTouchTarget = dimOrNull(ta, R.styleable.DayTimelineView_dtMinTouchTarget),

                colorBackground = colorOrNull(ta, R.styleable.DayTimelineView_dtColorBackground),
                colorGridLine = colorOrNull(ta, R.styleable.DayTimelineView_dtColorGridLine),
                colorAxisLabel = colorOrNull(ta, R.styleable.DayTimelineView_dtColorAxisLabel),
                colorNow = colorOrNull(ta, R.styleable.DayTimelineView_dtColorNow),
                colorBlockBgPast = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockBgPast,
                ),
                colorBlockBgOngoing = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockBgOngoing,
                ),
                colorBlockBgUpcoming = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockBgUpcoming,
                ),
                colorBlockTextPast = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockTextPast,
                ),
                colorBlockText = colorOrNull(ta, R.styleable.DayTimelineView_dtColorBlockText),
                colorBlockAccentPast = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockAccentPast,
                ),
                colorBlockAccent = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockAccent,
                ),
                colorBlockStroke = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorBlockStroke,
                ),
                colorSelected = colorOrNull(ta, R.styleable.DayTimelineView_dtColorSelected),
                colorEditLayerBg = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorEditLayerBg,
                ),
                colorEditLayerText = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorEditLayerText,
                ),
                colorEditLayerTime = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorEditLayerTime,
                ),
                colorEditHandle = colorOrNull(ta, 
                    R.styleable.DayTimelineView_dtColorEditHandle,
                ),
            )
        } finally {
            ta.recycle()
        }
    }

    // ---- 读取辅助：全部以 hasValue 把关，保证「未配置 == null」 ----
    //
    // 刻意写成接收 ta 的普通函数而非 TypedArray 的扩展函数：
    // 扩展形式在本工程的解析下对 getBoolean/getDimensionPixelSize 产生了
    // "No value passed for parameter 'p1'" 的诡异错误（Kotlin 对合成参数的报错），
    // 普通函数没有这个问题，可读性也更好。

    private fun dimOrNull(ta: TypedArray, i: Int): Int? =
        if (ta.hasValue(i)) ta.getDimensionPixelSize(i, 0) else null

    private fun boolOrNull(ta: TypedArray, i: Int): Boolean? =
        if (ta.hasValue(i)) ta.getBoolean(i, false) else null

    private fun intOrNull(ta: TypedArray, i: Int): Int? =
        if (ta.hasValue(i)) ta.getInt(i, 0) else null

    private fun floatOrNull(ta: TypedArray, i: Int): Float? =
        if (ta.hasValue(i)) ta.getFloat(i, 0f) else null

    private fun colorOrNull(ta: TypedArray, i: Int): Int? =
        if (ta.hasValue(i)) ta.getColor(i, 0) else null

    /** enum 属性按下标映射到枚举；未配置时返回 null（= 用默认）。 */
    private fun <T : Enum<T>> enumOrNull(ta: TypedArray, i: Int, values: Array<T>): T? =
        if (ta.hasValue(i)) values.getOrNull(ta.getInt(i, 0)) else null
}
