package com.github.kevinvane.daytimeline.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.api.EventDetail
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.google.android.material.color.MaterialColors

/**
 * 业务方实现的**只读**日程详情弹窗（PRD §7.7.1 / AD-23）。
 *
 * ## 组件零新增 View
 *
 * 这个类整体属于 demo（业务方）。组件库只提供两个入口：
 * - [DayTimelineView.detailOf] —— 拿只读快照渲染
 * - [DayTimelineView.enterEditMode] —— 编程进入编辑态，之后才能调三个出口
 *
 * 组件**不提供**详情界面，也不持有任何业务字段。
 *
 * ## 关闭时的收尾：一条容易踩的时序坑
 *
 * [DayTimelineView.confirmEdit] / [cancelEdit] / [requestDelete] **都要求组件
 * 处于编辑态**，否则因 `editSession == null` 直接返回。而 [PopupWindow] 的
 * dismiss 监听是**异步**触发的——若在 dismiss 之后才调 `confirmEdit()`，两者会
 * 互相打架：
 *
 * ```
 * dismiss()            → onDismiss 里 cancelEdit()   // 编辑态没了
 * confirmEdit()        → editSession == null        // 静默无效
 * ```
 *
 * 所以这里用 [actionTaken] 明确区分「用户主动关掉」与「业务方已发起操作」：
 * 前者必须收尾草稿（否则草稿残留，用户再也进不去也退不出），后者不能收尾。
 */
