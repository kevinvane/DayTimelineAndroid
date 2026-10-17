package com.github.kevinvane.daytimeline.app

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.api.EditDraft
import com.github.kevinvane.daytimeline.library.api.EditResult
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.DataIssue
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Demo：把 [DayTimelineView] 放进页面并提交一批带真实重叠关系的数据。
 *
 * 数据故意包含 PRD §14.2 关注的形态：部分重叠、完全包含、空闲列复用、
 * 首尾相接、跨越全天、极短日程。
 *
 * ## 表单输入演示（PRD §7.7.1 / §10.2 第四层 / AD-22）
 *
 * 组件**零新增 View**——输入控件全部在这个 demo 里（`sheet_event_form.xml`）。
 * 接管后的完整闭环：
 * ```
 * onEnterEditing(draft) → 用 draft 预填表单 → 返回 true
 * 表单「确定」→ applyEdit(EditResult) → confirmEdit()
 * 表单「取消」→ cancelEdit()
 * ```
 *
 * 演示了三件事：
 * 1. **标题**能改——此前 `onEventModified` 只回传修改前的对象，标题根本改不了；
 * 2. **时间**能在表单里精确输入，填越界值也安全（组件按 §8.3.1 合法化）；
 * 3. **备注不进组件**——它只存在 [DemoEvent] 里，组件既不接收也不感知。
 *
 * 默认路径（不带 [EXTRA_SHOW_EDIT_FORM]）不接管编辑层，长按直接进拖拽手柄态，
 * 此时「拖完松手即确认」由 [installDragEndAutoConfirm] 在**业务方侧**补上——
 * 组件只保留编辑态、从不自行提交（D3）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var timeline: DayTimelineView

    /** 业务方自己的数据源；组件不持有、不落库（PRD §11.4）。 */
    private val events: MutableList<DemoEvent> = SampleEvents.today().toMutableList()

    /**
     * 表单本次提交的业务方字段。
     *
     * **组件不接收它们**——`EditResult` 只有起止时间与标题（PRD §7.7.1）。
     * demo 暂存一下，好在收到 `onEventCreated` / `onEventModified` 时
     * 一起写回自己的数据类并 `submitEvents`。真实业务里这一步在落库层。
     */
    private var pendingNote: String? = null
    private var pendingColor: Int? = null

    /**
     * 本次是否真的提交过颜色。
     *
     * `null` 既表示「选默认色」又表示「没动过」，必须另设标志位区分，
     * 否则用户在已着色的日程上选「默认」会清不掉颜色。
     */
    private var colorTouched = false

    /** 业务方实现的只读详情弹窗（AD-23）；组件不提供任何 View。 */
    private var detailPopup: EventDetailPopup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 采集 §12.1 性能指标时用的数据量；默认 0 = 沿用手工挑选的 11 条。
        val requested = intent?.getIntExtra(EXTRA_EVENT_COUNT, 0) ?: 0
        if (requested > events.size) {
            events.addAll(SampleEvents.generated(requested - events.size))
        }
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                maxOf(bars.bottom, cutout.bottom),
            )
            insets
        }

        timeline = findViewById(R.id.timeline)
        detailPopup = EventDetailPopup(this, timeline)
        // 布局 XML 里已通过 app:dtXxx 配置了尺寸与行为（FC-005 的界面配置侧）。
        // 这里再用代码配置补充一项 XML 没覆盖的字段，验证两条路径可叠加。
        timeline.setConfig(TimelineConfig(defaultNewDurationMinutes = 30))

        // 是否接管编辑层：接管时本 demo 弹自己的表单（AD-22）；
        // 不接管时组件自己管编辑态，确认出口由 [installDragEndAutoConfirm] 补。
        val takeOverEditLayer = intent?.getBooleanExtra(EXTRA_SHOW_EDIT_FORM, false) == true

        // ── 第四层：接管编辑层，弹出本 demo 自己的表单（AD-22）──
        timeline.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(draft: EditDraft): Boolean {
                // **默认不接管**：长按直接进组件的拖拽手柄态（FI-004）。
                //
                // 这是产品/demo 侧的取舍，不是组件缺陷——不接管时组件自己管编辑态，
                // 于是 FI-010「点外部区域视为取消」生效（接管时它被关掉），
                // 而「松手确认」由下面 [installDragEndAutoConfirm] 提供。
                // 想看 AD-22 的接管链路（业务方弹自己的表单）用
                // `am start --ez show_edit_form true`。
                if (!takeOverEditLayer) {
                    android.util.Log.i(TAG, "不接管编辑层，长按直接进拖拽手柄态")
                    return false
                }
                showForm(draft)
                return true // 接管：组件不再响应 FI-010，退出路径交给我们
            }
        }

        // 默认路径的「拖完松手即确认」。接管路径**不装**：
        // 那时表单才是确认入口，两个出口同时生效会互相抢（松手先把草稿提交了）。
        if (!takeOverEditLayer) installDragEndAutoConfirm()

        // 「完成 / 取消 / 删除」三个出口由业务方自己的界面提供：表单（`sheet_event_form.xml`）
        // 与详情弹窗（`popup_event_detail.xml`）里各有一套。组件不内置按钮——
        // PRD v1.22 已把「可配置是否内置」这项需求取消，这是确定的行为，不是待补的缺口。

        timeline.listener = object : TimelineListener {
            /**
             * 单击 → 弹**只读详情**（AD-23）。
             *
             * 组件行为不变（FI-003 仍然照常触发选中与本回调），弹窗完全由业务方实现。
             * 长按仍进拖拽编辑态（FI-004 / FI-006 / FI-007 是 P0，不受影响）。
             */
            override fun onEventClick(event: TimelineEvent) {
                android.util.Log.i(TAG, "点击 ${event.id} → 弹详情")
                detailPopup?.show(event.id)
            }

            override fun onEventLongClick(event: TimelineEvent) {
                android.util.Log.i(TAG, "长按 ${event.id} → 进入编辑态（拖拽调时间）")
            }

            override fun onEventCreated(range: IntRange, content: CharSequence?) {
                android.util.Log.i(TAG, "新建 $range「$content」")
                events.add(
                    DemoEvent(
                        id = "local_${range.first}_${range.last}",
                        from = range.first,
                        to = range.last,
                        title = content?.toString(),
                        note = pendingNote.orEmpty(),
                        color = if (colorTouched) pendingColor else null,
                    ),
                )
                timeline.submitEvents(events)
            }

            override fun onEventModified(
                event: TimelineEvent,
                range: IntRange,
                content: CharSequence?,
                hasConflict: Boolean,
            ) {
                android.util.Log.i(TAG, "修改 ${event.id} → $range，标题=$content，冲突=$hasConflict")
                val i = events.indexOfFirst { it.id == event.id }
                if (i >= 0 && !hasConflict) {
                    val old = events[i]
                    events[i] = old.copy(
                        from = range.first,
                        to = range.last,
                        // content 为 null 表示未改标题，沿用原值；"" 表示清空
                        title = content?.toString() ?: old.title,
                        note = pendingNote ?: old.note,
                        color = if (colorTouched) pendingColor else old.color,
                    )
                    timeline.submitEvents(events)
                }
            }

            override fun onEventDeleted(event: TimelineEvent) {
                android.util.Log.i(TAG, "删除 ${event.id}")
                events.removeAll { it.id == event.id }
                timeline.submitEvents(events)
            }

            // D3：取消只允许用于统计编辑完成率，不得据此改数据
            override fun onEditCancelled() {
                android.util.Log.i(TAG, "编辑取消（无数据变更）")
            }

            override fun onDataIssues(issues: List<DataIssue>) {
                android.util.Log.w(TAG, "数据异常 ${issues.size} 条")
            }
        }
        timeline.submitEvents(events)
        scheduleFirstFrameProbe()
    }

    /**
     * 默认路径的「拖完松手即确认」——**业务方侧**补上的确认出口。
     *
     * ## 为什么必须有它
     *
     * 组件在拖拽抬手后**刻意不提交**，只保留编辑态等业务方确认
     * （PRD §8.2「抬起 → 保留编辑态，等待用户确认或取消」；
     * D3 要求「完成」是唯一的数据变更出口）。
     * 而 PRD v1.22 又取消了组件内置的「完成 / 取消 / 删除」按钮，
     * 于是不接管时**没有任何确认入口，拖完松手等于放弃**——本方法补的就是那个出口。
     *
     * ## 怎么知道「松手了」
     *
     * 组件没有「拖拽结束」的回调；要加一个就得动对外签名（`EditController`
     * 未开 `-Xjvm-default=all`，Java 接入方实现方得跟着改）。所以这里在**业务方侧**
     * 观察触摸流：`View.dispatchTouchEvent` 会**先**调 `OnTouchListener` 再调
     * `onTouchEvent`，而本方法恒返回 `false` 不消费事件，组件的手势照旧收得到。
     *
     * ## 三条必须守住的边界
     *
     * 1. **只在真的拖过之后确认**（位移超过 `scaledTouchSlop`）。否则长按抬手那一帧
     *    组件还没建编辑态、点外部取消（FI-010）那一帧本该取消——两者都会变成「确认」。
     * 2. **只在 [DayTimelineView.isEditing] 为真时确认**。拖动滚动（Intent.Scroll）
     *    同样有位移，不看编辑态会把「滑动时间轴」变成「提交一堆日程」。
     * 3. **`ACTION_CANCEL` 不确认**。组件在取消路径上刻意不分派任何意图
     *    （`DayTimelineView.kt:1202`）——手势被系统或父容器打断，
     *    不等于用户认可了这次拖动。
     *
     * 代价要说明白：**松手即落库，没有撤销**。用户拖错了只能再拖一次改回来。
     * 真实业务若要撤销，得另配一个「取消」出口（FI-010 点外部仍在）。
     */
    private fun installDragEndAutoConfirm() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var draggedThisGesture = false
        timeline.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    draggedThisGesture = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - downY
                    if (dy > touchSlop || dy < -touchSlop) draggedThisGesture = true
                }
                MotionEvent.ACTION_UP -> {
                    if (draggedThisGesture && timeline.isEditing()) {
                        android.util.Log.i(TAG, "拖拽松手 → confirmEdit()")
                        timeline.confirmEdit()
                    }
                }
                else -> Unit
            }
            false // 恒不消费：只观察，组件的手势与命中判定一行未改
        }
    }

    /**
     * 记录「`onCreate` 起点 → 时间轴首帧绘制完成」的耗时（PRD §12.1 首次显示耗时）。
     *
     * **刻意从 `onCreate` 起算，而不是用 `am start -W`。** §12.1 写的是
     * 「不含 App 自身启动」——`am start -W` 的 `ThisTime` 把进程创建、
     * `Application.onCreate`、类加载全算进去了，那不是组件的耗时。
     * 这里从 `Activity.onCreate` 第一行起算，天然排除了进程与 Application 阶段。
     *
     * 用 `OnPreDrawListener` 而不是 `post{}`：`post` 排在消息队列末尾，
     * 量到的是「这一帧画完之后」而不是「这一帧开始画」；`OnPreDraw` 更贴近
     * 「首帧呈现」的定义，且**只触发一次**（首帧 draw 之后立刻摘掉监听）。
     *
     * 仅在传入 `--ez first_frame_ms true` 时输出，正常使用不留任何日志。
     */
    private fun scheduleFirstFrameProbe() {
        if (!intent?.getBooleanExtra(EXTRA_FIRST_FRAME_MS, false)!!) return
        val startNanos = System.nanoTime()
        val view = timeline
        view.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    android.util.Log.i(
                        TAG,
                        "FIRST_FRAME_MS=$elapsedMs event_count=${events.size}",
                    )
                    return true
                }
            },
        )
    }

    // ---- 业务方自建的表单（组件零参与）----

