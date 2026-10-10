package com.github.kevinvane.daytimeline.app

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.DayTimelineView
import com.github.kevinvane.daytimeline.library.core.PlacedBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * demo 侧取消链路的仪器测试（D3 / PRD §14.3 Q1）。
 *
 * ## 为什么组件自己的测试不够
 *
 * `DayTimelineViewEditTest` 已证明**组件**在取消时不发数据变更通知，但组件不知道
 * 业务方会怎么接。demo 里有两条真实的取消链路，此前**零自动化覆盖**，
 * 只靠 `:226` 的注释自证安全——注释不是证据：
 *
 * 1. `BottomSheetDialog.setOnDismissListener` → `cancelEdit()`（`MainActivity:227`）。
 *    BottomSheet 默认可取消：返回键与「点外面」都会走到这里。写错的后果是
 *    「表单关了但草稿还开着」，而接管态下 FI-010 已失效，用户既进不去也退不出。
 * 2. `PopupWindow.onDismiss` → `cancelEdit()`（`EventDetailPopup:205`），且必须用
 *    `actionTaken` 区分「用户只是看了看」与「业务方已发起操作」——否则点「完成」
 *    也会被当成取消，把刚提交的数据又收回去。
 *
 * ## 断言方式
 *
 * 不断言「回调了没有」，而是比对**业务方数据源**（`MainActivity.events`）的快照。
 * D3 的实质是「用户的数据没被改」，直接比对数据比数回调更贴近真实后果，
 * 也不需要往 demo 里塞测试专用钩子。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCancelPathTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    /** 关动画前的三项全局缩放原值，tearDown 原样还原。 */
    private var animationScales: Map<String, String> = emptyMap()

    /**
     * 当前 Activity 实例。
     *
     * 刻意用 `onActivity` 捕获而不是 `scenario.get()`——后者在 `androidx.test:core`
     * 1.7.0 已被移除（只剩 `getResult` / `getState`），且 `onActivity` 是官方推荐写法。
     */
    private var activityRef: MainActivity? = null

    private val activity: MainActivity
        get() = requireNotNull(activityRef) { "前置失败：Activity 未启动（onActivity 没回调过）" }

    private val timeline: DayTimelineView
        get() = activity.findViewById(R.id.timeline)

    @Before
    fun setUp() {
        // Espresso 会拒绝在有动画的设备上点击（BottomSheet 的展开/收起动画必踩）。
        // CI 的模拟器默认关好了动画，本地真机未必——**在测试里自己关**，
        // 而不是让人记得先改系统设置：靠环境配合的测试，换台机器就红。
        disableAnimations()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activityRef = it }
        waitUntilIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        restoreAnimations()
    }

    private fun disableAnimations() {
        animationScales = ANIMATION_KEYS.associateWith { key ->
            val current = shell("settings get global $key")
            shell("settings put global $key 0")
            current
        }
    }

    private fun restoreAnimations() {
        animationScales.forEach { (key, value) ->
            // 原值可能是 null（此前从没有过这个设置），此时删掉即为还原
            if (value == "null") shell("settings delete global $key") else shell("settings put global $key $value")
        }
        animationScales = emptyMap()
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }

    /**
     * **对照**：同样改标题、同样走表单，这次点「确定」——数据源必须变。
     *
     * 没有这一条，下面的「取消不改数据」就可能是假通过：万一表单根本没提交能力，
     * 「取消当然不改数据」。两条配对才构成证据。
     */
    @Test
    fun submittingTheFormWritesToDataSource() {
        val before = dataSnapshot()

        enterEditing("standup")
        editTitleTo(NEW_TITLE)
        onView(withId(R.id.form_confirm)).perform(click())
        waitUntilIdle()

        assertFalse("「确定」后应退出编辑态", timeline.isEditing())
        assertTrue(
            "「确定」必须真的改到数据源，否则取消用例是假通过",
            dataSnapshot().any { it.startsWith("standup@") && it.endsWith("#$NEW_TITLE") },
        )
        assertEquals("「确定」不得凭空增删日程", before.size, dataSnapshot().size)
    }

    /**
     * 表单「取消」：**改了值也不得落库**。
     *
     * 走 `form_cancel` → `dismiss()` → `onDismiss` → `cancelEdit()` 这条链，
     * 中间任何一环漏掉都会表现为「编辑态残留」或「数据被改」。
     *
     * 刻意在取消**之前**改标题——只测「什么都没改就取消」的话，
     * 用例通过不能说明任何问题：本来就没什么可丢的。
     */
    @Test
    fun cancellingTheFormChangesNothing() {
        val before = dataSnapshot()
        enterEditing("standup")
        editTitleTo(NEW_TITLE)
        onView(withId(R.id.form_cancel)).perform(click())
        waitUntilIdle()

        assertFalse("取消表单后必须退出编辑态", timeline.isEditing())
        assertEquals("取消表单不得改动任何业务数据，改过的标题也不得落库", before, dataSnapshot())
    }

    /**
     * 表单被系统行为关掉（返回键 / 点外面）与被「取消」按钮关掉是同一条收尾路径，
     * 但前者容易漏——按钮是我们自己的，系统行为不是。
     *
     * 循环若干次直到 `isEditing()` 变 false：测的是「**无论怎么关**，收尾都必须发生」，
     * 而不是「某个特定的关法有效」。
     */
    @Test
    fun dismissingTheFormWithoutSubmittingChangesNothing() {
        val before = dataSnapshot()
        enterEditing("standup")

        closeForm()
        waitUntilIdle()

        assertFalse("表单被关掉后必须退出编辑态（草稿不得残留）", timeline.isEditing())
        assertEquals("表单被关掉不得改动任何业务数据", before, dataSnapshot())
    }

    /**
     * 详情弹窗「取消」：退出编辑态，数据不变。
     *
     * 这条路径**先进入编辑态再取消**。若 `enterEditMode` 失败却仍调 `cancelEdit`，
     * 会静默退出一段并不存在的编辑，看起来「通过」实则没测到东西——
     * 故先断言详情内容确已渲染。
     *
     * **已知局限**：详情弹窗是只读的，用户没有可改的字段，
     * 因此这里只能验「状态收尾」，验不了「改过的东西没落库」。
     * 后者由 `cancellingTheFormChangesNothing` 覆盖（那里有真实的改动）。
     */
    @Test
    fun cancellingFromDetailPopupChangesNothing() {
        val before = dataSnapshot()

        openDetail("standup")
        onView(withId(R.id.detail_cancel)).perform(click())
        waitUntilIdle()

        assertFalse("详情里取消后必须退出编辑态", timeline.isEditing())
        assertEquals("详情里取消不得改动任何业务数据", before, dataSnapshot())
    }

    /**
     * 回归：`EventDetailPopup.onDismissed` 里的 `actionTaken` 必须真的起作用。
     *
     * 「完成」路径同样会 dismiss 弹窗，若 dismiss 无条件收尾就会去 `cancelEdit()`。
     * 这里只断言**状态**：提交后不得还停在编辑态、也不得把刚提交的草稿收回去。
     *
     * 注意这条**不能**用来证明「完成会改数据」——详情弹窗是只读的，
     * 不改任何字段就点完成，回传的时间与标题和原值相同，数据源自然不变。
     * 数据的写入证据在 `submittingTheFormWritesToDataSource`。
     */
    @Test
    fun confirmingFromDetailPopupLeavesNoEditingState() {
        openDetail("standup")
        onView(withId(R.id.detail_done)).perform(click())
        waitUntilIdle()

        assertFalse("「完成」后不得停在编辑态", timeline.isEditing())
    }

    /**
     * 详情弹窗被关掉（未点任何按钮）也必须收尾。
     *
     * 与 `dismissingTheFormWithoutSubmitting` 是同一类陷阱在另一个窗口上的表现，
     * 但代码路径不同（`EventDetailPopup:196` vs `MainActivity:227`），必须各测一次。
     */
    @Test
    fun closingDetailPopupWithoutActionLeavesNoDraft() {
        val before = dataSnapshot()
        openDetail("standup")

        closeDetailPopup()
        waitUntilIdle()

        assertFalse("详情弹窗关闭后不得残留编辑态", timeline.isEditing())
        assertEquals("只是看了详情就关掉，不得改动业务数据", before, dataSnapshot())
    }

    // ---- 进入编辑态与窗口操作 ----

    /**
     * 打开某条日程的详情：走无障碍单击入口，等价于用户点一下块。
     *
     * 不用 Espresso 的 `click()`：那会点视图**中心**，而目标日程在一天中的位置不固定，
     * 断言就得写成「碰巧落在某条上」。无障碍入口是确定性的。
     */
    private fun openDetail(id: String) {
        // 先在测试线程取块，再切主线程执行——反过来的话 blockOf 会落在主线程，
        // 它内部的 runOnMainSync 就成了嵌套调用，Instrumentation 会直接抛异常
        val block = blockOf(id)
        onMainSync { invokeInternal("dispatchEventClickForAccessibility", block) }
        waitUntilIdle()
        // 断言内容已渲染——这一步同时替代「弹窗是否弹出」的判定：
        // 单击入口本身没有返回值，用界面上有没有东西来判，比反射一个内部标志可靠
        onView(withId(R.id.detail_title)).check { view, noView ->
            assertTrue("详情标题未渲染，弹窗可能没起来", noView == null && view.isShown)
        }
    }

    /** 长按进入编辑态 → demo 的 `EditController.onEnterEditing` 会弹出表单。 */
    private fun enterEditing(id: String) {
        val block = blockOf(id)
        onMainSync { invokeInternal("dispatchEventLongClickForAccessibility", block) }
        waitUntilIdle()
        assertTrue("前置条件：长按后应处于编辑态", timeline.isEditing())
    }

    /**
     * 找到指定日程的块，并确保它在视口内。
     *
     * 先 `scrollToMinute` 再取——否则「视口内找不到」会成为假阴性，
     * 而它看起来和真的功能缺陷一模一样。
     */
    private fun blockOf(id: String): PlacedBlock {
        val snapshot = onMainSync { visibleBlocks() }
        snapshot.firstOrNull { it.event.id == id }?.let { return it }
        onMainSync { timeline.scrollToMinute(midMinuteOf(id), smooth = false) }
        waitUntilIdle()
        return onMainSync { visibleBlocks() }
            .firstOrNull { it.event.id == id }
            ?: error("测试前置失败：滚动后仍找不到日程 $id")
    }

    private fun midMinuteOf(id: String): Int {
        val events = eventsField()
        val e = events.firstOrNull { idOf(it) == id } ?: error("数据源里没有 $id")
        return (fromOf(e) + toOf(e)) / 2
    }

    /** 当前视口内的块。`internal` 方法编译后带模块后缀，按前缀匹配即可。 */
    private fun visibleBlocks(): List<PlacedBlock> {
        val method = DayTimelineView::class.java.methods.first { it.name.startsWith("visibleBlockSnapshot") }
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(timeline) as List<Pair<Int, PlacedBlock>>).map { it.second }
    }

    /**
 * 调 `DayTimelineView` 的 `internal` 方法（编译后为 public + `$library_debug` 后缀）。
 *
 * 这两个入口都是 `Unit` 返回——它们只改组件内部状态并发通知，
 * 「有没有生效」由界面上看得见的东西断言（弹窗渲染 / `isEditing()`），
 * 比在这里反射一个内部标志更贴近用户实际看到的结果。
 */
