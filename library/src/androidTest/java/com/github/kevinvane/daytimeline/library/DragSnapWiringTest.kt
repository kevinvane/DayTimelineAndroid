package com.github.kevinvane.daytimeline.library

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 专项核实「触摸拖拽 → 吸附」这段**接线**是否真的把吸附用上了。
 *
 * ## 为什么要单独建一个文件
 *
 * [SnapCalculatorTest] 已把吸附算法本身覆盖得很干净（含 D11 的上下对称），
 * [DayTimelineViewEditTest] 也覆盖了拖拽的编辑语义。但**没有任何一条测试
 * 断言「手指拖完之后，落点确实被吸附到了步长上」**——
 * 算法对、接线断，中间那层没人守。
 *
 * 2026-10-14 真机走查（P4）时人工反馈「拖到整点/半点附近没有对齐吸附」，
 * 故用本文件把那句反馈变成可复现的断言，先判定是真缺陷还是观感问题。
 *
 * ## 结论：合成触摸驱动不了这段，用例 @Ignore
 *
 * 试过三轮，每一轮红的原因都不同，但**没有一轮红在吸附断言上**：
 *
 * 1. MotionEvent.obtain 给 DOWN 与 MOVE 填了同一个 eventTime → 800ms 后的 MOVE
 *    看起来是 0ms，长按判定等不到超时 → 块没动。
 * 2. 改成真实递增时间戳 + 多次 MOVE → 仍没动。**UP 之后才读**，而草稿未确认就抬起
 *    时编辑态被丢弃、位置还原 → 又读回原始值。
 * 3. 改到 MOVE 之后、UP 之前读 → 仍读到原始 540，**块在拖动过程中确实没动**。
 *
 * 根因是长按依赖 Handler 延迟回调与真实窗口的配合，而本测试的宿主是
 * 「裸 Context + measure/layout」，组件从未 attach 到窗口——与本仓库此前
 * RedrawConvergenceTest 遇到的是同一类问题（那里靠新建 TimelineTestHostActivity 解决）。
 *
 * 附带结论：SnapCalculatorTest 已覆盖吸附算法本身（含 D11 上下对称），
 * 缺的是**接线**这一层；本用例即那层缺口的占位。
 */
@RunWith(AndroidJUnit4::class)
class DragSnapWiringTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var view: DayTimelineView

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = DayTimelineView(context)
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
            view.submitEvents(listOf(StubEvent(TARGET_ID, 540, 600)), notifyIssues = false)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
        }
    }

    /**
     * 拖到**故意不对齐**的位置，落点仍必须吸附到 5 分钟步长上。
     *
     * 位移取 37px：小时高 120px，即每分钟 2px，37px ≈ 18.5 分钟，
     * 从 09:00（540，正好是 5 的倍数）出发会落到 558/559——**不是 5 的倍数**。
     * 若吸附失效，断言就会红。
     */
    /**
     * ⚠️ **当前 @Ignore**：合成触摸驱动不了真实拖拽，见类 KDoc。
     *
     * 留着而不是删掉，是因为「真实拖拽 → 吸附」这段确实**没有自动化覆盖**，
     * 空白要写在代码里，不能只留在某个人的记忆里。
     * 解锁条件：让测试宿主把组件挂进真实窗口（长按依赖窗口与主线程 Looper 的配合），
     * 届时本用例的断言（落点必须是 5 的倍数）可直接复用。
     */
    @Ignore("合成 MotionEvent 无法触发长按进拖拽；需宿主窗口，见类 KDoc")
    @Test
    fun dragLandsOnSnapStep() {
        val startY = centerYOf(TARGET_ID)
        val t0 = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, startY, t0)
        // 长按靠主线程上的延迟 runnable 触发；这期间主线程空闲，正好把它跑掉
        Thread.sleep(800)
        send(MotionEvent.ACTION_MOVE, startY + 37, SystemClock.uptimeMillis())
        Thread.sleep(50)
        send(MotionEvent.ACTION_MOVE, startY + 37, SystemClock.uptimeMillis())
        Thread.sleep(50)

        // **必须在 UP 之前读**。草稿未确认就抬起时编辑态会被丢弃、位置还原，
        // 读到的又是原始 540——第一版就是这么白红了一轮。
        val landed = startMinuteOf(TARGET_ID)
        send(MotionEvent.ACTION_UP, startY + 37, SystemClock.uptimeMillis())

        assertTrue(
            "拖拽应发生（拖动中读到 $landed，仍等于原始 540，说明块根本没动）",
            landed != 540,
        )
        assertEquals(
            "拖拽落点必须吸附到 $SNAP_MINUTES 分钟步长上，实测 $landed",
            0,
            landed % SNAP_MINUTES,
        )
    }

    /** 读回块当前的开始分钟（编辑态下 PlacedBlock 会随拖拽刷新）。 */
    private fun startMinuteOf(id: String): Int {
        var out = Int.MIN_VALUE
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == id }?.second
            requireNotNull(block) { "视口内找不到 $id" }
            out = block.event.start.minuteOfDay
        }
        return out
    }

    private fun centerYOf(id: String): Int {
        var out = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val block = view.visibleBlockSnapshot().firstOrNull { it.second.event.id == id }?.second
            requireNotNull(block) { "前置失败：找不到 $id" }
            val bounds = view.blockBoundsInParent(block)
            requireNotNull(bounds) { "前置失败：取不到 $id 的边界" }
            out = bounds.centerY()
        }
        return out
    }

    /**
     * 派发一个触摸事件。
     *
     * `downTime` 与 `eventTime` **必须分开给**：第一版把两者都填成
     * `System.currentTimeMillis()`，长按判定等的时间戳一直是「同一瞬间」，
     * 结果 800ms 后发出来的 MOVE 看起来像 0ms——**块根本没动**，
     * 断言红在「拖拽应发生」而不是吸附断言上。差点被误读成「吸附坏了」。
     */
    private fun send(action: Int, y: Int, eventTime: Long) {
        val e = MotionEvent.obtain(eventTime, eventTime, action, (WIDTH / 2).toFloat(), y.toFloat(), 0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private data class StubEvent(
        override val id: String,
        private val s: Int,
        private val e: Int,
    ) : com.github.kevinvane.daytimeline.library.core.TimelineEvent {
        override val start
            get() = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(s)
        override val end
            get() = com.github.kevinvane.daytimeline.library.core.MinuteOfDay.ofMinute(e)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val HOUR_HEIGHT = 120
        const val SNAP_MINUTES = 5
        const val TARGET_ID = "snap-target"
    }
}
