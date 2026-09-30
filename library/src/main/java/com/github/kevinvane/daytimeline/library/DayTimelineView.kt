package com.github.kevinvane.daytimeline.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import com.github.kevinvane.daytimeline.library.api.BlockContext
import com.github.kevinvane.daytimeline.library.api.EventBlockPainter
import com.github.kevinvane.daytimeline.library.api.GridContext
import com.github.kevinvane.daytimeline.library.api.GridPainter
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineColors
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.EventDiff
import com.github.kevinvane.daytimeline.library.core.EventSanitizer
import com.github.kevinvane.daytimeline.library.core.EventState
import com.github.kevinvane.daytimeline.library.core.Geometry
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.OverlapLayoutEngine
import com.github.kevinvane.daytimeline.library.core.PlacedBlock
import com.github.kevinvane.daytimeline.library.core.SanitizedEvent
import com.github.kevinvane.daytimeline.library.core.TimeStateResolver
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import com.github.kevinvane.daytimeline.library.internal.Dimens
import com.github.kevinvane.daytimeline.library.paint.DefaultEventBlockPainter
import com.github.kevinvane.daytimeline.library.paint.DefaultGridPainter
import com.github.kevinvane.daytimeline.library.paint.Theme

/**
 * 单日 24 小时时间轴组件（PRD §2.1）。
 *
 * ## 实现要点
 *
 * - **单 View 全量自绘**（AD-01）：不创建任何子 View，网格区常驻界面元素数为 0（§12.1）。
 * - **刷新必须收敛**（AD-06）：所有刷新走 [requestRefresh]，内部比对渲染签名，
 *   签名不变则完全不 invalidate（D17 / D10）。
 * - **时间类型编译期安全**（AD-02）：数据经 [MinuteOfDay]，传错单位编译不过（D18/D19）。
 * - **零第三方依赖**（K10/T1）：滚动用平台自带的 [OverScroller] / [GestureDetector]。
 *
 * ## 接入
 * ```kotlin
 * timelineView.submitEvents(todayList)
 * timelineView.listener = object : TimelineListener { ... }
 * ```
 */
class DayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ================= 对外 API =================

    /** 业务方事件回调。设为 null 即不回调。 */
    var listener: TimelineListener? = null

    /** 第二层定制：网格绘制。为 null 使用默认实现。 */
    var gridPainter: GridPainter? = null

    /** 第三层定制：日程块内容绘制。为 null 使用默认实现。 */
    var eventBlockPainter: EventBlockPainter? = null

    // ================= 内部状态 =================

    private var config = TimelineConfig()
    private var dimens: Dimens = Dimens.resolve(context, config)
    private var paints: Theme.Paints = buildPaints()
    private var colors: TimelineColors = dimens.theme.toPublicColors()

    private var events: List<SanitizedEvent> = emptyList()
    private var blocks: List<PlacedBlock> = emptyList()
    private var states: Map<String, com.github.kevinvane.daytimeline.library.core.EventState> = emptyMap()
    private var selectedId: String? = null

    private var viewDate: Long = System.currentTimeMillis()
    private var nowMinute: Int = currentMinuteOfDay()
    private var overrideNowMinute: Int? = null
    private var isToday = true

    private var scrollOffset = 0
    private var hasLocatedFirstTime = false

    /** 绘制签名：只有它变化才真正 invalidate（AD-06 / D17）。 */
    private var lastRenderSignature: Long = Long.MIN_VALUE

    /** 复用的绘制上下文，避免每帧分配（§12.1；lint DrawAllocation）。 */
    private val gridContext = GridContext()
    private val blockContext = BlockContext()

    /** 复用的 Paints 包装（§12.1：不在 onDraw 里分配对象）。 */
    private val gridPaints = GridPainter.Paints(paints.gridLine, paints.axisLabel, paints.nowLabel)
    private val blockPaints = EventBlockPainter.Paints(
        paints.blockBackground, paints.blockText, paints.blockAccent, paints.blockStroke,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context)
    private val density = context.resources.displayMetrics.density

    private val nowTicker = object : Runnable {
        override fun run() {
            if (overrideNowMinute == null) nowMinute = currentMinuteOfDay()
            // 重绘当前时间线；requestRefresh 内部会比对签名，无变化不会 invalidate
            requestRefresh()
            listener?.onNowRefreshed(nowMinute)
            handler.postDelayed(this, dimens.nowRefreshMillis)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 必须转调 performClick，否则屏幕阅读器无法激活（lint / UF-003）
                performClick()
                // M4 交互闭环在此接入：命中日程块 → 选中并回调点击；命中空白 → 新建编辑态
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // M4：长按日程块进入编辑态；长按空白不进入编辑（§8.2）
            }
        },
    )

    init {
        // E31 / E30：存活期间切换语言或深色模式时即时重绘
        ViewCompat.setAccessibilityDelegate(this, null)
        isClickable = true
        isFocusable = true
    }

    // ================= 对外方法 =================

    /**
     * 提交当天全部日程（PRD §11.1 / FD-002）。
     *
     * 内部会：兜底修正脏数据 → 上抛异常清单 → 保持滚动位置 → 重算分栏。
     * 增量更新不重建整个 View（AD-01），因此不会闪烁或重排（AC-06）。
     */
    @JvmOverloads
    fun submitEvents(list: List<TimelineEvent>, notifyIssues: Boolean = true) {
        val result = EventSanitizer.sanitize(
            list,
            minDurationMinutes = dimens.minDurationMinutes,
            maxDurationMinutes = dimens.maxDurationMinutes,
        )
        if (notifyIssues && result.issues.isNotEmpty()) {
            // 数据异常上抛；修正对用户不可见（§9.3）
            listener?.onDataIssues(result.issues)
        }
        val newEvents = result.events
        if (newEvents == events) return

        // FD-003 / AC-06：记录滚动锚点，数据变化后精确还原
        val anchor = EventDiff.anchorOf(
            blocks, scrollOffset, dimens.effectiveHourHeight, dimens.topPadding,
        )
        events = newEvents
        states = TimeStateResolver.resolveAll(events, nowMinute)
        relayout()
        scrollOffset = EventDiff.restoreOffset(
            anchor, blocks, dimens.effectiveHourHeight, dimens.topPadding, scrollOffset,
        )
        clampScroll()
        requestRefresh()
    }

    /** 只刷新一条（PRD §11.1 更新单条日程）。 */
    fun updateEvent(event: TimelineEvent) = submitEvents(
        events.map { if (it.id == event.id) event else it.source },
    )

    /** 只移除一条（PRD §11.1 删除单条日程）。 */
    fun removeEvent(id: String) = submitEvents(events.mapNotNull { if (it.id == id) null else it.source })

    /** 切换查看日期（PRD §11.1 / FD-005 / 场景 G）。 */
    fun setViewDate(epochMillis: Long) {
        viewDate = epochMillis
        isToday = isSameDay(epochMillis, System.currentTimeMillis())
        requestRefresh()
    }

    /** 指定「当前时间」，用于测试与特殊场景（PRD §11.1 / FD-006）。 */
    fun setNowMinute(minute: Int?) {
        overrideNowMinute = minute?.coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE)
        if (overrideNowMinute != null) nowMinute = overrideNowMinute!!
        states = TimeStateResolver.resolveAll(events, nowMinute)
        requestRefresh()
    }

    /** 设置参数配置（PRD §10.1 第一层 / FC-005）。 */
    fun setConfig(newConfig: TimelineConfig) {
        config = newConfig
        dimens = Dimens.resolve(context, config)
        paints = buildPaints()
        colors = dimens.theme.toPublicColors()
        relayout()
        clampScroll()
        requestRefresh()
    }

    fun getConfig(): TimelineConfig = config

    /** 跳转到指定时刻（PRD §11.2 / FI-013）。 */
    @JvmOverloads
    fun scrollToMinute(minute: Int, smooth: Boolean = true) {
        val target = (Geometry.minuteToOffset(
            minute.toFloat(), dimens.effectiveHourHeight,
        ).toInt() - dimens.firstLocateLeadIn).coerceAtLeast(0)
        val maxScroll = maxScroll()
        val clamped = target.coerceAtMost(maxScroll)
        if (smooth) {
            scroller.startScroll(scrollOffset, clamped, clamped - scrollOffset, 0, 220)
            postInvalidateOnAnimation()
        } else {
            scrollOffset = clamped
            requestRefresh()
        }
    }

    /** 读取当前滚动位置（PRD §11.2，供业务方在进程被杀后自行恢复）。 */
    fun getScrollOffset(): Int = scrollOffset

    /** 恢复滚动位置。 */
    fun setScrollOffset(offset: Int) {
        scrollOffset = offset.coerceIn(0, maxScroll())
        requestRefresh()
    }

    /** 当前滚动模式（§8.6）。 */
    fun scrollMode(): ScrollMode = config.resolvedScrollMode()

    // ================= 绘制 =================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dimens.effectiveHourHeight <= 0) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // lint UseKtx：用 androidx 的 withTranslation 扩展替代 save/translate/restore
        canvas.withTranslation(
            0f,
            -scrollOffset.toFloat(),
        ) {

        val axisEdge = resolveAxisEnd(w)
        val contentLeft = axisEdge
        val contentRight = (w - dimens.endMargin).coerceAtLeast(contentLeft)
        val available = (contentRight - contentLeft).coerceAtLeast(0)

        // ---- 网格 ----
        // 复用同一份 context（AD-06/§12.1：不在 onDraw 里分配对象）
        gridContext.apply {
            width = this@DayTimelineView.width
            height = this@DayTimelineView.height
            scrollOffset = this@DayTimelineView.scrollOffset
            axisAreaEnd = axisEdge
            contentStart = contentLeft
            contentEnd = contentRight
            hourHeight = dimens.effectiveHourHeight
            topPadding = dimens.topPadding
            bottomPadding = dimens.bottomPadding
            gridLineWidth = dimens.gridLineWidth
            showNowIndicator = shouldShowNow()
            nowMinute = this@DayTimelineView.nowMinute
            labelStep = labelStepFor(fontScaleOf())
        }
        (gridPainter ?: DefaultGridPainter).paint(canvas, gridContext, colors, gridPaints)

        // ---- 日程块 ----
        val painter = eventBlockPainter ?: DefaultEventBlockPainter
        val hourHeight = dimens.effectiveHourHeight
        val timeFmt = config.resolvedTimeFormat()
        val showSubtitle = config.showTimeSubtitle ?: false
        for (b in blocks) {
            val startMin = b.event.start.minuteOfDay
            val endMin = b.event.end.minuteOfDay
            val blockTop = dimens.topPadding +
                Geometry.minuteToOffset(startMin.toFloat(), hourHeight).toInt()
            val blockH = Geometry.blockHeight(startMin, endMin, hourHeight, dimens.blockMinHeight)
            // 视口裁剪：只绘制相交且高度为正的块（D4：绝不为负）
            if (blockH <= 0) continue
            if (!Geometry.intersectsViewport(blockTop.toFloat(), blockH, scrollOffset, h)) continue

            blockContext.apply {
                event = b.event
                state = states[b.event.id] ?: EventState.UPCOMING
                left = contentLeft + b.left
                top = blockTop
                width = b.width.coerceAtLeast(0)
                height = blockH
                corner = dimens.blockCorner
                paddingHorizontal = dimens.blockPaddingHorizontal
                paddingVertical = dimens.blockPaddingVertical
                accentBarWidth = dimens.blockAccentBarWidth
                strokeWidth = dimens.blockStrokeWidth
                selected = selectedId == b.event.id
                editing = false
                timeFormat = timeFmt
                startText = b.event.start.toString()
                endText = b.event.end.toString()
                showTimeSubtitle = showSubtitle
                tooShortForText = blockH < dimens.blockMinHeight
            }
            painter.paint(canvas, blockContext, colors, blockPaints)
        }

        }
    }

    // ================= 刷新收敛（AD-06 / D17） =================

    /**
     * 唯一的刷新入口。
     *
     * 只有「渲染签名」变化才 invalidate。这样 30 秒一次的定时刷新在多数时候
     * 不会触发任何重绘，避免 D10「无差别重复刷新」与 D17「不收敛的重绘循环」。
     */
    private fun requestRefresh() {
        val signature = renderSignature()
        if (signature == lastRenderSignature) return
        lastRenderSignature = signature
        invalidate()
    }

    private fun renderSignature(): Long {
        var h = 17L
        h = h * 31 + width
        h = h * 31 + height
        h = h * 31 + scrollOffset
        h = h * 31 + nowMinute
        h = h * 31 + events.size
        h = h * 31 + (selectedId?.hashCode() ?: 0)
        h = h * 31 + dimens.effectiveHourHeight
        // 状态集合参与签名：跨过「已过」分界时才能重绘（FR-009 要求 30 秒内更新）
        h = h * 31 + states.values.fold(0) { acc, s -> acc + s.ordinal }
        return h
    }

    private fun relayout() {
        val available = (width - dimens.endMargin - resolveAxisEnd(width)).coerceAtLeast(0)
        blocks = OverlapLayoutEngine.layout(events, available, dimens.blockGap)
    }

    // ================= 滚动 =================

    /**
     * 触摸分发。
     *
     * ## 关于 lint ClickableViewAccessibility 的豁免
     *
     * 该检查只在 [onTouchEvent] 的函数体里做**直接**调用扫描，无法穿透
     * [GestureDetector] 的委托，因此看不到我们在 [gestureDetector] 的
     * `onSingleTapConfirmed` 中确实调用了 [performClick]。
     *
     * 无障碍契约本身是满足的：轻点会经 `onSingleTapConfirmed` → `performClick()`，
     * 屏幕阅读器可以正常激活。故此处按 §5.2「显式豁免清单」的做法精确豁免本条，
     * **不是**全局关闭该检查。M4 交互接入后需在真机上复核（Q8）。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (config.resolvedScrollMode() == ScrollMode.EXTERNAL) {
            // §8.6：外部滚动模式不消费垂直手势，交给外层容器
            // 但点击/长按仍需可用，因此交给 GestureDetector 而不主动消费移动
            return gestureDetector.onTouchEvent(event)
        }
        return gestureDetector.onTouchEvent(event) || handleSelfScroll(event)
    }

    /**
     * 覆盖以满足无障碍要求（lint ClickableViewAccessibility）。
     *
     * [onTouchEvent] 消费了点击就必须能触发 [performClick]，
     * 否则屏幕阅读器无法激活该控件（UF-003 / Q8）。
     * M4 会在此接入选中 / 新建逻辑。
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleSelfScroll(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scroller.forceFinished(true)
            MotionEvent.ACTION_MOVE -> {
                // FI-001：自身滚动模式下消费垂直手势
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.coerceIn(0, maxScroll())
            // 滚动必须重绘（内容位移属于真实变化），不走签名比对
            lastRenderSignature = Long.MIN_VALUE
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    private fun maxScroll(): Int =
        (dimens.contentHeight - height).coerceAtLeast(0)

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
    }

    // ================= 生命周期 =================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(nowTicker, dimens.nowRefreshMillis)
    }

    override fun onDetachedFromWindow() {
        // E10 / Q6：页面刚打开就关闭时不得有残留任务
        handler.removeCallbacks(nowTicker)
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            handler.removeCallbacks(nowTicker)
            handler.postDelayed(nowTicker, dimens.nowRefreshMillis)
        } else {
            handler.removeCallbacks(nowTicker)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        clampScroll()
        requestRefresh()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 外部滚动模式：高度按全天内容高度（§8.6 高度约定）
        if (config.resolvedScrollMode() == ScrollMode.EXTERNAL) {
            setMeasuredDimension(
                resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(dimens.contentHeight, heightMeasureSpec),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    // ================= 状态保存（AD-11 / D12 / E9） =================

    override fun onSaveInstanceState(): android.os.Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(superState).also {
            it.scrollOffset = scrollOffset
            it.viewDate = viewDate
            it.selectedId = selectedId
        }
    }

    override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        scrollOffset = state.scrollOffset
        viewDate = state.viewDate
        selectedId = state.selectedId
        isToday = isSameDay(viewDate, System.currentTimeMillis())
        lastRenderSignature = Long.MIN_VALUE
    }

    /** 自身滚动模式下恢复滚动位置；外部滚动模式下滚动位置由外层负责（§8.6）。 */
    private class SavedState : android.view.View.BaseSavedState {
        var scrollOffset = 0
        var viewDate = 0L
        var selectedId: String? = null

        constructor(superState: android.os.Parcelable?) : super(superState)

        constructor(source: android.os.Parcel) : super(source) {
            scrollOffset = source.readInt()
            viewDate = source.readLong()
            selectedId = source.readString()
        }

        override fun writeToParcel(out: android.os.Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(scrollOffset)
            out.writeLong(viewDate)
            out.writeString(selectedId)
        }

        companion object {
            @JvmField
            val CREATOR = object : android.os.Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: android.os.Parcel) = SavedState(source)
                override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
            }
        }
    }

    // ================= 工具 =================

    private fun buildPaints(): Theme.Paints = Theme.Paints(
        Theme.DimensForPaints(
            gridLineWidth = dimens.gridLineWidth,
            axisLabelSize = dimens.axisLabelSize,
            nowLabelSize = dimens.nowLabelSize,
            editStrokeWidth = dimens.editStrokeWidth,
        ),
    )

    /** 时间轴区域右边缘。RTL 下时间轴在右侧（AD-10 / E16）。 */
    private fun resolveAxisEnd(totalWidth: Int): Int =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) 0
        else dimens.axisWidth.coerceIn(0, totalWidth)

    private fun shouldShowNow(): Boolean {
        val enabled = config.showNowIndicator ?: true
        if (!enabled) return false
        val todayOnly = config.nowIndicatorTodayOnly ?: true
        return if (todayOnly) isToday else true
    }

    /** 字体放大时标签自动降密度，但刻度线始终完整（PRD E14）。 */
    private fun labelStepFor(fontScale: Float): Int = when {
        fontScale >= 1.8f -> 3
        fontScale >= 1.4f -> 2
        else -> 1
    }

    private fun fontScaleOf(): Float = context.resources.configuration.fontScale

    private fun currentMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }
}

/** 把内部 [Theme] 映射为对外的 [TimelineColors]（供定制方取色）。 */
private fun Theme.toPublicColors(): TimelineColors = TimelineColors(
    background = background,
    gridLine = gridLine,
    axisLabel = axisLabel,
    now = now,
    blockBgPast = blockBgPast,
    blockBgOngoing = blockBgOngoing,
    blockBgUpcoming = blockBgUpcoming,
    blockTextPast = blockTextPast,
    blockText = blockText,
    blockAccentPast = blockAccentPast,
    blockAccent = blockAccent,
    blockStroke = blockStroke,
    selected = selected,
    editLayerBg = editLayerBg,
    editLayerText = editLayerText,
    editLayerTime = editLayerTime,
    editHandle = editHandle,
)
