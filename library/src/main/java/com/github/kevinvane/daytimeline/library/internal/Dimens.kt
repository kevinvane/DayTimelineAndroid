package com.github.kevinvane.daytimeline.library.internal

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.github.kevinvane.daytimeline.library.R
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.paint.Theme

/**
 * 尺寸与颜色的解析结果（PRD AD-09：配置的单一来源）。
 *
 * 合并规则：**资源默认值** → 被 [config] 覆盖的项。
 * 因此「不配置也能用」（FC-006），且深浅色只由 `values` / `values-night`
 * 两份 colors.xml 决定，代码里没有任何 `isNightMode` 分支（§7.3 强制要求 ②）。
 *
 * 全部尺寸以 px 存储；dp/sp 在此一次性换算，之后绘制层不再做单位换算。
 */
// LongParameterList 豁免：字段数由 PRD §7.2「默认尺寸单一来源」逐条决定，
// 不是随手堆出来的。改用 Builder 只会把同一批字段搬到另一处，
// 且让「某个字段的缺省值到底从哪来」更难追溯——而这正是本类存在的意义。
// 豁免登记见 config/detekt/EXEMPTIONS.md
@Suppress("LongParameterList")
internal class Dimens private constructor(
    val hourHeight: Int,
    val hourHeightMin: Int,
    val topPadding: Int,
    val bottomPadding: Int,
    val axisWidth: Int,
    val endMargin: Int,
    val gridLineWidth: Int,
    val axisLabelSize: Int,
    val nowLabelSize: Int,
    val blockGap: Int,
    val blockCorner: Int,
    val blockPaddingHorizontal: Int,
    val blockPaddingVertical: Int,
    val blockMinHeight: Int,
    val blockAccentBarWidth: Int,
    val blockAccentBarMarginStart: Int,
    val blockAccentBarMarginVertical: Int,
    val blockStrokeWidth: Int,
    val blockTextSize: Int,
    val nowDotDiameter: Int,
    val nowLineWidth: Int,
    val handleVisualSize: Int,
    val handleTouchSize: Int,
    val minTouchTarget: Int,
    val edgeScrollTriggerSize: Int,
    val edgeScrollStepSize: Int,
    val firstLocateLeadIn: Int,
    val editStrokeWidth: Int,
    val snapMinutes: Int,
    val minDurationMinutes: Int,
    val maxDurationMinutes: Int,
    val defaultNewDurationMinutes: Int,
    val dragThresholdRatio: Float,
    val nowRefreshMillis: Long,
    val theme: Theme,
) {
    /**
     * 实际生效的每小时格高。
     *
     * 兜底到 [hourHeightMin]（PRD E17：格高被设成极小值时自动抬升，不崩溃）。
     * 值为 0 会让所有换算除零，因此这里必须兜底而不是信任配置。
     */
    val effectiveHourHeight: Int get() = hourHeight.coerceAtLeast(hourHeightMin)

    /** 全天内容总高度（§8.6 外部滚动模式的组件高度约定）。 */
    val contentHeight: Int
        get() = MinuteOfDay.HOURS_PER_DAY * effectiveHourHeight + topPadding + bottomPadding

    /** 视口高度能容纳的分钟数，用于命中测试与裁剪。 */
    fun minutesVisibleIn(viewportHeightPx: Int): Int {
        if (effectiveHourHeight <= 0) return 0
        return (viewportHeightPx / effectiveHourHeight * MinuteOfDay.MINUTES_PER_HOUR)
            .coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE)
    }

    companion object {
        /**
         * 拖拽触发阈值系数（PRD §7.2：系统标准阈值的 0.3 倍）。
         *
         * 为什么放代码常量而不是资源：**它是无量纲比值，不是尺寸**。
         * 曾用 `<item format="float" type="dimen">` 声明，导致 AAPT2 把它编译成
         * `TYPE_FLOAT(0x4)`，而 `Resources.getDimension()` 只接受 `TYPE_DIMENSION(0x5)`，
         * 真机上直接抛 `Resources$NotFoundException`（见 dimens.xml 内的警示注释）。
         *
         * 需要被业务方覆盖时走 `<attr format="float">` + `TypedArray.getFloat()`，
         * 那是 API 1 起就支持的安全路径。
         */
        const val DEFAULT_DRAG_THRESHOLD_RATIO = 0.3f

        /**
         * 拖拽阈值系数允许的配置范围。
         *
         * 下限保证「位移小于阈值」在任何配置下都不会被误判为拖拽；
         * 上限保证系数不会大到让轻微抖动就被当成拖拽（§8.2 的手感契约）。
         */
        const val DRAG_THRESHOLD_RATIO_MIN = 0.05f
        const val DRAG_THRESHOLD_RATIO_MAX = 1f

        // LongMethod 豁免：与 `ConfigFromAttrs.read` 同形——刻意写成一段**长而平的**
        // 读取序列，每个尺寸一行「配置值 ?: 资源默认值」。拆成小函数会把
        // 「每个尺寸到底从哪来」的对照关系打散，那才是真正更难维护的形态。
        @Suppress("LongMethod")
        fun resolve(context: Context, config: TimelineConfig): Dimens {
            val res = context.resources

            /**
             * 读取尺寸资源，返回 **px**。
             *
             * 注意这里**不能**再套一层 `TypedValue.applyDimension`：
             * `Resources.getDimension()` 返回的已经是按资源自身单位换算好的 px，
             * 再按 DIP 换算会把 density 乘第二次（12sp 在 density=3 的设备上会变成
             * 108px 而非 36px，字号放大三倍）。
             *
             * 单位由资源声明本身决定（`12dp` 或 `12sp`），代码不需要也不应该区分。
             */
            fun dim(id: Int): Int = res.getDimension(id).toInt()

            fun pick(configured: Int?, resource: Int) = configured ?: dim(resource)

            @ColorInt
            fun color(configured: Int?, resource: Int): Int =
                configured ?: ContextCompat.getColor(context, resource)

            return Dimens(
                hourHeight = pick(config.hourHeight, R.dimen.day_timeline_hour_height),
                hourHeightMin = dim(R.dimen.day_timeline_hour_height_min),

                topPadding = pick(config.topPadding, R.dimen.day_timeline_top_padding),
                bottomPadding = pick(config.bottomPadding, R.dimen.day_timeline_bottom_padding),
                axisWidth = pick(config.axisWidth, R.dimen.day_timeline_axis_width),
                endMargin = pick(config.endMargin, R.dimen.day_timeline_end_margin),
                gridLineWidth = pick(config.gridLineWidth, R.dimen.day_timeline_grid_line_width),
                axisLabelSize = pick(config.axisLabelSize, R.dimen.day_timeline_axis_label_size),
                nowLabelSize = pick(config.nowLabelSize, R.dimen.day_timeline_now_label_size),
                blockGap = pick(config.blockGap, R.dimen.day_timeline_block_gap),
                blockCorner = pick(config.blockCorner, R.dimen.day_timeline_block_corner),
                blockPaddingHorizontal = pick(
                    config.blockPaddingHorizontal,
                    R.dimen.day_timeline_block_padding_horizontal,
                ),
                blockPaddingVertical = pick(
                    config.blockPaddingVertical,
                    R.dimen.day_timeline_block_padding_vertical,
                ),
                blockMinHeight = pick(config.blockMinHeight, R.dimen.day_timeline_block_min_height),
blockAccentBarWidth = pick(
                    config.blockAccentBarWidth,
                    R.dimen.day_timeline_block_accent_bar,
                ),
                // 内缩量不得为负：负值会让色条越到块外侧，是与 D4 同类的越界绘制
                blockAccentBarMarginStart = pick(
                    config.blockAccentBarMarginStart,
                    R.dimen.day_timeline_block_accent_bar_margin_start,
                ).coerceAtLeast(0),
                blockAccentBarMarginVertical = pick(
                    config.blockAccentBarMarginVertical,
                    R.dimen.day_timeline_block_accent_bar_margin_vertical,
                ).coerceAtLeast(0),
                blockStrokeWidth = pick(
                    config.blockStrokeWidth,
                    R.dimen.day_timeline_block_stroke_width,
                ),
                blockTextSize = pick(
                    config.blockTextSize,
                    R.dimen.day_timeline_block_text_size,
                ),
                nowDotDiameter = pick(config.nowDotDiameter, R.dimen.day_timeline_now_dot_diameter),
                nowLineWidth = pick(config.nowLineWidth, R.dimen.day_timeline_now_line_width),
                handleVisualSize = pick(
                    config.editHandleVisualSize,
                    R.dimen.day_timeline_handle_visual,
                ),
                // 热区不得小于最小触摸目标（UF-001）：配置只能调大，不能调小
                handleTouchSize = maxOf(
                    pick(config.editHandleTouchSize, R.dimen.day_timeline_handle_touch),
                    pick(config.minTouchTarget, R.dimen.day_timeline_min_touch_target),
                ),
                minTouchTarget = pick(config.minTouchTarget, R.dimen.day_timeline_min_touch_target),
                edgeScrollTriggerSize = pick(
                    config.edgeScrollTriggerSize,
                    R.dimen.day_timeline_edge_scroll_trigger,
                ),
                edgeScrollStepSize = pick(
                    config.edgeScrollStepSize,
                    R.dimen.day_timeline_edge_scroll_step,
                ),
                firstLocateLeadIn = dim(R.dimen.day_timeline_first_locate_lead_in),
                editStrokeWidth = dim(R.dimen.day_timeline_edit_stroke_width),
                snapMinutes = config.resolvedSnapMinutes(
                    res.getInteger(R.integer.day_timeline_snap_minutes),
                ),
                minDurationMinutes = config.resolvedMinDuration(
                    res.getInteger(R.integer.day_timeline_min_duration_minutes),
                ),
                maxDurationMinutes = config.resolvedMaxDuration(
                    res.getInteger(R.integer.day_timeline_max_duration_minutes),
                ),
                defaultNewDurationMinutes = (
                    config.defaultNewDurationMinutes
                        ?: res.getInteger(R.integer.day_timeline_default_new_duration_minutes)
                    ).coerceAtLeast(1),
                dragThresholdRatio = (config.dragThresholdRatio ?: DEFAULT_DRAG_THRESHOLD_RATIO)
                    .coerceIn(DRAG_THRESHOLD_RATIO_MIN, DRAG_THRESHOLD_RATIO_MAX),
                nowRefreshMillis = res.getInteger(
                    R.integer.day_timeline_now_refresh_seconds,
                ) * 1000L,
                theme = Theme.resolve(
                    background = color(config.colorBackground, R.color.day_timeline_background),
                    gridLine = color(config.colorGridLine, R.color.day_timeline_grid_line),
                    axisLabel = color(config.colorAxisLabel, R.color.day_timeline_axis_label),
                    now = color(config.colorNow, R.color.day_timeline_now),
                    blockBgPast = color(
                        config.colorBlockBgPast, R.color.day_timeline_block_bg_past,
                    ),
                    blockBgOngoing = color(
                        config.colorBlockBgOngoing, R.color.day_timeline_block_bg_ongoing,
                    ),
                    blockBgUpcoming = color(
                        config.colorBlockBgUpcoming, R.color.day_timeline_block_bg_upcoming,
                    ),
                    blockTextPast = color(
                        config.colorBlockTextPast, R.color.day_timeline_block_text_past,
                    ),
                    blockText = color(config.colorBlockText, R.color.day_timeline_block_text),
                    blockAccentPast = color(
                        config.colorBlockAccentPast, R.color.day_timeline_block_accent_past,
                    ),
                    blockAccent = color(
                        config.colorBlockAccent, R.color.day_timeline_block_accent,
                    ),
                    blockStroke = color(
                        config.colorBlockStroke, R.color.day_timeline_block_stroke,
                    ),
                    selected = color(config.colorSelected, R.color.day_timeline_selected),
                    editLayerBg = color(
                        config.colorEditLayerBg, R.color.day_timeline_edit_layer_bg,
                    ),
                    editLayerText = color(
                        config.colorEditLayerText, R.color.day_timeline_edit_layer_text,
                    ),
                    editLayerTime = color(
                        config.colorEditLayerTime, R.color.day_timeline_edit_layer_time,
                    ),
                    editHandle = color(config.colorEditHandle, R.color.day_timeline_edit_handle),
                ),
            )
        }
    }
}


/** 读取当前是否深色模式。**仅供读取/上报使用，不得据此做配色分支**（§7.3 ②）。 */
internal fun Context.isNightMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
