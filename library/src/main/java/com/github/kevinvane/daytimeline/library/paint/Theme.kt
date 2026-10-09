package com.github.kevinvane.daytimeline.library.paint

import android.graphics.Paint
import androidx.annotation.ColorInt
import com.github.kevinvane.daytimeline.library.core.EventState

/**
 * 17 项语义色项的解析结果（PRD §7.3）。
 *
 * 本类**只做「已解析的色值 → 按状态取色」的映射**，不含任何深浅色判断。
 * 深色适配完全由 `values/colors.xml` 与 `values-night/colors.xml` 两份资源承担，
 * 组件代码里不存在 `isNightMode` 分支——这是 §7.3 强制要求 ②。
 */
internal data class Theme(
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
    @ColorInt val blockStroke: Int,
    @ColorInt val selected: Int,
    @ColorInt val editLayerBg: Int,
    @ColorInt val editLayerText: Int,
    @ColorInt val editLayerTime: Int,
    @ColorInt val editHandle: Int,
) {
    /** 按三态取块背景色。 */
    fun blockBackground(state: EventState): Int = when (state) {
        EventState.PAST -> blockBgPast
        EventState.ONGOING -> blockBgOngoing
        EventState.UPCOMING -> blockBgUpcoming
    }

    /** 按三态取块文字色。「已过」三色同时弱化（§9.4 样式选择）。 */
    fun blockText(state: EventState): Int = when (state) {
        EventState.PAST -> blockTextPast
        EventState.ONGOING, EventState.UPCOMING -> blockText
    }

    /** 按三态取左侧色条色。 */
    fun blockAccent(state: EventState): Int = when (state) {
        EventState.PAST -> blockAccentPast
        EventState.ONGOING, EventState.UPCOMING -> blockAccent
    }

    /**
     * 预建绘制对象，避免 `onDraw` 中反复 `new Paint`（§12.1 性能要求）。
     *
     * 这些 Paint **不是**语义色项的存放处——色值仍由本 Theme 持有，
     * Paint 只是承载者。深色切换时由调用方重建整套 Paint。
     */
    class Paints(dimens: DimensForPaints) {
        val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dimens.gridLineWidth.toFloat()
        }
        val axisLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            textSize = dimens.axisLabelSize.toFloat()
        }
        val nowLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dimens.nowLabelSize.toFloat()
        }
        val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val nowDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val blockBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        /**
         * 日程块内容文字。
         *
         * **必须显式设置 textSize**：`Paint` 的默认值是 12 个**原始像素**，
         * 不是 12sp。漏设会让块内文字在 density=3 的设备上只有 12px 高，
         * 看上去几乎看不见（真机上出现过的现象）。
         */
        val blockText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dimens.blockTextSize.toFloat()
        }
        val blockAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val blockStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dimens.editStrokeWidth.toFloat()
        }
        val editLayer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val editHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }

    /** [Paints] 构造所需的尺寸子集。 */
    internal data class DimensForPaints(
        val gridLineWidth: Int,
        val axisLabelSize: Int,
        val nowLabelSize: Int,
        val blockTextSize: Int,
        val editStrokeWidth: Int,
    )

    companion object {
        // LongParameterList 豁免：17 项语义色项由 PRD §7.3 逐条列举，
        // 参数数与规格条数一一对应是**刻意的**——少一个就意味着漏了一条规格。
        // 豁免登记见 config/detekt/EXEMPTIONS.md
        @Suppress("LongParameterList")
        fun resolve(
            @ColorInt background: Int,
            @ColorInt gridLine: Int,
            @ColorInt axisLabel: Int,
            @ColorInt now: Int,
            @ColorInt blockBgPast: Int,
            @ColorInt blockBgOngoing: Int,
            @ColorInt blockBgUpcoming: Int,
            @ColorInt blockTextPast: Int,
            @ColorInt blockText: Int,
            @ColorInt blockAccentPast: Int,
            @ColorInt blockAccent: Int,
            @ColorInt blockStroke: Int,
            @ColorInt selected: Int,
            @ColorInt editLayerBg: Int,
            @ColorInt editLayerText: Int,
            @ColorInt editLayerTime: Int,
            @ColorInt editHandle: Int,
        ): Theme = Theme(
            background, gridLine, axisLabel, now,
            blockBgPast, blockBgOngoing, blockBgUpcoming,
            blockTextPast, blockText, blockAccentPast, blockAccent, blockStroke,
            selected, editLayerBg, editLayerText, editLayerTime, editHandle,
        )
    }
}
