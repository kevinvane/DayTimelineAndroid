package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay

/** 时间显示格式（PRD FC-004 / E15）。 */
enum class TimeFormat {
    /** 跟随系统语言与地区设置。 */
    SYSTEM,

    /** 强制 24 小时制。 */
    H24,

    /** 强制 12 小时制。 */
    H12,
}

/** 滚动模式（PRD FI-001 / FI-002 / §8.6）。 */
enum class ScrollMode {
    /**
     * 组件自身滚动（默认）。组件高度填满父容器，由组件负责滚动与状态恢复。
     */
    SELF,

    /**
     * 外部容器滚动。组件高度必须等于「全天内容高度」，
     * 即 `24 × 每小时格高 + 顶部留白 + 底部留白`，否则内容会被截断或留白过多。
     *
     * 此模式下组件**不消费垂直手势**，滚动位置恢复由外层容器负责。
     */
    EXTERNAL,
}

/**
 * 组件参数配置（PRD §10.1 第一层定制 / FC-005「代码配置」侧）。
 *
 * 与 `attrs.xml` 中的界面配置项**一一对应**：改了一侧请同步另一侧，
 * 两侧效果必须完全一致。
 *
 * 所有字段都有默认值，取自 PRD §7.2（尺寸的唯一定义处），
 * 业务方不配置任何内容也能得到合理外观（FC-006）。
 *
 * 本类不含尺寸与颜色的**取值**，只含「是否覆盖」的意图；实际取值由
 * `Dimens` / `Theme` 合并「资源默认值 + 本配置」后得出，
 * 这样才能满足 §7.3 的强制要求：深色适配只靠替换资源，不写分支逻辑。
 */