/**
 * 「编辑」入口失败时的兜底（[DayTimelineView.enterEditModeAndNotify] 返回 false）。
 *
 * 正常路径不会走到这里：表单由 `EditController.onEnterEditing` 在进入编辑态的
 * 回调里弹出，**不需要**业务方再弹一次——那正是「点编辑弹出两个表单」的成因。
 */
fun onDetailEditFailed(eventId: String) {
    android.util.Log.w(TAG, "进入编辑态失败（id=$eventId）")
    android.widget.Toast.makeText(this, "日程已不存在，无法编辑", android.widget.Toast.LENGTH_SHORT)
        .show()
}

    private fun showForm(draft: EditDraft) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_event_form, null)
        dialog.setContentView(view)

        // 本次表单是否走了「确定」：区分「确定」与「取消/被关掉」两种 dismiss 路径
        var submitted = false
        val existing = events.firstOrNull { it.id == draft.event?.id }

        // ── 用 draft 预填（此前业务方拿不到草稿，无法预填）──
        view.findViewById<android.widget.TextView>(R.id.form_heading).setText(
            if (draft.isCreating) R.string.form_create_heading else R.string.form_edit_heading,
        )
        val titleInput = view.findViewById<android.widget.EditText>(R.id.form_title)
        val startInput = view.findViewById<android.widget.EditText>(R.id.form_start)
        val endInput = view.findViewById<android.widget.EditText>(R.id.form_end)
        val noteInput = view.findViewById<android.widget.EditText>(R.id.form_note)

        titleInput.setText(draft.content?.toString().orEmpty())
        startInput.setText(MinuteOfDay.ofMinute(draft.range.first).toString())
        endInput.setText(MinuteOfDay.ofMinute(draft.range.last).toString())
        // 备注是业务方自己的字段，组件不提供，只能从数据源取
        noteInput.setText(existing?.note.orEmpty())

        // ── 业务色选择（PRD §7.7.1 第三条通道）──
        var pickedColor: Int? = existing?.color
        buildColorRow(view.findViewById(R.id.form_color_row)) { pickedColor = it }

        view.findViewById<Button>(R.id.form_cancel).setOnClickListener {
            // 只 dismiss；取消交给下面的 onDismiss 统一处理，避免两条路径各写一遍
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.form_confirm).setOnClickListener {
            submitted = true
            dialog.dismiss()
            submitForm(draft, startInput, endInput, titleInput, noteInput, pickedColor)
        }

        // BottomSheetDialog 默认可取消：返回键与「点外面」都会走到 onDismiss，
        // 且此时**不会**触发上面两个按钮。若不在这里收尾，接管态下 FI-010 已失效
        //（DayTimelineView.editTakenOver），用户会卡在「表单关了但草稿还开着」——
        // 正是 AD-22 要杜绝的那种不一致，只是方向反了过来。
        // confirmEdit() 已把 editSession 置空，故重复调用 cancelEdit() 是安全的空操作。
        dialog.setOnDismissListener {
            if (!submitted) timeline.cancelEdit() // D3：取消不发任何数据变更
        }

        dialog.show()

        // **必须强制展开。** BottomSheet 的默认行为在内容放不下时（矮屏 / 横屏 /
        // 大字体）会停在 collapsed 中间态，只露出顶部一条，实测在 1280×800 的
        // 横屏设备上整张表单只显示约 128px——底部的「取消 / 确定」整排落在屏幕外，
        // **用户根本点不到**（仪器测试报「可见面积占比 0.0」）。
        //
        // 表单本身已是 `ScrollView`，内容再高也能滚，所以让它一路展开即可。
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun submitForm(
        draft: EditDraft,
        startInput: android.widget.EditText,
        endInput: android.widget.EditText,
        titleInput: android.widget.EditText,
        noteInput: android.widget.EditText,
        color: Int?,
    ) {
        // 时间解析失败就回落到草稿值——非法值交给组件合法化即可（PRD §8.3.1）
        val start = MinuteOfDay.parse(startInput.text.toString())?.minuteOfDay
            ?: draft.range.first
        val end = MinuteOfDay.parse(endInput.text.toString())?.minuteOfDay
            ?: draft.range.last

        // 三者都是「本次表单确实提交过」的业务方字段，一律整值写回。
        // 不能用 `?: 旧值` 的写法：那会让「清空」与「不改」无法区分——
        // 用户清空标题后旧标题会悄悄复活（EditResult.content 的三态语义）。
        pendingNote = noteInput.text.toString()
        pendingColor = color
        colorTouched = true

        // 标题：空输入框映射为 "" 表示**清空**（PRD 允许空标题），
        // 而不是 null（null 的语义是「不改标题」）
        val title: CharSequence = titleInput.text.toString()

        // 灌值 → 提交，两步分离（D3：confirmEdit 仍是唯一数据变更出口）
        timeline.applyEdit(EditResult(range = start..end, content = title))
        timeline.confirmEdit()
    }

    private fun buildColorRow(row: LinearLayout, onPick: (Int?) -> Unit) {
        val options = listOf(
            getString(R.string.form_color_default) to null,
            getString(R.string.event_blue) to getColor(R.color.event_blue),
            getString(R.string.event_green) to getColor(R.color.event_green),
            getString(R.string.event_orange) to getColor(R.color.event_orange),
            getString(R.string.event_purple) to getColor(R.color.event_purple),
        )
        options.forEach { (label, value) ->
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener { onPick(value) }
            }
            if (value != null) btn.setBackgroundColor(value)
            row.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    companion object {
        private const val TAG = "DayTimelineDemo"

        /**
         * `--ei event_count 200`：把 demo 的日程数扩到指定条数（PRD §12.1 的基准数据量）。
         *
         * **为什么要这个入口**：demo 原本写死 11 条，每一条都在刻意覆盖一条布局规则
         * （重叠 / 包含 / 相邻 / 极短 / 跨全天），因而**没有**数量入口。
         * 而 §12.1 的「首次显示耗时」「滚动流畅度」都以**单日 200 条**为基准——
         * 拿 11 条去测，数字再好看也不构成验收证据。
         *
         * 手写的 11 条**始终保留**（含 `standup`，仪器测试依赖它），补足的部分由
         * [SampleEvents.generated] 按固定种子生成，所以失败可原样复现。
         */
        const val EXTRA_EVENT_COUNT = "event_count"

        /** `--ez first_frame_ms true`：输出首帧耗时日志，见 [scheduleFirstFrameProbe]。 */
        const val NANOS_PER_MILLI = 1_000_000.0

        const val EXTRA_FIRST_FRAME_MS = "first_frame_ms"

        /**
         * `--ez show_edit_form true`：长按**弹出**业务方自己的表单（AD-22 接管链路）。
         *
         * **默认 `false`**：长按直接进组件的拖拽手柄态（FI-004）。
         * 取这个默认值是因为长按最常用的意图就是**拖动**，
         * 而表单一弹出来就是模态窗口，拖拽手势根本发不出去。
         *
         * 代价（写在这里，别让下一个人以为是 bug）：
         * `false` 时组件自己管编辑态，于是 FI-010「点外部区域视为取消」生效，
         * 而 PRD v1.22 已取消组件内置的「完成/取消/删除」按钮——
         * 确认入口由 demo 自己补上（见 [installDragEndAutoConfirm]，拖完松手即确认）。
         * 代价是**拖错了没有撤销**，松手即落库。
         *
         * `MainActivityCancelPathTest` 那 7 条正是靠 `true` 测取消链路的
         * （D3 / PRD Q1），因此它们启动时会显式带上这个 extra。
         */
        const val EXTRA_SHOW_EDIT_FORM = "show_edit_form"
    }
}

