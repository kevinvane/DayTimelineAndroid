package com.github.kevinvane.daytimeline.r8test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.api.endMinute
import com.github.kevinvane.daytimeline.library.api.startMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **混淆后**的功能验证（PRD §12.5 / T7 / 风险 R8）。
 *
 * 本模块 `testBuildType = "release"` 且 `isMinifyEnabled = true`，
 * 所以这里跑的每一行代码都经过 R8 处理。任何保留规则写漏了，都会在这里
 * 表现为崩溃、NoSuchMethodError、NoSuchFieldError 或 VerifyError。
 *
 * 这才是「业务方开启混淆后功能正常」这句话的**唯一**有效证据——
 * library 自身的 assembleRelease 不开混淆，规则写错也不会有任何东西报错。
 */
@RunWith(AndroidJUnit4::class)
class R8ConsumerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    /**
     * 记录全部回调的监听器。
     *
     * 此前本类只覆写 `onEventCreated`（存进 `createdRange`），
     * 于是取消路径的断言只能是 `assertNull(createdRange)`——
     * **`onEventModified` / `onEventDeleted` 根本没被记录**，
     * 它们若在混淆产物上被误触发，本测试不会红。
     * 这与 library 侧 `DayTimelineViewEditTest` 的白名单断言是同一个道理。
     */
    private lateinit var recorder: ConsumerApiSmoke.RecordingListener
    private var draftOnEnter: com.github.kevinvane.daytimeline.library.api.EditDraft? = null

    /**
     * 钉住滚动位置的原因：**FI-012 首次显示自动定位是按真实时钟滚动的。**
     *
     * 上面那次 `view.layout(...)` 会触发 `onSizeChanged` → `autoLocateOnFirstShow()`，
     * 把「当前时间」放到约三分之一屏处。**滚动量取决于跑测试时的墙上时间**，
     * 于是 `tapEmptySpace()` 里那个写死的 `y = HEIGHT / 8` 就不再指向固定的时间点。
     *
     * 实测（本机 API 34 模拟器）：11:24 跑时 `scrollOffset = 1622`，09:00 那条日程
     * 正好落在 y≈240，点中它 → `onEventClick` → **不进新建编辑态**，
     * `fullEditCycleWorksAfterMinification` 报「点空白应进入编辑态」；
     * 10:40 跑时同一份代码通过。**同一提交，今天绿、明天红。**
     *
     * 这里不复现 FI-012 的定位行为：本模块要验的是「混淆后对外契约是否完好」，
     * 定位由 [com.github.kevinvane.daytimeline.library] 侧的仪器测试负责。
     * 用 `setScrollOffset(0)` 显式钉住，是把「坐标前提」写成代码而不是留在注释里。
     *
     * 顺带让本类**全部**用例与时钟无关——不只出问题的这一条：
     * 另外两条也走 [tapEmptySpace]，只是它们没提交日程，本就点不中任何块，
     * 才侥幸没跟着变红。那种「靠数据凑巧躲开」的状态不该留着。
     */
    @Before
    fun setUp() {
        // View 构造会创建 Handler，必须在主线程（详见 library 的 DayTimelineViewTest）
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
            // 把滚动位置钉死在 0——理由见下方 KDoc。
            view.setScrollOffset(0)
        }
        check(view.getScrollOffset() == 0) {
            "滚动位置未钉住，测试的坐标前提不成立（见 setUp 的 KDoc）"
        }
        draftOnEnter = null

        recorder = ConsumerApiSmoke.RecordingListener()
        view.listener = recorder
    }

    /** 构造、测量、绘制全链路在混淆后仍可走通。 */
    @Test
    fun viewConstructsAndDrawsAfterMinification() {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        assertTrue("混淆后仍应画出内容", hasNonTransparentPixel(bmp))
    }

    /**
     * 自定义 View 的 XML 反射入口。
     *
     * 布局 XML 里写的是全限定类名，框架靠 `Class.forName` + 两参构造器实例化。
     * 类名或构造器被混淆，这条路直接断。
     */
    @Test
    fun viewIsInflatableFromXmlAfterMinification() {
        val cls = Class.forName("com.github.kevinvane.daytimeline.library.DayTimelineView")
        val ctor = cls.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        // View 构造会创建 Handler，必须在主线程（详见 library 的 DayTimelineViewTest）
        var inflated: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            inflated = ctor.newInstance(context, null)
        }
        assertNotNull("XML 反射构造路径应可用", inflated)
    }

    /** 数据契约接口：业务方实现并提交，混淆后仍能正常渲染。 */
    @Test
    fun businessEventImplementationWorksAfterMinification() {
        view.submitEvents(listOf(ConsumerApiSmoke.event("e1", "混淆前的标题")))
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        assertTrue(hasNonTransparentPixel(bmp))
    }

    /**
     * **Java 调用方**也能实现数据契约，且混淆后组件真的读得到（OQ-7 / 风险 R12）。
     *
     * 断言刻意不停在「没崩」：读回 title / 起止时间，证明 `JavaBusinessEvent` 实现的
     * 方法签名与组件的调用方一致——光 submit + draw 只能证明没抛异常。
     */
    @Test
    fun javaCallerCanImplementContractAfterMinification() {
        view.submitEvents(
            listOf(ConsumerApiSmoke.javaEvent("j1", "Java 业务方的日程", 540, 600)),
        )

        val detail = view.detailOf("j1")
        assertNotNull("Java 实现的契约提交后应能被组件读取", detail)
        assertEquals("content 经 Java 实现回读应一致", "Java 业务方的日程", detail!!.content?.toString())
        assertEquals("start 经 Java 实现回读应一致", 540, detail.startMinute.minuteOfDay)
        assertEquals("end 经 Java 实现回读应一致", 600, detail.endMinute.minuteOfDay)

        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        assertTrue(hasNonTransparentPixel(bmp))
    }

    /**
     * 完整闭环：手势进入编辑态 → `applyEdit` 灌值 → `confirmEdit` 提交。
     *
     * 三条链路同时被验证：
     * 1. `TimelineListener.onEventCreated` 的签名没被裁剪（否则收不到回调）；
     * 2. `EditResult.range` / `.content` 字段没被混淆（否则抛 NoSuchFieldError）；
     * 3. `EditDraft` 的字段同样可读（第四层预填表单依赖它）。
     */
    @Test
    fun fullEditCycleWorksAfterMinification() {
        view.editController = ConsumerApiSmoke.controller(onEnter = { draftOnEnter = it })
        view.submitEvents(listOf(ConsumerApiSmoke.event("e1", "既有标题")))

        // 点空白进入新建编辑态
        tapEmptySpace()
        assertTrue("点空白应进入编辑态", view.isEditing())

        val draft = draftOnEnter
        assertNotNull("EditController.onEnterEditing 应收到草稿", draft)
        assertNotNull("草稿字段 .range 在混淆后可读", draft!!.range)
        assertTrue("新建态 isCreating 应为 true", draft.isCreating)

        // 灌值 + 提交：写的是字面量标题，混淆后必须还能正确回传
        view.applyEdit(
            com.github.kevinvane.daytimeline.library.api.EditResult(
                range = 600..660,
                content = "混淆后写入的标题",
            ),
        )
        view.confirmEdit()

        assertFalse("提交后应退出编辑态", view.isEditing())
        assertEquals("回传的起止时间应与提交值一致", 600..660, recorder.createdRange)
        assertEquals(
            "EditResult.content 字段在混淆后可读且值正确",
            "混淆后写入的标题", recorder.createdContent?.toString(),
        )
    }

    /**
     * 取消路径在混淆后仍不得触发任何数据变更（PRD Q1 / D3）。
     *
     * 断言用**「回调集合精确等于白名单」**而不是逐个字段判空：
     * 逐个判空只能证明「已知的那几个没被调用」，而新增一个数据变更回调时
     * 它压根不会被检查到。集合相等则会在它第一次触发时立刻失败。
     *
     * 这条是 library 侧 `DayTimelineViewEditTest.cancelEmitsNoDataChangeAtAll`
     * 的混淆产物版本——**混淆会改变回调的调用链**（经 R8 重写、可能内联、
     * 经 DefaultImpls 桥接），debug 下的结论不能直接搬过来。
     */
    @Test
    fun cancelStillEmitsNoDataChangeAfterMinification() {
        tapEmptySpace()
        assertTrue(view.isEditing())

        view.applyEdit(
            com.github.kevinvane.daytimeline.library.api.EditResult(range = 600..660, content = "不该生效"),
        )
        recorder.reset() // 滤掉进入编辑态时的回调
        view.cancelEdit()

        assertFalse(view.isEditing())
        assertEquals(
            "取消路径只允许触发 onEditCancelled，实际回调：${recorder.fired}",
            listOf("cancelled"), recorder.fired,
        )
        assertNull("取消路径不得发出任何新建通知", recorder.createdRange)
        assertNull("取消路径不得发出任何修改通知", recorder.modifiedId)
        assertNull("取消路径不得发出任何删除通知", recorder.deletedId)
    }

    /**
     * 对照组：**确认**路径在混淆后确实会发数据变更。
     *
     * 没有这一条，上一条的「取消后集合等于 {cancelled}」可能是假通过——
     * 万一混淆产物上回调根本没被触发过，两条断言都会通过。
     */
    @Test
    fun confirmStillEmitsDataChangeAfterMinification() {
        tapEmptySpace()
        view.applyEdit(
            com.github.kevinvane.daytimeline.library.api.EditResult(range = 600..660, content = "该生效"),
        )
        recorder.reset()
        view.confirmEdit()

        assertEquals(
            "确认路径应发出新建通知，证明回调链在混淆后仍通",
            listOf("created"), recorder.fired,
        )
        assertEquals("回传范围应与提交值一致", 600..660, recorder.createdRange)
    }

    // ---- 工具 ----

    /**
     * 详情弹窗契约（AD-23）在混淆后仍可用。
     *
     * 验证三件事：快照字段可读、`enterEditMode` 让三个出口真正生效
     * （否则业务方点「删除」会静默无效）、`clearSelection` 不影响编辑态。
     */
    @Test
    fun detailApiSurvivesMinification() {
        view.submitEvents(listOf(ConsumerApiSmoke.event("e1", "待查看的日程")))

        var seenTitle = ""
        var seenLast = -1
        val got = ConsumerApiSmoke.detailApi(view) { title, last ->
            seenTitle = title
            seenLast = last
        }
        assertTrue("detailOf 应能取到该日程", got)
        assertEquals("EventDetail.content 字段混淆后可读", "待查看的日程", seenTitle)
        assertTrue("EventDetail.range 字段混淆后可读", seenLast > 0)

        assertFalse(view.isEditing())
        // 进入编辑态后三个出口才可用——这是业务方点「删除」的前提
        assertTrue(view.enterEditMode("e1"))
        assertTrue(view.isEditing())
        view.clearSelection()
        assertTrue("clearSelection 不应退出编辑态", view.isEditing())
        view.cancelEdit()
        assertFalse(view.isEditing())
    }

    /**
     * 点一个**确定没有日程**的位置。
     *
     * `y = HEIGHT / 8` 在钉住滚动位置后对应凌晨 1 点前后（具体分钟随屏幕密度略有差异），
     * 而本模块只放 09:00 一条日程（540~600 分钟），二者相距甚远。
     * **这个前提由 [setUp] 里的 `setScrollOffset(0)` 保证**——
     * 没有它，FI-012 会按真实时钟滚动，写死的坐标随时可能点中日程块。
     *
     * 刻意**不反射读内部 Dimens**：那是 internal 实现，混淆后本就该被改名，
     * 业务方不该也不需要碰它——从消费端反射内部字段验证不了任何对外契约。
     * `setScrollOffset` / `getScrollOffset` 是对外 API，用它们不违反这条。
     */
    private fun tapEmptySpace() {
        val x = WIDTH / 2
        val y = HEIGHT / 8
        val down = System.currentTimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val e = MotionEvent.obtain(down, down, action, x.toFloat(), y.toFloat(), 0)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
            e.recycle()
        }
    }

    private fun hasNonTransparentPixel(bitmap: Bitmap): Boolean {
        for (x in 0 until bitmap.width step 7) {
            for (y in 0 until bitmap.height step 7) {
                if (bitmap.getPixel(x, y) != 0) return true
            }
        }
        return false
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}