data class TimelineConfig(

    // ---- 布局尺寸 ----
    /** 每小时格高，`null` 表示用资源默认值。 */
    val hourHeight: Int? = null,
    val axisWidth: Int? = null,
    val topPadding: Int? = null,
    val bottomPadding: Int? = null,
    val endMargin: Int? = null,
    val gridLineWidth: Int? = null,
    val axisLabelSize: Int? = null,
    /** 与 [axisLabelSize] 是两个独立配置项（§7.2 注）。 */
    val nowLabelSize: Int? = null,

    // ---- 日程块外观 ----
    val blockGap: Int? = null,
    val blockCorner: Int? = null,
    val blockPaddingHorizontal: Int? = null,
    val blockPaddingVertical: Int? = null,
    val blockMinHeight: Int? = null,
    val blockAccentBarWidth: Int? = null,
    val blockStrokeWidth: Int? = null,
    val showTimeSubtitle: Boolean? = null,

    // ---- 当前时间线 ----
    val showNowIndicator: Boolean? = null,
    val nowIndicatorTodayOnly: Boolean? = null,
    val nowDotDiameter: Int? = null,
    val nowLineWidth: Int? = null,
    val autoLocateOnFirstShow: Boolean? = null,

    // ---- 时间行为 ----
    val timeFormat: TimeFormat? = null,
    /** 吸附步长（分钟），0 关闭吸附但保留时长兜底。 */
    val snapMinutes: Int? = null,
    val minDurationMinutes: Int? = null,
    val maxDurationMinutes: Int? = null,
    val defaultNewDurationMinutes: Int? = null,

    // ---- 滚动行为 ----
    val scrollMode: ScrollMode? = null,
    val edgeAutoScrollEnabled: Boolean? = null,
    val edgeScrollTriggerSize: Int? = null,
    val edgeScrollStepSize: Int? = null,
    /** 拖拽触发阈值 = 系统标准阈值 × 该系数。 */
    val dragThresholdRatio: Float? = null,

    // ---- 编辑交互 ----
    // 注：内置「完成/取消/删除」按钮尚未实现，故 showEditActions / showDeleteAction
    // 暂时不存在，避免出现声明了却无人消费的字段（违反 T5 死代码 0 处）。
    // 实现按钮时连同 attrs.xml 中的 dtShowEditActions / dtShowDeleteAction 一并加回。
    val editHandleVisualSize: Int? = null,
    /** 触摸热区。业务方只应调大不应调小（UF-001 下限 48dp）。 */
    val editHandleTouchSize: Int? = null,
    val minTouchTarget: Int? = null,

    // ---- 颜色（17 项语义色项）----
    val colorBackground: Int? = null,
    val colorGridLine: Int? = null,
    val colorAxisLabel: Int? = null,
    val colorNow: Int? = null,
    val colorBlockBgPast: Int? = null,
    val colorBlockBgOngoing: Int? = null,
    val colorBlockBgUpcoming: Int? = null,
    val colorBlockTextPast: Int? = null,
    val colorBlockText: Int? = null,
    val colorBlockAccentPast: Int? = null,
    val colorBlockAccent: Int? = null,
    val colorBlockStroke: Int? = null,
    val colorSelected: Int? = null,
    val colorEditLayerBg: Int? = null,
    val colorEditLayerText: Int? = null,
    val colorEditLayerTime: Int? = null,
    val colorEditHandle: Int? = null,
) {
    /** 取吸附步长，未配置时用 §7.2 的 15 分钟。 */
    fun resolvedSnapMinutes(default: Int): Int = (snapMinutes ?: default).coerceAtLeast(0)

    fun resolvedMinDuration(default: Int): Int = (minDurationMinutes ?: default).coerceAtLeast(1)

    fun resolvedMaxDuration(default: Int): Int =
        (maxDurationMinutes ?: default).coerceIn(1, MinuteOfDay.MINUTES_PER_DAY)

    fun resolvedScrollMode(): ScrollMode = scrollMode ?: ScrollMode.SELF

    fun resolvedTimeFormat(): TimeFormat = timeFormat ?: TimeFormat.SYSTEM

    /**
     * 与 [other] 合并：**other 的非 null 字段覆盖本对象，其余保留**。
     *
     * ## 为什么是合并而不是替换
     *
     * 本类所有字段都是可空，且 `null` 的含义是「用资源默认值」。因此：
     * - 布局 XML 通过 `app:dtXxx` 写入了一部分配置；
     * - 业务方随后调用 `setConfig(TimelineConfig(hourHeight = …))`；
     * - 若整体替换，XML 里那些**没提到**的字段会被清成 null 而悄悄退回默认值，
     *   表现为「XML 配置好像没生效」，且没有任何报错。
     *
     * 合并语义让「XML 打底 + 代码微调」这种最常见的组合自然成立，
     * 也符合 FC-005「两侧一一对应、效果完全一致」的要求。
     */
    fun mergedWith(other: TimelineConfig): TimelineConfig = TimelineConfig(
        hourHeight = other.hourHeight ?: hourHeight,
        axisWidth = other.axisWidth ?: axisWidth,
        topPadding = other.topPadding ?: topPadding,
        bottomPadding = other.bottomPadding ?: bottomPadding,
        endMargin = other.endMargin ?: endMargin,
        gridLineWidth = other.gridLineWidth ?: gridLineWidth,
        axisLabelSize = other.axisLabelSize ?: axisLabelSize,
        nowLabelSize = other.nowLabelSize ?: nowLabelSize,

        blockGap = other.blockGap ?: blockGap,
        blockCorner = other.blockCorner ?: blockCorner,
        blockPaddingHorizontal = other.blockPaddingHorizontal ?: blockPaddingHorizontal,
        blockPaddingVertical = other.blockPaddingVertical ?: blockPaddingVertical,
        blockMinHeight = other.blockMinHeight ?: blockMinHeight,
        blockAccentBarWidth = other.blockAccentBarWidth ?: blockAccentBarWidth,
        blockStrokeWidth = other.blockStrokeWidth ?: blockStrokeWidth,
        showTimeSubtitle = other.showTimeSubtitle ?: showTimeSubtitle,

        showNowIndicator = other.showNowIndicator ?: showNowIndicator,
        nowIndicatorTodayOnly = other.nowIndicatorTodayOnly ?: nowIndicatorTodayOnly,
        nowDotDiameter = other.nowDotDiameter ?: nowDotDiameter,
        nowLineWidth = other.nowLineWidth ?: nowLineWidth,

        timeFormat = other.timeFormat ?: timeFormat,
        snapMinutes = other.snapMinutes ?: snapMinutes,
        minDurationMinutes = other.minDurationMinutes ?: minDurationMinutes,
        maxDurationMinutes = other.maxDurationMinutes ?: maxDurationMinutes,
        defaultNewDurationMinutes = other.defaultNewDurationMinutes ?: defaultNewDurationMinutes,

        scrollMode = other.scrollMode ?: scrollMode,
        edgeAutoScrollEnabled = other.edgeAutoScrollEnabled ?: edgeAutoScrollEnabled,
        edgeScrollTriggerSize = other.edgeScrollTriggerSize ?: edgeScrollTriggerSize,
        edgeScrollStepSize = other.edgeScrollStepSize ?: edgeScrollStepSize,
        dragThresholdRatio = other.dragThresholdRatio ?: dragThresholdRatio,

        editHandleVisualSize = other.editHandleVisualSize ?: editHandleVisualSize,
        editHandleTouchSize = other.editHandleTouchSize ?: editHandleTouchSize,
        minTouchTarget = other.minTouchTarget ?: minTouchTarget,

        colorBackground = other.colorBackground ?: colorBackground,
        colorGridLine = other.colorGridLine ?: colorGridLine,
        colorAxisLabel = other.colorAxisLabel ?: colorAxisLabel,
        colorNow = other.colorNow ?: colorNow,
        colorBlockBgPast = other.colorBlockBgPast ?: colorBlockBgPast,
        colorBlockBgOngoing = other.colorBlockBgOngoing ?: colorBlockBgOngoing,
        colorBlockBgUpcoming = other.colorBlockBgUpcoming ?: colorBlockBgUpcoming,
        colorBlockTextPast = other.colorBlockTextPast ?: colorBlockTextPast,
        colorBlockText = other.colorBlockText ?: colorBlockText,
        colorBlockAccentPast = other.colorBlockAccentPast ?: colorBlockAccentPast,
        colorBlockAccent = other.colorBlockAccent ?: colorBlockAccent,
        colorBlockStroke = other.colorBlockStroke ?: colorBlockStroke,
        colorSelected = other.colorSelected ?: colorSelected,
        colorEditLayerBg = other.colorEditLayerBg ?: colorEditLayerBg,
        colorEditLayerText = other.colorEditLayerText ?: colorEditLayerText,
        colorEditLayerTime = other.colorEditLayerTime ?: colorEditLayerTime,
        colorEditHandle = other.colorEditHandle ?: colorEditHandle,
    )
}