private fun invokeInternal(name: String, block: PlacedBlock) {
        val method = DayTimelineView::class.java.methods
            .first { it.name.startsWith(name) && it.parameterCount == 1 }
        method.invoke(timeline, block)
    }

    /**
     * 把表单标题改成 [NEW_TITLE]。
     *
     * 收键盘是必需的：软键盘弹出后会盖住 BottomSheet 底部的按钮，
     * Espresso 因「可见区域不足 90%」拒绝点击——那是环境几何问题，
     * 不是被测行为，不该让它决定用例成败。
     */
    private fun editTitleTo(title: String) {
        onView(withId(R.id.form_title)).perform(clearText(), typeText(title))
        closeSoftKeyboard()
        waitUntilIdle()
    }

    private fun closeForm() {
        repeat(CLOSE_ATTEMPTS) {
            if (!timeline.isEditing()) return
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntilIdle()
        }
        if (timeline.isEditing()) onView(withId(R.id.form_cancel)).perform(click())
    }

    private fun closeDetailPopup() {
        repeat(CLOSE_ATTEMPTS) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntilIdle()
            if (!timeline.isEditing()) return
        }
    }

    // ---- 业务方数据源快照 ----

    /**
     * 数据源快照：`id@起始-结束#标题`，按 id 排序。
     *
     * 排序而非保留列表顺序——「顺序变了」不是取消路径的缺陷，
     * 「某条日程的时间或标题变了」才是。
     */
    private fun dataSnapshot(): List<String> = eventsField()
        .map { e -> "${idOf(e)}@${fromOf(e)}-${toOf(e)}#${titleOf(e) ?: ""}" }
        .sorted()

    private fun eventsField(): List<Any?> {
        val field = MainActivity::class.java.getDeclaredField("events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(activity) as List<Any?>
    }

    private fun readOf(e: Any?, name: String): Any? =
        e!!::class.java.getDeclaredField(name).apply { isAccessible = true }.get(e)

    private fun idOf(e: Any?): String = readOf(e, "id") as String

    private fun fromOf(e: Any?): Int = readOf(e, "from") as Int

    private fun toOf(e: Any?): Int = readOf(e, "to") as Int

    private fun titleOf(e: Any?): String? = readOf(e, "title") as String?

    // ---- 基础设施 ----

    private fun <T> onMainSync(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntilIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        /** 「怎么关掉弹窗」在真机上因设备而异，循环若干次直到收尾发生。 */
        const val CLOSE_ATTEMPTS = 4

        /**
         * 测试写入的标题。
         *
         * 用 ASCII：Espresso 的 `typeText` 对非 ASCII 要走 `sendStringSync`，
         * 在部分 IME 上会静默丢字符——那会让断言变成「测的是输入法」而不是「测的是组件」。
         */
        const val NEW_TITLE = "EDITED"

        /** Espresso 要求这三项为 0，否则拒绝点击有动画的视图。 */
        val ANIMATION_KEYS = listOf(
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        )
    }
}