/** 演示用的业务方数据实现，展示业务方如何「实现约定」而不继承基类（FD-001）。 */
private data class DemoEvent(
    override val id: String,
    val from: Int,
    val to: Int,
    val title: String?,
    val note: String = "",
    /** 业务色（PRD §7.7.1 第三条通道）；null 表示用组件默认配色。 */
    override val color: Int? = null,
) : TimelineEvent {
    override val start: MinuteOfDay get() = MinuteOfDay.ofMinute(from)
    override val end: MinuteOfDay get() = MinuteOfDay.ofMinute(to)
    override val content: CharSequence? get() = title
}

/**
 * demo 夹具数据。
 *
 * MagicNumber 豁免：这里的「几点几分」**就该**是字面量，否则 `at(9, 30)` 要写成
 * `at(540, 30)` 之类，反而看不出是几点。这些数字不参与任何计算，只被传给
 * [DemoEvent] 的构造参数。
 */
@Suppress("MagicNumber")
private object SampleEvents {

    /**
     * `at(9, 30)` → 当天第 570 分钟。
     *
     * 刻意提供这个辅助函数而不是到处写 `9 * 60 + 30`：
     * demo 数据要一眼看出「几点到几点」，写成分钟数反而更难核对。
     */
    private fun at(hour: Int, minute: Int = 0): Int = hour * 60 + minute

