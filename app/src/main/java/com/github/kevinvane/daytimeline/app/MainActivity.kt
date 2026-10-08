package com.github.kevinvane.daytimeline.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.DataIssue
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

/**
 * Demo：把 [DayTimelineView] 放进页面并提交一批带真实重叠关系的数据。
 *
 * 数据故意包含 PRD §14.2 关注的形态：部分重叠、完全包含、空闲列复用、
 * 首尾相接、跨越全天、极短日程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var timeline: DayTimelineView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        timeline = findViewById(R.id.timeline)
        val events = SampleEvents.today().toMutableList()
        // 布局 XML 里已通过 app:dtXxx 配置了尺寸与行为（FC-005 的界面配置侧）。
        // 这里再用代码配置补充一项 XML 没覆盖的字段，验证两条路径可叠加。
        timeline.setConfig(TimelineConfig(defaultNewDurationMinutes = 30))

        findViewById<android.widget.Button>(R.id.btn_done).setOnClickListener { timeline.confirmEdit() }
        findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener { timeline.cancelEdit() }
        findViewById<android.widget.Button>(R.id.btn_delete).setOnClickListener { timeline.requestDelete() }

        timeline.listener = object : TimelineListener {
            override fun onEventClick(event: TimelineEvent) {
                android.util.Log.i(TAG, "点击 ${event.id}")
            }

            override fun onEventLongClick(event: TimelineEvent) {
                android.util.Log.i(TAG, "长按 ${event.id} → 进入编辑态")
            }

            override fun onEventCreated(range: IntRange) {
                android.util.Log.i(TAG, "新建 $range")
                events.add(DemoEvent("local_${range.first}_${range.last}", range.first, range.last, "新建日程"))
                timeline.submitEvents(events)
            }

            override fun onEventModified(event: TimelineEvent, range: IntRange, hasConflict: Boolean) {
                android.util.Log.i(TAG, "修改 ${event.id} → $range，冲突=$hasConflict")
                val i = events.indexOfFirst { it.id == event.id }
                if (i >= 0 && !hasConflict) {
                    events[i] = DemoEvent(event.id, range.first, range.last, event.content)
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

    companion object {
        private const val TAG = "DayTimelineDemo"
    }
}

/** 演示用的业务方数据实现，展示业务方如何「实现约定」而不继承基类（FD-001）。 */
private data class DemoEvent(
    override val id: String,
    val from: Int,
    val to: Int,
    override val content: CharSequence?,
) : TimelineEvent {
    override val start: MinuteOfDay get() = MinuteOfDay.ofMinute(from)
    override val end: MinuteOfDay get() = MinuteOfDay.ofMinute(to)
}

private object SampleEvents {

    fun today(): List<TimelineEvent> = listOf(
        DemoEvent("standup", 9 * 60, 9 * 60 + 30, "站会"),
        // 与 standup 重叠
        DemoEvent("design_review", 9 * 60 + 15, 10 * 60, "设计评审"),
        // 完全包含在 design_review 内
        DemoEvent("quick_sync", 9 * 60 + 20, 9 * 60 + 25, "同步"),
        // 复用 design_review 结束后空出的列
        DemoEvent("hiring", 10 * 60, 11 * 60, "面试"),
        // 首尾相接，判定为不重叠
        DemoEvent("lunch", 12 * 60, 13 * 60, "午休"),
        // 右侧相邻列在纵向范围内为空 → 向右扩展占满整行（W3）
        DemoEvent("focus_block", 14 * 60, 16 * 60, "深度工作"),
        // 三条互相重叠
        DemoEvent("a", 16 * 60, 17 * 60, "并行任务 A"),
        DemoEvent("b", 16 * 60 + 20, 17 * 60, "并行任务 B"),
        DemoEvent("c", 16 * 60 + 40, 17 * 60, "并行任务 C"),
        // 极短日程：低于最小显示高度
        DemoEvent("ping", 19 * 60, 19 * 60 + 2, "提醒"),
        // 跨越全天
        DemoEvent("oncall", 8 * 60, 20 * 60, "值班"),
    )
}
