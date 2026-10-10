package com.github.kevinvane.daytimeline.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        timeline = findViewById(R.id.timeline)
        detailPopup = EventDetailPopup(this, timeline)
        // 布局 XML 里已通过 app:dtXxx 配置了尺寸与行为（FC-005 的界面配置侧）。
        // 这里再用代码配置补充一项 XML 没覆盖的字段，验证两条路径可叠加。
        timeline.setConfig(TimelineConfig(defaultNewDurationMinutes = 30))

        // ── 第四层：接管编辑层，弹出本 demo 自己的表单（AD-22）──
        timeline.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(draft: EditDraft): Boolean {
                showForm(draft)
                return true // 接管：组件不再响应 FI-010，退出路径交给我们
            }
        }

        // 「完成 / 取消 / 删除」三个出口由业务方自己的界面提供：表单（`sheet_event_form.xml`）
        // 与详情弹窗（`popup_event_detail.xml`）里各有一套。组件不内置按钮（PRD §7.7 的
        // 「可配置是否内置」尚未实现，见技术方案 §9.4 第 1 条）。

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
