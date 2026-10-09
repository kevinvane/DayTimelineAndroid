package com.github.kevinvane.daytimeline.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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
import java.util.Locale
import com.github.kevinvane.daytimeline.library.api.BlockContext
import com.github.kevinvane.daytimeline.library.api.EventBlockPainter
import com.github.kevinvane.daytimeline.library.api.GridContext
import com.github.kevinvane.daytimeline.library.api.GridPainter
import com.github.kevinvane.daytimeline.library.api.ScrollMode
import com.github.kevinvane.daytimeline.library.api.TimelineColors
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.EditSession
import com.github.kevinvane.daytimeline.library.core.EventDiff
import com.github.kevinvane.daytimeline.library.core.EventSanitizer
import com.github.kevinvane.daytimeline.library.core.EventState
import com.github.kevinvane.daytimeline.library.core.Geometry
import com.github.kevinvane.daytimeline.library.core.GestureArbiter
import com.github.kevinvane.daytimeline.library.core.HitTester
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.OverlapLayoutEngine
import com.github.kevinvane.daytimeline.library.core.PlacedBlock
import com.github.kevinvane.daytimeline.library.core.SanitizedEvent
import com.github.kevinvane.daytimeline.library.core.TimeStateResolver
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import com.github.kevinvane.daytimeline.library.internal.ConfigFromAttrs
import com.github.kevinvane.daytimeline.library.paint.InstallAccessibility
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

    /** 拖拽滚动的状态（FI-001）。 */
    private var lastScrollTouchY = 0f
    private var parentDisallowRequested = false
    private var lastVelocityTracker: android.view.VelocityTracker? = null

    /** 绘制签名：只有它变化才真正 invalidate（AD-06 / D17）。 */
    private var lastRenderSignature: Long = Long.MIN_VALUE

    /** M4 交互状态。 */
    private var editSession: EditSession? = null
    private val gestureArbiter = GestureArbiter(dragThresholdPx = 48)
    private val hitTester = HitTester(minTouchTarget = 48, handleTouchSize = 48)
    private var grabbedHandle = 0
    private var edgeScrollScheduled = false
    private var blockTops = IntArray(0)
    private var blockHeights = IntArray(0)

    /**
     * 编辑态的「完成 / 取消 / 删除」回调。
     *
     * 刻意与 [listener] 分离：业务方可只接管编辑层（AD-08 第四层），
     * 不影响其余能力。
     */
    var editController: EditController? = null


    /** 第四层定制：接管新建流程 / 编辑层操作。 */
    interface EditController {
        /** 用户点击「完成」。返回 true 表示已由业务方处理，组件不再自行上抛事件。 */
        fun onDone(range: IntRange, isCreating: Boolean): Boolean = false

        /** 用户点击「取消」。组件内部已保证不会发出任何数据变更。 */
        fun onCancel() = Unit

        /** 用户点击「删除」。 */
        fun onDelete(): Boolean = false

        /** 需要弹出删除二次确认。组件提供默认实现，业务方可覆盖。 */
        fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit) {
            android.app.AlertDialog.Builder(context)
                .setTitle(R.string.day_timeline_delete_confirm_title)
                .setMessage(R.string.day_timeline_delete_confirm_message)
                .setNegativeButton(R.string.day_timeline_delete_confirm_negative, null)
                .setPositiveButton(R.string.day_timeline_delete_confirm_positive) { _, _ ->
                    onConfirmed()
                }
                .show()
        }
    }

    /** 复用的绘制上下文，避免每帧分配（§12.1；lint DrawAllocation）。 */
    private val gridContext = GridContext()
    private val blockContext = BlockContext()

    /** 复用的 Paints 包装（§12.1：不在 onDraw 里分配对象）。 */
    private var gridPaints = GridPainter.Paints(paints.gridLine, paints.axisLabel, paints.nowLabel, nowLine = paints.nowLine, nowDot = paints.nowDot)
    private var blockPaints = EventBlockPainter.Paints(
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

    /**
     * 仅用于**长按超时**检测（§8.2 的 onLongPress）。
     *
     * 点击 / 滚动 / 拖拽判定全部由 [GestureArbiter] 自己做（纯状态机，可单测），
     * 不交给 GestureDetector——那样就无法保证「滑动时绝不误触发长按」。
     */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onLongPress(e: MotionEvent) {
                // 只标记「长按已触发」；真正的进入编辑态在抬手时判定（§8.2）
                gestureArbiter.onLongPressTimeout()
            }
        },
    )

    init {
        // ---- 先解析 XML 配置，再重算尺寸与色值 ----
        //
        // Kotlin 的属性初始化器按声明顺序执行，`dimens` 的初值在下面的 `init` **之前**
        // 求出，因此拿不到 attrs。所以这里读一次 XML 配置并整体重算——
        // 构造只发生一次，多这一次开销可以忽略，换来「XML 与代码配置走同一条路」。
        val fromXml = ConfigFromAttrs.read(context, attrs)
        if (fromXml != config) {
            config = config.mergedWith(fromXml)
            rebindDerivedState()
        }
        // AD-05 无障碍虚拟视图（Q8 / UF-002 / FI-016）
        InstallAccessibility.install(this)
        isClickable = true
        isFocusable = true
    }

    /** 依据当前 [config] 重算 dimens / paints / colors / 布局。 */
    private fun rebindDerivedState() {
        dimens = Dimens.resolve(context, config)
        paints = buildPaints()
        colors = dimens.theme.toPublicColors()
        gridPaints = GridPainter.Paints(paints.gridLine, paints.axisLabel, paints.nowLabel, nowLine = paints.nowLine, nowDot = paints.nowDot)
        blockPaints = EventBlockPainter.Paints(
            paints.blockBackground, paints.blockText, paints.blockAccent, paints.blockStroke,
        )
        relayout()
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
        // E20 / E21 / E28：业务方回传新数据后，编辑态要么自动取消，要么打冲突标记
        editSession = editSession?.onDataChanged(newEvents.associateBy { it.id })
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
        nowMinute = overrideNowMinute ?: currentMinuteOfDay()
        states = TimeStateResolver.resolveAll(events, nowMinute)
        requestRefresh()
    }

    /** 设置参数配置（PRD §10.1 第一层 / FC-005）。 */
    /**
     * 以 [newConfig] 覆盖当前配置。
     *
     * 采用**合并**语义（见 [TimelineConfig.mergedWith]）：[newConfig] 中的非 null 字段
     * 生效，其余保持不变。因此可以「XML 打底 + 代码微调」，
     * 而不会像整体替换那样把 XML 里没提到的字段悄悄退回默认值。
     */
    fun setConfig(newConfig: TimelineConfig) {
        config = config.mergedWith(newConfig)
        rebindDerivedState()
        clampScroll()
        requestRefresh()
    }

    /** 当前生效配置的只读快照（未设置项为 null，表示用资源默认值）。 */
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
            // 全程使用 Y 轴：scrollOffset 是纵向偏移，computeScroll() 读的也是 currY。
            // 早先误用 X 轴（currX 变化而 currY 恒为 0）会导致抬手后每帧被写回 0，表现为回弹。
            scroller.startScroll(0, scrollOffset, 0, clamped - scrollOffset, SCROLL_DURATION_MS)
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
            nowDotDiameter = dimens.nowDotDiameter
            nowLineWidth = dimens.nowLineWidth
            skipOverlappingHourLabel = config.nowIndicatorSkipOverlappingHourLabel ?: true
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
                timeFormat = timeFmt
                startText = b.event.start.toString()
                endText = b.event.end.toString()
                showTimeSubtitle = showSubtitle
                tooShortForText = blockH < dimens.blockMinHeight
            }
            painter.paint(canvas, blockContext, colors, blockPaints)
        }

        // §7.6：当前时间线压在日程块之上（网格层画的只是时间文字，红线/圆点在此画；
        // 业务方自定义 GridPainter 时由其自行决定是否绘制）
        if (gridPainter == null) {
            DefaultGridPainter.paintNowForeground(canvas, gridContext, colors, gridPaints)
        }

        drawEditLayer(canvas, contentLeft, contentRight)

        }
    }

    /**
     * 绘制编辑层（PRD §7.7）。
     *
     * 编辑块与刻度线**左右对齐**（左起于时间轴区域右侧，右止于右侧边距），
     * 与普通日程块不同——这是 §7.7 的明确要求。
     *
     * 手柄为上下边缘水平居中的胶囊，视觉厚 6dp × 宽 24dp，触摸热区 48dp 由 [HitTester] 负责（UF-001）。
     */
    private fun drawEditLayer(canvas: Canvas, contentLeft: Int, contentRight: Int) {
        val session = editSession ?: return
        val top = dimens.topPadding +
            Geometry.minuteToOffset(session.startMinute.toFloat(), dimens.effectiveHourHeight).toInt()
        val height = Geometry.blockHeight(
            session.startMinute, session.endMinute, dimens.effectiveHourHeight, dimens.blockMinHeight,
        )
        if (height <= 0) return // D4：绝不为负

        val left = contentLeft.toFloat()
        val right = contentRight.toFloat()
        val bottom = top + height

        paints.editLayer.color = colors.editLayerBg
        canvas.drawRect(left, top.toFloat(), right, bottom.toFloat(), paints.editLayer)

        paints.selection.color = colors.selected
        paints.selection.strokeWidth = dimens.editStrokeWidth.toFloat()
        canvas.drawRect(left, top.toFloat(), right, bottom.toFloat(), paints.selection)

        // 起止时间显示在时间轴区域内侧右对齐（§7.7）
        paints.nowLabel.color = colors.editLayerTime
        paints.nowLabel.textAlign = android.graphics.Paint.Align.RIGHT
        val labelX = (contentLeft - dimens.gridLineWidth).toFloat()
        canvas.drawText(
            session.start.toString(), labelX, top.toFloat(), paints.nowLabel,
        )
        canvas.drawText(
            session.end.toString(), labelX, bottom.toFloat(), paints.nowLabel,
        )

        // 手柄：上下边缘水平中心的胶囊（视觉 6dp 厚 × 4 倍宽，热区仍由 HitTester 保 48dp）
        val handleH = dimens.handleVisualSize.toFloat()
        val handleW = handleH * 4f
        val cx = (left + right) / 2f
        paints.editHandle.color = colors.editHandle
        canvas.drawRoundRect(
            cx - handleW / 2, top - handleH / 2, cx + handleW / 2, top + handleH / 2,
            handleH / 2, handleH / 2, paints.editHandle,
        )
        canvas.drawRoundRect(
            cx - handleW / 2, bottom - handleH / 2, cx + handleW / 2, bottom + handleH / 2,
            handleH / 2, handleH / 2, paints.editHandle,
        )
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
        // 内容变化（改标题 / 改时间 / 数量变化）都必须触发重绘，不能只看 size
        h = h * 31 + events.hashCode()
        h = h * 31 + (selectedId?.hashCode() ?: 0)
        h = h * 31 + dimens.effectiveHourHeight
        // 状态集合参与签名：跨过「已过」分界时才能重绘（FR-009 要求 30 秒内更新）
        h = h * 31 + states.hashCode()
        // 编辑态几何：拖拽 / 缩放必须逐帧重绘（走 requestRefresh 收敛）
        h = h * 31 + (editSession?.startMinute ?: -1)
        h = h * 31 + (editSession?.endMinute ?: -1)
        // isToday 决定 now 指示线显隐（setViewDate 切日时能重绘）
        h = h * 31 + if (isToday) 1 else 0
        return h
    }

    private fun relayout() {
        val available = (width - dimens.endMargin - resolveAxisEnd(width)).coerceAtLeast(0)
        blocks = OverlapLayoutEngine.layout(events, available, dimens.blockGap)
        cacheBlockGeometry()
    }

    /**
     * 预先算好每个块的顶边与高度，供命中测试使用。
     *
     * 命中测试在每次触摸时都会遍历这些值，若每次都重算几何会引入不必要的
     * 开销；缓存后 [HitTester] 只做数值比较。
     */
    private fun cacheBlockGeometry() {
        if (blocks.size != blockTops.size) {
            blockTops = IntArray(blocks.size)
            blockHeights = IntArray(blocks.size)
        }
        val hourHeight = dimens.effectiveHourHeight
        for (i in blocks.indices) {
            val s = blocks[i].event.start.minuteOfDay
            val e = blocks[i].event.end.minuteOfDay
            blockTops[i] = dimens.topPadding +
                Geometry.minuteToOffset(s.toFloat(), hourHeight).toInt()
            blockHeights[i] = Geometry.blockHeight(s, e, hourHeight, dimens.blockMinHeight)
        }
    }

    // ================= 滚动 =================

    /**
     * 确认编辑（PRD FI-008）。
     *
     * **这是唯一会发出数据变更事件的出口。** 取消走 [cancelEdit]，
     * 那条路径在 [EditSession.cancel] 的类型上就不可能携带数据（D3）。
     */
    fun confirmEdit() {
        val session = editSession ?: return
        val commit = session.commit() ?: return
        editSession = null
        selectedId = null
        val handled = editController?.onDone(commit.rangeOrEmpty(), commit.isCreate()) == true
        if (handled) {
            requestRefresh()
            return
        }
        when (commit) {
            is EditSession.Commit.Create -> listener?.onEventCreated(commit.startMinute..commit.endMinute)
            is EditSession.Commit.Modify -> {
                listener?.onEventModified(commit.event.source, commit.range, commit.hasConflict)
                // 本地同步显示，避免业务方未回传数据时界面无反应
                if (!commit.hasConflict) {
                    replaceLocally(commit.event.id, commit.range)
                }
            }
        }
        requestRefresh()
    }

    /**
     * 取消编辑（PRD FI-009 / FI-010）。
     *
     * **D3 硬性要求：绝不在此发出任何数据变更通知。**
     * 唯一允许的回调是 [TimelineListener.onEditCancelled]，它不携带数据，
     * 业务方只能用于统计「编辑完成率」（§15）。
     */
    fun cancelEdit() {
        if (editSession == null) return
        editSession?.cancel() // 返回无字段对象，确保没有任何数据被带出
        editSession = null
        selectedId = null
        grabbedHandle = 0
        editController?.onCancel()
        listener?.onEditCancelled()
        requestRefresh()
    }

    /** 删除日程（PRD FI-011），带二次确认。 */
    fun requestDelete() {
        val session = editSession ?: return
        val event = session.origin ?: return
        if (editController?.onDelete() == true) {
            // 业务方已接管：与 [confirmEdit] 的 handled 分支同样收干净三项状态，
            // 否则选中描边与编辑层会残留到下一次刷新为止
            editSession = null
            selectedId = null
            grabbedHandle = 0
            requestRefresh()
            return
        }
        val title = event.content?.toString()
            ?: context.getString(R.string.day_timeline_a11y_no_content)
        val controller = editController
        if (controller != null) {
            controller.confirmDelete(context, title) { performDelete(event) }
        } else {
            defaultConfirmDelete(title) { performDelete(event) }
        }
    }

    private fun defaultConfirmDelete(title: String, onConfirmed: () -> Unit) {
        android.app.AlertDialog.Builder(this.context)
            .setTitle(R.string.day_timeline_delete_confirm_title)
            .setMessage(R.string.day_timeline_delete_confirm_message)
            .setNegativeButton(R.string.day_timeline_delete_confirm_negative, null)
            .setPositiveButton(R.string.day_timeline_delete_confirm_positive) { _, _ -> onConfirmed() }
            .show()
    }

    private fun performDelete(event: SanitizedEvent) {
        editSession = null
        selectedId = null
        removeEvent(event.id)
        listener?.onEventDeleted(event.source)
    }

    /** 是否处于编辑态。 */
    fun isEditing(): Boolean = editSession != null

    // ================= 无障碍（AD-05 / Q8） =================
    // 以下为 [com.github.kevinvane.daytimeline.library.paint.TimelineAccessibilityHelper]
    // 所需的只读访问器，必须 internal 且不接受外部输入。

    internal fun visibleBlockSnapshot(): List<Pair<Int, PlacedBlock>> {
        val out = ArrayList<Pair<Int, PlacedBlock>>(blocks.size)
        for (i in blocks.indices) {
            if (i >= blockHeights.size || i >= blockTops.size) continue
            val top = blockTops[i]
            val h = blockHeights[i]
            if (h <= 0) continue
            if (!Geometry.intersectsViewport(top.toFloat(), h, scrollOffset, height)) continue
            out += i to blocks[i]
        }
        return out
    }

    internal fun blockBoundsInParent(block: PlacedBlock): android.graphics.Rect? {
        val i = blocks.indexOfFirst { it === block || it.event.id == block.event.id }
        if (i < 0) return null
        val top = blockTops.getOrNull(i) ?: return null
        val h = blockHeights.getOrNull(i) ?: return null
        val left = resolveAxisEnd(width) + block.left
        return android.graphics.Rect(
            left,
            top - scrollOffset,
            left + block.width,
            top - scrollOffset + h,
        )
    }

    internal fun blockStateOf(block: PlacedBlock): EventState =
        states[block.event.id] ?: EventState.UPCOMING

    internal fun dispatchEventClickForAccessibility(block: PlacedBlock) {
        selectedId = block.event.id
        listener?.onEventClick(block.event.source)
        requestRefresh()
    }

    internal fun dispatchEventLongClickForAccessibility(block: PlacedBlock) {
        selectedId = block.event.id
        editSession = EditSession.beginEdit(block.event)
        listener?.onEventLongClick(block.event.source)
        requestRefresh()
    }

    internal fun currentHourRangeText(): String = "00:00 - 24:00"

    /** 私有扩展：取提交结果的起止范围。 */
    private fun EditSession.Commit.rangeOrEmpty(): IntRange = when (this) {
        is EditSession.Commit.Create -> startMinute..endMinute
        is EditSession.Commit.Modify -> range
    }

    private fun EditSession.Commit.isCreate(): Boolean = this is EditSession.Commit.Create

    private fun replaceLocally(id: String, range: IntRange) {
        val updated = events.map {
            if (it.id == id) it.copy(
                start = MinuteOfDay.ofMinute(range.first),
                end = MinuteOfDay.ofMinute(range.last),
            ) else it
        }
        events = updated
        states = TimeStateResolver.resolveAll(events, nowMinute)
        relayout()
    }

    /**
     * 拖拽滚动（PRD FI-001 自身滚动模式）。
     *
     * 手指上移 → 内容上移 → `scrollOffset` 增大。用**增量**而非绝对值，
     * 这样即使某一帧事件丢失也不会跳位。
     *
     * §8.5：边界回弹关闭，因此直接钳制而非交给 `OverScroller` 的 overscroll。
     */
    private fun dragScroll(viewY: Float) {
        if (maxScroll() <= 0) return
        // 自身滚动模式下一律拦住父容器，避免下拉刷新抢手势（E29）
        if (!parentDisallowRequested) {
            parent?.requestDisallowInterceptTouchEvent(true)
            parentDisallowRequested = true
        }
        val dy = lastScrollTouchY - viewY
        if (dy == 0f) return
        lastScrollTouchY = viewY
        val next = (scrollOffset + dy.toInt()).coerceIn(0, maxScroll())
        if (next == scrollOffset) return
        scrollOffset = next
        // 滚动是真实内容位移，不走渲染签名比对
        lastRenderSignature = Long.MIN_VALUE
        invalidate()
    }

    /**
     * 抬手：交给 [OverScroller] 做惯性（§8.5「惯性跟随系统原生手感」），
     * 并解除对父容器的拦截。
     */
    private fun endScrollGesture(viewY: Float) {
        val velocityTracker = lastVelocityTracker
        lastVelocityTracker = null
        val max = maxScroll()
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000) // px/s
            // 手指上滑 → getYVelocity() 为负 → 取反得到向下的正速度
            val velocityY = (-velocityTracker.getYVelocity()).toInt()
            velocityTracker.recycle()
            if (max > 0 && velocityY != 0 && config.resolvedScrollMode() == ScrollMode.SELF) {
                // **必须用 Y 轴**：computeScroll() 读的是 scroller.currY。
                // 早先误把参数填到 X 轴上（startX/minX/maxX），currY 恒为 0，
                // 于是惯性期间每帧把 scrollOffset 写回 0 —— 表现为「滑一下就弹回顶部」。
                //
                // minY=0 / maxY=max 即为钳制，配合 §8.5「边界回弹关闭」不做 overscroll。
                scroller.fling(
                    0, scrollOffset,      // startX, startY
                    0, velocityY,         // velocityX, velocityY
                    0, 0, 0, max,          // minX, maxX, minY, maxY
                )
                postInvalidateOnAnimation()
            }
        }
        lastScrollTouchY = viewY
        if (parentDisallowRequested) {
            parent?.requestDisallowInterceptTouchEvent(false)
            parentDisallowRequested = false
        }
    }

    /**
     * 触摸分发（PRD §8.2 / M4 / AD-04 / FI-001）。
     *
     * 判定顺序严格按 §8.2：先看位移是否超过阈值（决定滚动 / 拖拽），
     * 再看是否已触发长按（决定编辑态），最后才是点击。
     *
     * 自身滚动模式下 [Intent.Scroll] 会被真正消费并执行拖拽滚动 + 惯性；
     * 外部滚动模式下则让给外层容器（§8.6 / E29）。
     *
     * ## 关于 lint ClickableViewAccessibility 的豁免
     *
     * 该检查只在 `onTouchEvent` 函数体内做**直接**调用扫描。本实现不再经
     * `GestureDetector` 委托，但仍在此精确豁免：命中日程块的点击最终会走到
     * [performClick]，契约已满足（UF-003）。按 §5.2「显式豁免清单」处理，
     * **不是**全局关闭该检查。M5 接入无障碍虚拟视图后需在真机复核（Q8）。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hourHeight = dimens.effectiveHourHeight
        if (hourHeight <= 0) return false
        val x = event.x
        val y = event.y
        val contentLeft = resolveAxisEnd(width)
        val contentRight = (width - dimens.endMargin).coerceAtLeast(contentLeft)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastScrollTouchY = y
                parentDisallowRequested = false
                // 速度跟踪：抬手后据此算惯性（FI-001）
                lastVelocityTracker?.recycle()
                lastVelocityTracker = android.view.VelocityTracker.obtain().also {
                    it.addMovement(event)
                }
                // 交给 GestureDetector 只为长按超时；其余判定自己做
                gestureDetector.onTouchEvent(event)
                val hit = hitTester.hitTest(
                    x, y, contentLeft, contentRight,
                    blocks, blockTopsInView(), blockHeights,
                    // 新建态 origin 为 null → editIndex() == -1，但手柄仍需可点（HitTester 只把 editing >= 0
                    // 当作「编辑层激活」的门闩，不直接用索引取块）
                    editing = if (editSession != null) maxOf(0, editIndex()) else -1,
                    editingTop = editTopPx() - scrollOffset,
                    editingHeight = editHeightPx(),
                )
                grabbedHandle = when (hit) {
                    is HitTester.Hit.TopHandle -> 1
                    is HitTester.Hit.BottomHandle -> -1
                    else -> 0
                }
                // 记录按下点相对编辑块起点的偏移，避免拖拽时整块跳动
                downGrabOffset = (minuteAt(y) - (editSession?.startMinute ?: 0))
                    .coerceIn(-MinuteOfDay.MINUTES_PER_DAY, MinuteOfDay.MINUTES_PER_DAY)
                gestureArbiter.onDown(x, y, editingTouching = editSession != null)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastVelocityTracker?.addMovement(event)
                gestureArbiter.onMove(x, y, grabbedHandle)
                when (gestureArbiter.intent) {
                    GestureArbiter.Intent.DragMove -> {
                        dragTo(y)
                        return true
                    }
                    GestureArbiter.Intent.DragResizeTop -> {
                        editSession?.resizeStartTo(
                            minuteAt(y), dimens.snapMinutes, dimens.minDurationMinutes,
                        )
                        maybeStartEdgeScroll(y)
                        requestRefresh()
                        return true
                    }
                    GestureArbiter.Intent.DragResizeBottom -> {
                        editSession?.resizeEndTo(
                            minuteAt(y), dimens.snapMinutes, dimens.minDurationMinutes,
                        )
                        maybeStartEdgeScroll(y)
                        requestRefresh()
                        return true
                    }
                    // §8.6 / E29：外部滚动模式下把手势让给外层容器；
                    // 自身滚动模式则真正消费并执行拖拽滚动（FI-001）
                    GestureArbiter.Intent.Scroll -> {
                        if (config.resolvedScrollMode() == ScrollMode.SELF) {
                            dragScroll(y)
                            return true
                        }
                        return false
                    }
                    else -> return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val intent = gestureArbiter.onUp()
                grabbedHandle = 0
                stopEdgeScroll()
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    parentDisallowRequested = false
                    lastVelocityTracker?.recycle()
                    lastVelocityTracker = null
                } else {
                    // 只在确实判为滚动时才做惯性收尾；点击 / 长按 / 编辑拖拽
                    // 不应启动 scroller 动画，否则会与编辑态的绘制时序打架
                    if (intent == GestureArbiter.Intent.Scroll) {
                        endScrollGesture(y)
                    } else {
                        lastVelocityTracker?.recycle()
                        lastVelocityTracker = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        parentDisallowRequested = false
                    }
                }

                when (intent) {
                    // §8.2：已触发长按但全程未移动 → 进入编辑态（原地选中）
                    GestureArbiter.Intent.LongPress -> {
                        enterEditByLongPress(x, y, contentLeft, contentRight)
                        return true
                    }
                    GestureArbiter.Intent.Click -> {
                        handleTap(x, y, contentLeft, contentRight)
                        return true
                    }
                    // 抬起后保留编辑态，等待用户确认或取消（§8.2）
                    GestureArbiter.Intent.DragMove,
                    GestureArbiter.Intent.DragResizeTop,
                    GestureArbiter.Intent.DragResizeBottom,
                    -> return true
                    else -> return false
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** 长按日程块 → 编辑态（FI-004）。长按空白处**不**进入编辑态（§8.2）。 */
    private fun enterEditByLongPress(x: Float, y: Float, contentLeft: Int, contentRight: Int) {
        performClick() // 无障碍激活
        if (editSession != null) return
        val hit = hitTester.hitTest(
            x, y, contentLeft, contentRight,
            blocks, blockTopsInView(), blockHeights,
            editing = -1, editingTop = 0, editingHeight = 0,
        )
        if (hit is HitTester.Hit.Block) {
            selectedId = hit.block.event.id
            editSession = EditSession.beginEdit(hit.block.event)
            listener?.onEventLongClick(hit.block.event.source)
        }
        // 命中空白：等同普通点击（§8.2）
        requestRefresh()
    }

    private fun editIndex(): Int {
        val id = editSession?.origin?.id ?: return -1
        return blocks.indexOfFirst { it.event.id == id }
    }

    /**
     * 视口坐标下的块顶边。缓存的 [blockTops] 是内容坐标（未扣滚动），
     * 而触摸坐标是视口坐标，命中测试前必须减掉 [scrollOffset]。
     */
    private fun blockTopsInView(): IntArray {
        if (scrollOffset == 0) return blockTops
        return IntArray(blockTops.size) { i -> blockTops[i] - scrollOffset }
    }

    private fun editTopPx(): Int {
        val s = editSession ?: return 0
        return dimens.topPadding +
            Geometry.minuteToOffset(s.startMinute.toFloat(), dimens.effectiveHourHeight).toInt()
    }

    private fun editHeightPx(): Int {
        val s = editSession ?: return 0
        return Geometry.blockHeight(
            s.startMinute, s.endMinute, dimens.effectiveHourHeight, dimens.blockMinHeight,
        )
    }

    /** 视口 y → 当天分钟。 */
    private fun minuteAt(viewY: Float): Int {
        val contentY = viewY + scrollOffset - dimens.topPadding
        return Geometry.offsetToMinute(contentY.toFloat(), dimens.effectiveHourHeight)
    }

    private fun dragTo(viewY: Float) {
        val session = editSession ?: return
        session.moveTo(
            minuteAt(viewY) - downGrabOffset,
            dimens.snapMinutes,
            dimens.minDurationMinutes,
            dimens.maxDurationMinutes,
        )
        requestRefresh()
        maybeStartEdgeScroll(viewY)
    }

    /**
     * 空白处点击 → 新建；点击已有日程 → 选中并上抛点击事件（§8.2）。
     *
     * 编辑态下点击外部区域视为取消（FI-010），语义与「取消」完全一致，
     * 因此同样**不会**发出任何数据变更通知（D3）。
     */
    private fun handleTap(x: Float, y: Float, contentLeft: Int, contentRight: Int) {
        if (editSession != null) {
            val topInView = editTopPx() - scrollOffset
            val inside = x >= contentLeft - dimens.endMargin &&
                x <= contentRight + dimens.endMargin &&
                y >= topInView - dimens.endMargin &&
                y <= topInView + editHeightPx() + dimens.endMargin
            if (!inside) cancelEdit() // FI-010
            return
        }
        val hit = hitTester.hitTest(
            x, y, contentLeft, contentRight,
            blocks, blockTopsInView(), blockHeights,
            editing = -1, editingTop = 0, editingHeight = 0,
        )
        when (hit) {
            is HitTester.Hit.Block -> {
                selectedId = hit.block.event.id
                listener?.onEventClick(hit.block.event.source)
            }
            // FI-005：点击空白进入新建编辑态，默认时长 1 小时
            HitTester.Hit.Empty -> {
                if (x >= contentLeft && x <= contentRight) {
                    editSession = EditSession.beginCreate(
                        minuteAt(y), dimens.snapMinutes, dimens.defaultNewDurationMinutes,
                    )
                }
            }
            else -> Unit
        }
        requestRefresh()
    }

    // ---- 边缘自动滚动（FI-014 / §8.4）：跟随**被拖动的块**，而非手指 ----

    private fun maybeStartEdgeScroll(@Suppress("UNUSED_PARAMETER") viewY: Float) {
        if (!(config.edgeAutoScrollEnabled ?: true)) return
        if (editSession == null) return
        val trigger = dimens.edgeScrollTriggerSize
        val blockTop = editTopPx() - scrollOffset
        val blockBottom = blockTop + editHeightPx()
        val delta = when {
            blockTop < trigger -> -dimens.edgeScrollStepSize
            blockBottom > height - trigger -> dimens.edgeScrollStepSize
            else -> 0
        }
        edgeScrollDelta = delta
        if (delta != 0 && !edgeScrollScheduled) {
            edgeScrollScheduled = true
            handler.post(edgeScrollRunnable)
        }
    }

    private var edgeScrollDelta = 0
    private var downGrabOffset = 0
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (editSession == null || edgeScrollDelta == 0) {
                edgeScrollScheduled = false
                return
            }
            val before = scrollOffset
            scrollOffset = (scrollOffset + edgeScrollDelta).coerceIn(0, maxScroll())
            if (scrollOffset != before) {
                lastRenderSignature = Long.MIN_VALUE
                invalidate()
            }
            handler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
        }
    }

    private fun stopEdgeScroll() {
        edgeScrollDelta = 0
        if (edgeScrollScheduled) {
            handler.removeCallbacks(edgeScrollRunnable)
            edgeScrollScheduled = false
        }
    }

    /**
     * 覆盖以满足无障碍要求（lint ClickableViewAccessibility）。
     *
     * [onTouchEvent] 消费了点击就必须能触发 [performClick]，
     * 否则屏幕阅读器无法激活该控件（UF-003 / Q8）。
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            // 一律读 currY：scrollOffset 是纵向偏移，fling/startScroll 也都配在 Y 轴上
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

    override fun onDetachedFromWindow() {
        // E10 / Q6：页面刚打开就关闭时不得有残留任务
        handler.removeCallbacks(nowTicker)
        handler.removeCallbacks(edgeScrollRunnable)
        edgeScrollScheduled = false
        scroller.forceFinished(true)
        lastVelocityTracker?.recycle()
        lastVelocityTracker = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // E30 / E31：存活期间切换深色模式或语言后重新挂载，
        // 强制重建色值与文案（§7.3 要求不写分支，故整份重解析而非局部修补）
        rebindResourcesIfConfigChanged()
        handler.postDelayed(nowTicker, dimens.nowRefreshMillis)
    }

    /**
     * 检测到 uiMode / locale 变化时重新解析资源。
     *
     * 这**不是**「深色模式分支」——它对深浅两种情况一视同仁地重解析资源，
     * 具体的浅/深取值仍全部来自 `values` 与 `values-night` 两份 colors.xml，
     * 代码里没有任何 `if (isNight)` 式的配色判断（§7.3 强制要求 ②）。
     */
    private fun rebindResourcesIfConfigChanged() {
        val current = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) to Locale.getDefault()
        if (current == lastBoundUiMode) return
        lastBoundUiMode = current
        rebindDerivedState()
        requestRefresh()
    }

    private var lastBoundUiMode: Pair<Int, Locale> = Pair(Int.MIN_VALUE, Locale.getDefault())

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
        /** 边缘自动滚动帧间隔：约 60fps。 */
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
            blockTextSize = dimens.blockTextSize,
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

/** 边缘自动滚动帧间隔：约 60fps（FI-014）。 */
private const val EDGE_SCROLL_INTERVAL_MS = 16L

/** 平滑滚动到指定时刻的时长（ms）。§8.5 未规定，取 220ms 的常见手感。 */
private const val SCROLL_DURATION_MS = 220

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