    /**
     * 生成 [count] 条日程，补足到 §12.1 的基准数据量。
     *
     * ## 为什么要自己写 LCG
     *
     * 刻意**不用 `kotlin.random`**：真机采性能数据要能复现，
     * 种子固定的 LCG 保证「同一台设备上隔一天再采，布局完全一致」。
     * 这与 `PerformanceBenchmarkTest` 的做法一致。
     *
     * ## 分布
     *
     * §12.1 要求「覆盖从稀疏到密集的典型区间」，因此按**时段**分层而不是均匀撒：
     * 上午与下午各放一段密集区（制造真实的多列重叠），中午与夜间稀疏。
     * 时长 15–120 分钟，避免全部等长导致分栏结果退化。
     *
     * 全部落在当天 00:00–23:59 内且 `from < to`，不制造非法数据——
     * 组件对脏数据是「容错 + 事件上抛」，在这里造脏数据只会让
     * 性能数据失真（组件要多走一遍容错分支）。
     */
    fun generated(count: Int): List<DemoEvent> {
        if (count <= 0) return emptyList()
        var seed = 20261014L
        fun next(bound: Int): Int {
            seed = seed * 25214903917L + 11L
            val v = (seed ushr 16) and 0x7FFFFFFF
            return (v % bound).toInt()
        }

        val out = ArrayList<DemoEvent>(count)
        repeat(count) { i ->
            // 时段：0-59 与 720-1079 分钟（上午 / 下午）各占 40%，其余平摊到全天
            val start = when (next(100)) {
                in 0..39 -> next(4 * 60)
                in 40..79 -> 720 + next(4 * 60)
                else -> next(24 * 60)
            }
            val duration = 15 + next(105)
            val end = (start + duration).coerceAtMost(24 * 60 - 1)
            // start 必定 < end：start 最大 1439，而 duration ≥ 15 时若越界已被上面夹到 1439，
            // 这里再兜一次底，确保不存在 from >= to 的非法数据。
            val safeStart = start.coerceAtMost((end - 1).coerceAtLeast(0))
            out += DemoEvent("gen_$i", safeStart, end, "生成日程 $i")
        }
        return out
    }

    fun today(): List<DemoEvent> = listOf(
        DemoEvent("standup", at(9), at(9, 30), "站会"),
        // 与 standup 重叠
        DemoEvent("design_review", at(9, 15), at(10), "设计评审"),
        // 完全包含在 design_review 内
        DemoEvent("quick_sync", at(9, 20), at(9, 25), "同步"),
        // 复用 design_review 结束后空出的列
        DemoEvent("hiring", at(10), at(11), "面试"),
        // 首尾相接，判定为不重叠
        DemoEvent("lunch", at(12), at(13), "午休"),
        // 右侧相邻列在纵向范围内为空 → 向右扩展占满整行（W3）
        DemoEvent("focus_block", at(14), at(16), "深度工作"),
        // 三条互相重叠
        DemoEvent("a", at(16), at(17), "并行任务 A"),
        DemoEvent("b", at(16, 20), at(17), "并行任务 B"),
        DemoEvent("c", at(16, 40), at(17), "并行任务 C"),
        // 极短日程：低于最小显示高度
        DemoEvent("ping", at(19), at(19, 2), "提醒"),
        // 跨越全天
        DemoEvent("oncall", at(8), at(20), "值班"),
    )
}
