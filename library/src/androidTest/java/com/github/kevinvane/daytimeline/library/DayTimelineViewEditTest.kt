package com.github.kevinvane.daytimeline.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.endMinute
import com.github.kevinvane.daytimeline.library.api.startMinute
import com.github.kevinvane.daytimeline.library.api.EditDraft
import com.github.kevinvane.daytimeline.library.api.EditResult
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.api.TimelineListener
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 编辑闭环的真机测试：`confirmEdit` / `cancelEdit` / `requestDelete` 三个出口 + 第四层接管。
 *
 * 这四个出口此前**零测试覆盖**，而它们正是 D3 红线（取消绝不产生数据变更通知）
 * 在 View 层的落点。`EditSession` 的纯 JVM 单测只能证明状态机本身干净，
 * 证不了「View 有没有在取消路径上顺手发了通知」——必须在这层兜住。
 *
 * 断言方式刻意用**「记录全部回调」**而非逐个回调计数：
 * 新增一个数据变更回调时，这个测试会立刻失败，而不是悄悄漏检。
 */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewEditTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    /** 收到过的全部回调，按名字记录——任何一个都不该在取消路径上出现。 */
    private val fired = mutableListOf<String>()
    private var lastRange: IntRange? = null
    private var lastModifiedId: String? = null
    private var lastDeletedId: String? = null
    private var confirmTitle: String? = null
    private var lastCreatedContent: CharSequence? = null
    private var lastModifiedContent: CharSequence? = null

    private data class Ev(
        override val id: String,
        private val s: Int,
        private val e: Int,
        override val content: CharSequence? = null,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    @Before
    fun setUp() {
        fired.clear()
        lastRange = null
        lastModifiedId = null
        lastDeletedId = null
        confirmTitle = null
        lastCreatedContent = null
        lastModifiedContent = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
        }
        // 本文件测的是**编辑闭环**，不是首次定位（FI-012），而下述用例全部依赖
        // 「从 00:00 起算」——它们用 visibleBlockSnapshot 找 01:00 的日程。
        // 不关掉自动定位，结论会随运行时刻变化：下午跑时当前时间已被定位到，
        // 01:00 的块滚出视口，`enterEditByAccessibility` 直接前置失败。
        // 这也是 FI-012「可配置关闭」的正当用途：需要确定起点的场景显式关掉它。
        view.setConfig(TimelineConfig(autoLocateOnFirstShow = false))
        view.listener = object : TimelineListener {
            override fun onEventClick(event: TimelineEvent) { fired += "click" }

            override fun onEventLongClick(event: TimelineEvent) { fired += "longClick" }

            override fun onEventCreated(range: IntRange, content: CharSequence?) {
                fired += "created"
                lastRange = range
                lastCreatedContent = content
            }

            override fun onEventModified(
                event: TimelineEvent,
                range: IntRange,
                content: CharSequence?,
                hasConflict: Boolean,
            ) {
                fired += "modified"
                lastModifiedId = event.id
                lastRange = range
                lastModifiedContent = content
            }

            override fun onEventDeleted(event: TimelineEvent) {
                fired += "deleted"
                lastDeletedId = event.id
            }

            override fun onEditCancelled() { fired += "cancelled" }
        }
        view.submitEvents(
            listOf(
                // 时间**刻意选在一天最前段**：格高随密度放大（56dp × density），
                // 排在 9:00/14:00 的用例在高密度屏上会落到折叠线以下，
                // 导致「视口内找不到该日程」——那是测试的脆弱性，不是组件的缺陷。
                //
                // ⚠️ 与此并列的是：本文件已按 FI-012 关掉首次定位（见 setUp），
                // 否则同样有「视口内找不到」的问题——那种才是**真机时刻依赖**，
                // 上午通过、下午失败，且报错完全不指向原因。
                Ev("morning", 60, 120, "会议评审"),
                Ev("afternoon", 180, 240, "深度工作"),
            ),
            notifyIssues = false,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    /**
     * D3 核心红线：**取消不得发出任何数据变更通知**。
     *
     * 此前这条只有 `EditSessionTest` 在纯 JVM 层验证过；View 层的
     * `cancelEdit()` 有没有顺手发通知，没有任何测试能抓住。
     */
    @Test
    fun cancelEmitsNoDataChangeAtAll() {
        enterEditByAccessibility("morning")
        assertTrue("前置条件：应已进入编辑态", view.isEditing())
        fired.clear() // 滤掉进入编辑态时的 longClick

        view.cancelEdit()

        assertFalse("取消后必须退出编辑态", view.isEditing())
        assertEquals("取消只允许触发 onEditCancelled，实际回调：$fired", listOf("cancelled"), fired)
    }

    /** 确认修改：必须上抛 onEventModified，且带上正确的 id 与新范围。 */
    @Test
    fun confirmModificationEmitsEventModified() {
        enterEditByAccessibility("morning")
        fired.clear() // 滤掉进入编辑态时的 longClick

        view.confirmEdit()

        assertTrue("应触发 onEventModified，实际回调：$fired", fired.contains("modified"))
        assertEquals("回传的应是修改前的那条日程", "morning", lastModifiedId)
        assertEquals(60..120, lastRange)
        assertEquals("未改动标题时应回传原标题", "会议评审", lastModifiedContent?.toString())
        assertFalse("确认后必须退出编辑态", view.isEditing())
        assertFalse("确认路径不应触发取消回调", fired.contains("cancelled"))
    }

    /**
     * 回归：`EditController.onDelete()` 返回 true（业务方接管）时，
     * 必须把 `editSession` / `selectedId` / `grabbedHandle` 三项状态一并清干净，
     * **并且要触发刷新**。
     *
     * 修复前该分支只清了 `editSession`：选中描边与编辑层会一直留在屏幕上，
     * 直到下一次无关刷新才消失；而 `requestDelete()` 是业务方按钮调的、
     * 不经过 `onTouchEvent`，所以不会有任何自动 invalidate 兜底。
     */
    @Test
    fun requestDeleteTakenOverByControllerClearsAllEditState() {
        enterEditByAccessibility("morning")
        view.editController = object : DayTimelineView.EditController {
            override fun onDelete(): Boolean = true
        }
        fired.clear()

        val signatureBefore = readLong("lastRenderSignature")
        view.requestDelete()

        assertFalse("接管后必须退出编辑态", view.isEditing())
        assertNull("接管后不得残留选中态，否则选中描边会留在屏幕上", readString("selectedId"))
        assertEquals("接管后不得残留手柄抓取状态", 0, readInt("grabbedHandle"))
        assertTrue(
            "接管后必须触发刷新（requestRefresh），否则界面不重绘；" +
                "签名未变说明 requestRefresh 没跑：before=$signatureBefore",
            readLong("lastRenderSignature") != signatureBefore,
        )
        assertTrue("接管删除不得发出任何删除/创建/修改事件，实际回调：$fired", fired.isEmpty())
    }

    /**
     * 未被接管时走二次确认 → 确认后真删并上抛 `onEventDeleted`。
     *
     * 这里覆写 `confirmDelete` 直接确认，避开真机上 `AlertDialog` 的时序，
     * 本测试只关心「确认之后」的状态与回调，不关心对话框本身。
     */
    @Test
    fun confirmedDeleteRemovesEventAndEmitsOnEventDeleted() {
        enterEditByAccessibility("morning")
        view.editController = object : DayTimelineView.EditController {
            override fun onDelete(): Boolean = false
            override fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit) {
                confirmTitle = eventTitle
                onConfirmed()
            }
        }
        fired.clear()

        view.requestDelete()

        assertTrue("确认后应触发 onEventDeleted，实际回调：$fired", fired.contains("deleted"))
        assertEquals("删除的应是当前编辑的那条", "morning", lastDeletedId)
        assertEquals("二次确认应带上日程标题", "会议评审", confirmTitle)
        assertFalse("删除后必须退出编辑态", view.isEditing())
        assertNull("删除后不得残留选中态", readString("selectedId"))
        // 断言数据层而非 visibleBlockSnapshot：默认格高下 14:00 的块在 1920px 视口内
        // 位于折叠线以下（scrollOffset=0），视口快照会是空的，与删除是否成功无关。
        assertEquals("被删的日程应从列表移除", listOf("afternoon"), readEventIds())
        // 接管标志必须一并清零：否则下一次进入编辑态时，若业务方未接管，
        // FI-010 会被上一轮的残留标志误伤，点外部将不再取消
        assertEquals("删除后接管标志必须清零", false, readBoolean("editTakenOver"))
    }

    /**
     * 回归：`editTakenOver` 若在删除路径上漏清，下一轮编辑会失去 FI-010。
     *
     * 场景：接管 → 删除 → 摘掉 controller → 再进编辑态 → 点外部本应取消。
     * 若上一步没清标志，这次点外部会被静默忽略。
     */
    @Test
    fun takeoverFlagIsClearedAfterDelete() {
        view.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(draft: EditDraft): Boolean = true
        }
        enterEditByAccessibility("morning")
        assertEquals("应已接管", true, readBoolean("editTakenOver"))

        // 走默认删除确认：覆写 confirmDelete 直接确认，避开对话框时序
        view.editController = object : DayTimelineView.EditController {
            override fun onDelete(): Boolean = false
            override fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit) =
                onConfirmed()
        }
        view.requestDelete()
        assertFalse("删除后应退出编辑态", view.isEditing())
        assertFalse("删除后接管标志必须清零", readBoolean("editTakenOver"))

        // 不再接管，重新进入编辑态后 FI-010 必须恢复生效
        view.editController = null
        enterEditByAccessibility("afternoon")
        fired.clear()
        tapOutsideEditBlock()
        assertFalse("接管标志残留会让 FI-010 失效，实际：$fired", view.isEditing())
    }

    /**
     * 空白处点击 → 新建草稿，落在点击位置，时长取默认（PRD FI-005）。
     *
     * **不断言具体分钟数**：点击位置随设备格高变化，写死 03:00 会让高密度屏失败。
     * 这里只断言可判定的性质——时长等于配置值、范围落在全天之内。
     */
    @Test
    fun tappingEmptySpaceCreatesDraftAndConfirmEmitsCreated() {
        view.setConfig(TimelineConfig(defaultNewDurationMinutes = 30))

        tapOutsideEditBlock()

        assertTrue("点空白应进入新建编辑态", view.isEditing())
        assertTrue("点空白不得触发长按回调，实际回调：$fired", !fired.contains("longClick"))
        fired.clear()

        view.confirmEdit()

        assertTrue("新建确认应触发 onEventCreated，实际回调：$fired", fired.contains("created"))
        val r = requireNotNull(lastRange) { "onEventCreated 未携带起止时间" }
        assertEquals("新建时长应等于配置的默认时长", 30, r.last - r.first)
        assertTrue("新建范围应落在全天之内，实际 $r", r.first >= 0 && r.last <= 1440)
        assertFalse("新建确认后必须退出编辑态", view.isEditing())
    }

    /** 未处于编辑态时调用三个出口都必须是安全的空操作（不得崩、不得发事件）。 */
    @Test
    fun editApisAreSafeWhenNotEditing() {
        assertFalse(view.isEditing())
        view.confirmEdit()
        view.cancelEdit()
        view.requestDelete()

        assertTrue("非编辑态下三个出口都不应触发任何回调，实际回调：$fired", fired.isEmpty())
    }

    // ===== AD-22：第四层接管 + 表单输入 =====

    /**
     * `onEnterEditing` 必须拿到可预填的草稿：正在编辑哪条、当前起止、当前标题。
     *
     * 这是此前完全缺失的能力——`EditSession` 是 View 私有字段，业务方拿不到，
     * 只能轮询 `isEditing()` 并自己猜。
     */
    @Test
    fun enterEditingHandsOverAFillableDraft() {
        var draft: EditDraft? = null
        view.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(d: EditDraft): Boolean {
                draft = d
                return true
            }
        }

        enterEditByAccessibility("morning")
        fired.clear()

        val d = draft
        assertNotNull("应收到草稿", d)
        assertFalse("修改态 isCreating 应为 false", d!!.isCreating)
        assertEquals("草稿应带上被编辑的日程", "morning", d.event?.id)
        assertEquals("草稿应带上当前起止时间", 60..120, d.range)
        assertEquals("草稿应带上当前标题，供表单预填", "会议评审", d.content?.toString())
        assertEquals("草稿不应触发任何数据变更回调，实际回调：$fired", emptyList<String>(), fired)
    }

    /**
     * 接管后 **FI-010 失效**：点编辑块外部不再视为取消（PRD §8.2.1）。
     *
     * 回归场景：业务方弹的非模态表单会被点穿，若组件仍执行 FI-010，
     * 草稿会被取消而表单还开着，业务方随后提交时操作的是已死的编辑态。
     */
    @Test
    fun tappingOutsideDoesNotCancelWhenControllerHasTakenOver() {
        view.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(draft: EditDraft): Boolean = true
        }
        enterEditByAccessibility("morning")
        assertTrue(view.isEditing())
        fired.clear()

        // 点一个远离编辑块的空白处
        tapOutsideEditBlock()

        assertTrue("接管后点外部不得取消编辑态", view.isEditing())
        assertEquals("接管后点外部不得触发任何回调，实际回调：$fired", emptyList<String>(), fired)
    }

    /** 对照组：未接管时 FI-010 仍然生效，语义与「取消」完全一致。 */
    @Test
    fun tappingOutsideStillCancelsWhenNotTakenOver() {
        enterEditByAccessibility("morning")
        assertTrue(view.isEditing())
        fired.clear()

        tapOutsideEditBlock()

        assertFalse("未接管时点外部应取消编辑态", view.isEditing())
        assertEquals("FI-010 语义与取消一致，实际回调：$fired", listOf("cancelled"), fired)
    }

    /**
     * `applyEdit` 灌入的标题必须随 `confirmEdit` 上抛。
     *
     * 回归场景：此前 `onEventModified` 只回传**修改前**的对象，标题在编辑
     * 路径上根本改不了。
     */
    @Test
    fun applyEditCarriesNewContentThroughConfirm() {
        enterEditByAccessibility("morning")
        fired.clear()

        view.applyEdit(EditResult(range = 11 * 60..12 * 60, content = "改过的标题"))
        view.confirmEdit()

        assertTrue("应触发 onEventModified，实际回调：$fired", fired.contains("modified"))
        assertEquals("回传的新标题", "改过的标题", lastModifiedContent?.toString())
        assertEquals("回传的新时间", 11 * 60..12 * 60, lastRange)
    }

    /**
     * `EditResult.content = null` 表示**不改标题**，应沿用原值。
     *
     * 与「清空标题」区分开：清空传空串（PRD 允许空标题）。
     */
    @Test
    fun applyEditWithNullContentKeepsOriginalTitle() {
        enterEditByAccessibility("morning")
        fired.clear()

        view.applyEdit(EditResult(range = 60..120, content = null))
        view.confirmEdit()

        assertEquals("null 应表示不改标题，沿用原值", "会议评审", lastModifiedContent?.toString())
    }

    /** 新建态下 `applyEdit` 的标题应随 `onEventCreated` 上抛。 */
    @Test
    fun applyEditCarriesContentThroughCreate() {
        tapOutsideEditBlock()
        assertTrue(view.isEditing())
        fired.clear()

        view.applyEdit(EditResult(range = 3 * 60..4 * 60, content = "新的日程"))
        view.confirmEdit()

        assertTrue("应触发 onEventCreated，实际回调：$fired", fired.contains("created"))
        assertEquals("回传的新标题", "新的日程", lastCreatedContent?.toString())
        assertEquals(3 * 60..4 * 60, lastRange)
    }

    /**
     * 表单填的非法时间必须被合法化（PRD §8.3.1）。
     *
     * 这里填「25:30 → 26:00」——两端都超出全天，应被钳制而不是崩溃或产生非法日程。
     */
    @Test
    fun applyEditClampsOutOfRangeTimes() {
        enterEditByAccessibility("morning")
        fired.clear()

        view.applyEdit(EditResult(range = 1530..1560, content = "越界时间"))
        view.confirmEdit()

        assertNotNull("应仍能正常提交，实际回调：$fired", lastRange)
        val r = lastRange!!
        assertTrue("开始不得早于 00:00，实际 $r", r.first >= 0)
        assertTrue("结束不得晚于 24:00，实际 $r", r.last <= 1440)
        assertTrue("必须晚于开始，实际 $r", r.last > r.first)
    }

    /** 表单把两端填反时必须自动交换，不得产出非正时长。 */
    @Test
    @Suppress("InvalidRange")
    fun applyEditSwapsReversedRange() {
        enterEditByAccessibility("morning")
        fired.clear()

        // detekt 会报 InvalidRange，这是**故意的**：Kotlin 里 `120..60` 就是空区间，
        // 而这正是业务方把表单两端填反后最自然会构造出来的东西——
        // first 仍是 120、last 仍是 60，组件必须兜底交换而不是接受非法草稿。
        view.applyEdit(EditResult(range = 120..60, content = "填反了"))
        view.confirmEdit()

        assertEquals("应自动交换两端", 60..120, lastRange)
    }

    /** 非编辑态调 `applyEdit` 必须是安全的空操作。 */
    @Test
    fun applyEditIsSafeWhenNotEditing() {
        assertFalse(view.isEditing())
        view.applyEdit(EditResult(range = 600..660, content = "不该生效"))
        assertFalse(view.isEditing())
        assertTrue("不应触发任何回调，实际回调：$fired", fired.isEmpty())
    }

    // ===== AD-23：详情弹窗支持（组件零新增 View）=====

    /**
     * `detailOf` 返回的必须是**组件兜底修正后**的值。
     *
     * 业务方拿它直接渲染详情，若组件给的是原始脏数据（结束早于开始、超 24:00），
     * 弹窗上就会出现「10:00 – 09:00」这种内容。
     */
    @Test
    fun detailOfReturnsSanitizedValues() {
        // 结束早于开始：sanitize 应按最小时长修正
        view.submitEvents(
            listOf(Ev("dirty", 600, 500, "脏数据")),
            notifyIssues = false,
        )
        relayout()

        val detail = view.detailOf("dirty")
        assertNotNull("应取到详情", detail)
        assertEquals("dirty", detail!!.id)
        assertTrue("结束必须晚于开始，实际 ${detail.range}", detail.range.last > detail.range.first)
        assertEquals("标题应原样带出", "脏数据", detail.content?.toString())
    }

    @Test
    fun detailOfReturnsNullForUnknownId() {
        assertNull("不存在的 id 应返回 null", view.detailOf("nope"))
    }

    /** 详情快照的分钟值类型形式应与 range 一致（D18/D19：不让业务方碰裸 Int 换算）。 */
    @Test
    fun detailExposesValueTypeMinutes() {
        view.submitEvents(listOf(Ev("morning", 60, 120, "标题")), notifyIssues = false)
        relayout()
        val detail = view.detailOf("morning")!!
        assertEquals(60, detail.startMinute.minuteOfDay)
        assertEquals(120, detail.endMinute.minuteOfDay)
    }

    /**
     * `enterEditMode` 是业务方在详情弹窗里点「完成/取消/删除」的前提。
     *
     * 没有它，`confirmEdit()` 等三个出口会因 `editSession == null` 静默返回。
     */
    @Test
    fun enterEditModePutsComponentIntoEditing() {
        assertFalse(view.isEditing())
        val entered = view.enterEditMode("morning")

        assertTrue("应进入编辑态", entered)
        assertTrue(view.isEditing())
        // 进入后三个出口才可用
        fired.clear()
        view.confirmEdit()
        assertTrue("进入后 confirmEdit 应可用，实际回调：$fired", fired.contains("modified"))
    }

    /**
     * 已处于编辑态时再调 `enterEditMode` 必须**拒绝**，
     * 否则会把用户正在编辑的草稿换成另一条，用户察觉不到。
     */
    @Test
    fun enterEditModeRefusesWhenAlreadyEditing() {
        assertTrue(view.enterEditMode("morning"))
        assertFalse("已在编辑态时应拒绝", view.enterEditMode("afternoon"))

        // 仍然编辑的是 morning，不是 afternoon
        fired.clear()
        view.confirmEdit()
        assertEquals("应仍是 morning", "morning", lastModifiedId)
    }

    @Test
    fun enterEditModeReturnsFalseForUnknownId() {
        assertFalse("不存在的 id 不应进入编辑态", view.enterEditMode("nope"))
        assertFalse(view.isEditing())
    }

    /**
     * `clearSelection` 只清选中态，**不影响编辑态**。
     *
     * 详情弹窗会调用它清描边；若它把编辑态也清了，用户正在编辑的东西就没了。
     */
    @Test
    fun clearSelectionKeepsEditState() {
        view.enterEditMode("morning")
        assertTrue(view.isEditing())

        view.clearSelection()

        assertTrue("清选中不应退出编辑态", view.isEditing())
        // 编辑态仍可提交
        fired.clear()
        view.confirmEdit()
        assertTrue("编辑态应仍可用，实际回调：$fired", fired.contains("modified"))
    }

    @Test
    fun clearSelectionIsSafeWhenNothingSelected() {
        view.clearSelection() // 非编辑态、无选中：必须是空操作且不崩
        assertFalse(view.isEditing())
        assertTrue("不应触发任何回调，实际回调：$fired", fired.isEmpty())
    }

    /** `enterEditMode` 应照常触发接管回调（PRD §8.2.1 的接管语义不变）。 */
    @Test
    fun enterEditModeStillNotifiesController() {
        var draft: EditDraft? = null
        view.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(d: EditDraft): Boolean {
                draft = d
                return true
            }
        }

        assertTrue(view.enterEditModeAndNotify("morning"))

        assertNotNull("接管回调应被触发", draft)
        assertEquals("接管回调应带上被编辑的日程", "morning", draft!!.event?.id)
    }

    /**
     * 回归：`enterEditMode`（静默版）**不得**回调接管方。
     *
     * 真机上「点编辑弹出两个表单」的成因：静默进入也回调了 `onEnterEditing`，
     * 接管方弹了一次表单，业务方随后又显式弹了一次。
     *
     * 同理，业务方在详情里点「完成 / 取消 / 删除」时也不该弹出表单。
     */
    @Test
    fun enterEditModeDoesNotNotifyController() {
        var notified = false
        view.editController = object : DayTimelineView.EditController {
            override fun onEnterEditing(d: EditDraft): Boolean {
                notified = true
                return true
            }
        }

        assertTrue(view.enterEditMode("morning"))

        assertFalse("静默进入不得回调接管方，否则会多弹一次表单", notified)
        assertTrue("但必须真的进入编辑态，三个出口才可用", view.isEditing())
    }

    /** 两个入口的差别只在「是否回调接管方」，进入编辑态的行为必须一致。 */
    @Test
    fun bothEnterEditModePathsReachEditingState() {
        // 静默版
        assertTrue(view.enterEditMode("morning"))
        assertTrue(view.isEditing())
        fired.clear()
        view.cancelEdit()

        // 通知版
        assertTrue(view.enterEditModeAndNotify("morning"))
        assertTrue(view.isEditing())
        fired.clear()
        view.cancelEdit()
    }

    /** 业务色应能在详情快照里取到（AD-22 第三通道 + AD-23 详情展示）。 */
    @Test
    fun detailOfCarriesBusinessColor() {
        view.submitEvents(
            listOf(ColoredEvent("tinted", 300, 360, 0xFF3F6BDC.toInt())),
            notifyIssues = false,
        )
        relayout()

        assertEquals(
            "业务色应随详情快照带出",
            0xFF3F6BDC.toInt(),
            view.detailOf("tinted")?.color,
        )
        assertNull("无色时为 null", view.detailOf("morning")?.color)
    }

    /**
     * D3 铁律在接管路径上同样成立：`applyEdit` 之后取消，不得发出任何数据变更。
     *
     * 业务方最典型的误用就是「灌了值又反悔」，这条守住它。
     */
    @Test
    fun cancelAfterApplyEditStillEmitsNoDataChange() {
        enterEditByAccessibility("morning")
        fired.clear()

        view.applyEdit(EditResult(range = 11 * 60..12 * 60, content = "灌了值"))
        view.cancelEdit()

        assertFalse("取消后必须退出编辑态", view.isEditing())
        assertEquals("取消路径不得发出任何数据变更，实际回调：$fired", listOf("cancelled"), fired)
    }

    /** 业务色（PRD §7.7.1 第三通道）应能透传到绘制上下文。 */
    @Test
    fun businessColorFlowsIntoBlockContext() {
        view.submitEvents(
            listOf(ColoredEvent("tinted", 300, 360, 0xFF3F6BDC.toInt())),
            notifyIssues = false,
        )
        relayout()

        val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == "tinted" }
        assertNotNull("测试前置失败：视口内找不到该日程", block)
        // BlockContext 是**复用**对象（AD-14），只有真正走一遍 onDraw 才会被填充，
        // 反射读到的是上一次绘制留下的值——不 draw 就断言必然读到默认 null。
        drawOnce()
        val ctx = readBlockContext()
        assertNotNull("未取到绘制上下文", ctx)
        assertEquals("业务色应透传到 BlockContext.accentColor", 0xFF3F6BDC.toInt(), ctx!!.accentColor)
    }

    /**
     * 未提供业务色时保持 null，默认绘制回落到组件主题色。
     *
     * 同样必须先 draw——否则读到的是尚未填充的复用对象，断言会变成假通过。
     */
    @Test
    fun businessColorDefaultsToNull() {
        drawOnce()
        val ctx = readBlockContext()
        assertNotNull("未取到绘制上下文", ctx)
        assertNull("未提供业务色时 accentColor 应为 null", ctx!!.accentColor)
    }

    /** 带业务色的日程实现，验证 TimelineEvent.color 的默认实现不破坏既有实现者。 */
    private data class ColoredEvent(
        override val id: String,
        private val s: Int,
        private val e: Int,
        override val color: Int?,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    /** 反射读取复用的 BlockContext（组件每帧改写同一份，见 AD-14）。 */
    private fun readBlockContext(): com.github.kevinvane.daytimeline.library.api.BlockContext? =
        fieldOf("blockContext").get(view)
            as? com.github.kevinvane.daytimeline.library.api.BlockContext

    private fun relayout() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    /** 真正走一遍 onDraw，让复用的绘制上下文被填充。 */
    private fun drawOnce() {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
    }

    /**
     * 点一个**确定在编辑块之外**的位置，触发 / 检验 FI-010。
     *
     * 用视口底部而不是「最低块下方 N px」：编辑块的命中范围会向外扩
     * `endMargin`，紧贴块下方点击仍算「在块内」，测不出 FI-010。
     * 本文件的测试数据都排在一天前段，视口底部必然在编辑块之外。
     */
    private fun tapOutsideEditBlock() = tapAt(HEIGHT - 10)

    private fun readBoolean(name: String): Boolean =
        fieldOf(name).get(view) as Boolean

    // ---- 进入编辑态的两种方式 ----

    /** 走无障碍长按入口（等价于用户长按日程块）。 */
    private fun enterEditByAccessibility(id: String) {
        val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == id }?.second
        requireNotNull(block) { "测试前置失败：视口内找不到日程 $id" }
        view.dispatchEventLongClickForAccessibility(block)
    }

    /** 在指定视口 y 处模拟一次「按下即抬起」的点击（不移动 → 判定为 Click）。 */
    private fun tapAt(y: Int) {
        val x = WIDTH / 2
        val down = System.currentTimeMillis()
        listOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
        ).forEach { action ->
            val e = MotionEvent.obtain(down, down, action, x.toFloat(), y.toFloat(), 0)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
            e.recycle()
        }
    }

    // ---- 反射工具 ----

    private fun fieldOf(name: String) =
        DayTimelineView::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun readInt(name: String): Int = (fieldOf(name).get(view) as Number).toInt()

    private fun readLong(name: String): Long = (fieldOf(name).get(view) as Number).toLong()

    private fun readString(name: String): String? = fieldOf(name).get(view) as String?

    /** 数据层当前的日程 id 列表（不依赖视口可见性）。 */
    private fun readEventIds(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val list = fieldOf("events").get(view) as List<com.github.kevinvane.daytimeline.library.core.SanitizedEvent>
        return list.map { it.id }
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
