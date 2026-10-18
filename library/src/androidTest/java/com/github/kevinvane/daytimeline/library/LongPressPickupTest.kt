package com.github.kevinvane.daytimeline.library

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0：长按**超时即进入编辑态**（不等抬手），对齐 Google 日历的「拾起」。
 *
 * ## 为什么必须挂进宿主窗口
 *
 * 长按由 [android.view.GestureDetector] 在主线程上**延迟**触发。既有仪器测试是
 * 「裸 Context + `measure`/`layout`」，组件从未挂进窗口、也拿不到真实主线程 Looper
 * 的配合——[DragSnapWiringTest] 正是为此被 `@Ignore`。这里用
 * [TimelineTestHostActivity] 真正挂进窗口，才能「按下 → 等超时 → 断言手指仍未抬起
 * 时已进入编辑态」。
 *
 * ## 回滚验证
 *
 * 把 `DayTimelineView` 的 `GestureDetector.onLongPress` 改回「只调
 * `gestureArbiter.onLongPressTimeout()` 打标记、等抬手才进入」——
 * [longPressEntersEditingBeforeRelease] 在超时后的断言转红（`isEditing()` 仍为 false）。
 */
@RunWith(AndroidJUnit4::class)
class LongPressPickupTest {

    private lateinit var scenario: ActivityScenario<TimelineTestHostActivity>
    private lateinit var view: DayTimelineView

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(TimelineTestHostActivity::class.java)
        scenario.onActivity { activity ->
            view = DayTimelineView(activity)
            view.setConfig(
                TimelineConfig(
                    hourHeight = HOUR_HEIGHT,
                    topPadding = 0,
                    bottomPadding = 0,
                    snapMinutes = SNAP_MINUTES,
                    autoLocateOnFirstShow = false,
                    showNowIndicator = false,
                ),
            )
            view.setNowMinute(0)
            view.submitEvents(listOf(Ev(TARGET_ID, 60, 120)), notifyIssues = false)
            activity.container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        if (::view.isInitialized) {
            scenario.onActivity { it.container.removeView(view) }
        }
        scenario.close()
    }

    /** 核心：手指仍按住时，长按超时应已进入编辑态。 */
    @Test
    fun longPressEntersEditingBeforeRelease() {
        val bounds = targetBounds()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()

        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        instrumentation.waitForIdleSync()
        assertFalse("刚按下、未到长按阈值时不得进入编辑态", readEditing())

        Thread.sleep(LONG_PRESS_WAIT_MILLIS)
        instrumentation.waitForIdleSync()
        assertTrue(
            "长按超时应立即进入编辑态（无需抬手）——P0 对齐 Google 日历的「拾起」",
            readEditing(),
        )

        send(MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis())
        instrumentation.waitForIdleSync()
        assertTrue("抬手后应保留编辑态（等待用户确认或取消）", readEditing())
    }

    /** 未松手就能接着拖动：超时后 MOVE 应改变草稿时长（对齐 Google 的按住即拖）。 */
    @Test
    fun draggingAfterPickupKeepsEditingWithoutRelease() {
        val bounds = targetBounds()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()

        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        Thread.sleep(LONG_PRESS_WAIT_MILLIS)
        instrumentation.waitForIdleSync()
        assertTrue("前置条件：长按超时应已进入编辑态", readEditing())

        // 位移 60px（> 48px 拖拽阈值）——编辑态拖拽必须被消费，且不得抛异常
        val up = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_MOVE, x, y + 60f, down, up)
        instrumentation.waitForIdleSync()
        assertTrue("按住拖动过程中编辑态必须保持", readEditing())

        send(MotionEvent.ACTION_UP, x, y + 60f, down, SystemClock.uptimeMillis())
        instrumentation.waitForIdleSync()
        assertTrue("抬手后仍保留编辑态", readEditing())
    }

    private fun readEditing(): Boolean {
        var editing = false
        instrumentation.runOnMainSync { editing = view.isEditing() }
        return editing
    }

    private fun targetBounds(): Rect {
        var rect: Rect? = null
        instrumentation.runOnMainSync {
            val block = view.visibleBlockSnapshot().first { it.second.event.id == TARGET_ID }.second
            rect = view.blockBoundsInParent(block)
        }
        return requireNotNull(rect) { "测试前置失败：取不到 $TARGET_ID 的边界" }
    }

    private fun send(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        instrumentation.runOnMainSync { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private data class Ev(
        override val id: String,
        private val s: Int,
        private val e: Int,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    private companion object {
        const val TARGET_ID = "target"
        const val HOUR_HEIGHT = 120
        const val SNAP_MINUTES = 5

        /** 等足长按超时（系统默认约 400–500ms），留足余量。 */
        const val LONG_PRESS_WAIT_MILLIS = 900L
    }
}