class EventDetailPopup(
    private val activity: MainActivity,
    private val timeline: DayTimelineView,
) {

    private var popup: PopupWindow? = null

    /**
     * 本次关闭是否由业务方发起的操作导致。
     *
     * true = 「完成/取消/删除/编辑」已接管后续流程，dismiss 不得再动编辑态。
     * false = 用户点外部或按返回键关掉的，需要收尾。
     */
    private var actionTaken = false

    private companion object {
        /** 弹窗最大宽度占屏幕宽度的比例：留出边距，标题再长也不横向溢出。 */
        const val MAX_WIDTH_RATIO = 0.8f

        /** 弹窗圆角（dp）。 */
        const val CORNER_RADIUS_DP = 12f
    }

    /**
     * 展示某条日程的详情。
     *
     * ## 为什么用 [PopupWindow.showAtLocation] 而不是 `showAsDropDown`
     *
     * `showAsDropDown(anchor)` 会把弹窗左上角对齐到 anchor 的**左下角**。
     * 这里的 anchor 是整个时间轴视图（高约 24 小时格高），它的底边远在屏幕之下，
     * 弹窗会被推到屏幕外——真机表现是「只露出顶部一丝标题，其余在屏幕外」。
     *
     * 改用 `showAtLocation(..., Gravity.CENTER, ...)` 居中显示：
     * 与日程块的实际位置无关，任何滚动位置、任何屏幕尺寸都不会溢出。
     *
     * @return false 表示该日程不存在，未展示任何东西。
     */
    fun show(eventId: String): Boolean {
        val detail = timeline.detailOf(eventId) ?: return false

        // 打开弹窗前清掉选中描边：弹窗已承担「当前选中」的视觉表达，
        // 两者同时存在显得冗余（产品决策）。
        timeline.clearSelection()

        dismissInternal(clearOnDismiss = true)

        val view = LayoutInflater.from(activity).inflate(R.layout.popup_event_detail, null)
        bind(view, detail)

        // 宽度按屏幕可用宽度的 80% 取上限：标题再长也不会横向溢出。
        // 高度交给 WRAP_CONTENT，内容少时弹窗就小。
        val metrics = activity.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * MAX_WIDTH_RATIO).toInt()

        val win = PopupWindow(view, maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setOnDismissListener { onDismissed() }
            // PopupWindow 不像 Dialog 会自动套 shape，圆角与底色要自己给。
            //
            // ⚠️ 底色必须用 MaterialColors.getColor(view, attr) 读**主题属性**，
            // 不能用 ContextCompat.getColor(context, attr) —— 后者按**应用资源 ID**
            // 去查，而 attr 是框架侧的属性 ID，真机上必然抛
            // Resources$NotFoundException（编译与 lint 都发现不了）。
            setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = CORNER_RADIUS_DP * metrics.density
                    setColor(
                        MaterialColors.getColor(
                            view,
                            com.google.android.material.R.attr.colorSurface,
                        ),
                    )
                },
            )
        }
        popup = win
        actionTaken = false
        win.showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
        return true
    }

    private fun bind(view: View, detail: EventDetail) {
        // 业务色：无色时隐藏色条，不留空隙
        val colorBar = view.findViewById<View>(R.id.detail_color_bar)
        // color 来自 library 模块的可空属性，跨模块不能 smart cast，取局部变量
        val businessColor = detail.color
        if (businessColor != null) {
            colorBar.setBackgroundColor(businessColor)
            colorBar.visibility = View.VISIBLE
        } else {
            colorBar.visibility = View.GONE
        }

        // 标题：允许为空（§7.7.1），显示占位文案而不是留白
        view.findViewById<TextView>(R.id.detail_title).text =
            detail.content?.toString()?.ifBlank { null }
                ?: activity.getString(R.string.detail_untitled)

        // 时间：组件已兜底修正，可直接展示
        view.findViewById<TextView>(R.id.detail_time).text = activity.getString(
            R.string.detail_time_range,
            MinuteOfDay.ofMinute(detail.range.first).toString(),
            MinuteOfDay.ofMinute(detail.range.last).toString(),
        )

        view.findViewById<Button>(R.id.detail_done).setOnClickListener {
            // 静默进入编辑态：点「完成」不该弹出表单
            val entered = timeline.enterEditMode(detail.id)
            dismissInternal(clearOnDismiss = false)
            if (entered) timeline.confirmEdit()
        }

        view.findViewById<Button>(R.id.detail_cancel).setOnClickListener {
            val entered = timeline.enterEditMode(detail.id)
            dismissInternal(clearOnDismiss = false)
            // 取消不产生任何数据变更（D3）；未进入编辑态时 cancelEdit 本身也是空操作
            if (entered) timeline.cancelEdit()
        }

        view.findViewById<Button>(R.id.detail_delete).setOnClickListener {
            val entered = timeline.enterEditMode(detail.id)
            dismissInternal(clearOnDismiss = false)
            // 删除走二次确认（FI-011），对话框会盖在本弹窗之上，故先关掉
            if (entered) timeline.requestDelete()
        }

        view.findViewById<Button>(R.id.detail_edit).setOnClickListener {
            // 这里用 enterEditModeAndNotify：它会回调 EditController.onEnterEditing，
            // 由 MainActivity 在该回调里弹出表单（AD-22 第四层）。
            //
            // 刻意**不再**在之后显式调 showEditFormFor——那会弹第二次表单。
            val entered = timeline.enterEditModeAndNotify(detail.id)
            dismissInternal(clearOnDismiss = false)
            // 编辑态已交接给表单，不做任何收尾
            if (!entered) activity.onDetailEditFailed(detail.id)
        }
    }

    /** 主动关闭弹窗。 */
    fun dismiss() = dismissInternal(clearOnDismiss = true)

    private fun dismissInternal(clearOnDismiss: Boolean) {
        val current = popup ?: return
        actionTaken = !clearOnDismiss
        popup = null
        current.dismiss()
    }

    /**
     * PopupWindow 的 dismiss 回调（同步触发，但仍与调用方分离）。
     *
     * 两条路径在此汇合：用户点外部/返回键，以及业务方主动关闭。
     * [actionTaken] 区分二者——业务方已发起操作时**不得**再动编辑态。
     */
    private fun onDismissed() {
        popup = null
        if (actionTaken) {
            actionTaken = false
            return
        }
        // 用户只是看了详情就关掉：把可能残留的编辑态收干净。
        // 若用户是在编辑态下打开详情的，这里等于「放弃这次查看」。
        // cancelEdit() 在非编辑态下是安全的空操作，且不产生数据变更（D3）。
        timeline.cancelEdit()
    }
}
