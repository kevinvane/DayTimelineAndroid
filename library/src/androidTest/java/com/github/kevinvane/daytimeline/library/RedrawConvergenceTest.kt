package com.github.kevinvane.daytimeline.library

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kevinvane.daytimeline.library.api.TimelineConfig
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 重绘收敛（AD-06 / D17 / PRD §12.1 耗电项）与「网格区常驻界面元素数 = 0」
 * （PRD §12.1 / D16）的**运行时**验证。
 *
 * ## 为什么要宿主窗口
 *
 * 既有仪器测试全部是「裸 Context + `measure`/`layout`」，组件从未 attach 到窗口。
 * 那种 View 上的 `invalidate()` 不会调度任何绘制——**收敛与否根本观测不到**。
 * 因此这里通过 [TimelineTestHostActivity] 把组件真正挂进窗口，
 * 用 [ViewTreeObserver.OnDrawListener] 数真实的绘制次数。
 *
 * ## 「一次绘制」的准确含义（口径，不是guarantee）
 *
 * `OnDrawListener` 回调的是**整个窗口的一帧绘制**，不是「某个 View 被重画」。
 * 本测试之所以能据此判断收敛：宿主 Activity 里除组件外没有任何会 invalidate 的东西，
 * 窗口一旦产生绘制，必然是组件请求的。反过来说，断言的准确表述是
 * 「**静止期间组件没有请求任何一帧**」，而不是「组件的 `onDraw` 没被调用」。
 *
 * ## 为什么会抖：时钟
 *
 * 组件有一个 30 秒一次的当前时间线刷新（[DayTimelineView] 的 `nowTicker`）。
 * 它会在跨过整分钟时改变 `nowMinute` → 渲染签名变化 → 合法地触发一次重绘。
 * 那会让本测试变成「取决于跑在第几秒」的间歇性红灯。
 * 解决办法是在准备阶段先 `setNowMinute(固定值)`：该调用会让定时器**不再覆盖**当前时间，
 * 于是签名在整个用例期间恒定，断言变成确定性的。
 */
@RunWith(AndroidJUnit4::class)
class RedrawConvergenceTest {

    private lateinit var scenario: ActivityScenario<TimelineTestHostActivity>
    private lateinit var view: DayTimelineView

    private val drawCount = AtomicInteger(0)
    private val drawListener = ViewTreeObserver.OnDrawListener { drawCount.incrementAndGet() }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(TimelineTestHostActivity::class.java)
        // onActivity 本身就在主线程执行，不必再套一层 runOnMainSync。
        // 刻意全程走 onActivity 而不是取一次 Activity 引用：这样每个断言拿到的
        // 都是「此刻真实的窗口状态」，不会和 Activity 重建（旋转、配置变更）脱节。
        scenario.onActivity { activity ->
            view = DayTimelineView(activity)
            // 关掉首次定位：它会把滚动位置定到「当前时间」附近，
            // 让本文件里「重复设置同一滚动位置」的前置条件依赖运行时刻。
            view.setConfig(TimelineConfig(autoLocateOnFirstShow = false))
            // 固定时钟，让 30 秒定时器不再改动 nowMinute（见类 KDoc）
            view.setNowMinute(FIXED_NOW)
            view.submitEvents(dayEvents(DAY_SIZE))
            view.viewTreeObserver.addOnDrawListener(drawListener)
            activity.container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        settle()
    }

    @After
    fun tearDown() {
        if (::view.isInitialized) {
            scenario.onActivity { activity ->
                view.viewTreeObserver.removeOnDrawListener(drawListener)
                activity.container.removeView(view)
            }
        }
        scenario.close()
    }

    /**
     * AD-06 / D17：无副作用的重复刷新不得触发任何一次重绘。
     *
     * 覆盖三种业务方真实会写出来的「无副作用」调用：
     *
     | 调用 | 为什么会走到 `requestRefresh` 却不该重绘 |
     |---|---|
     | `setScrollOffset(getScrollOffset())` | 位置没变，只是重新算了一次夹取 |
     | `setConfig(getConfig())` | 合并后配置不变，重排了分栏但几何没变 |
     | `submitEvents(同一份数据)` | 在 [DayTimelineView.submitEvents] 里就提前返回了（D10） |
     *
     * ## 回滚验证（均已实测）
     *
     * - 把 [DayTimelineView] 的 `requestRefresh` 改成**无条件** `invalidate()`（删掉签名比对）
     *   → 本用例转红：刷新前 1 帧、刷新后 2 帧。注意只 +1 帧而不是 +50 帧——
     *   Android 会把同一个主线程任务里的多次 `invalidate` 合并成一帧。
     *   所以本用例断言的是「**有没有出现意外的一帧**」，不是帧数多少。
     * - 删掉 `submitEvents` 里那句 `if (newEvents == events) return` → 本用例**仍然全绿**。
     *   这是对的、也是一开始写错的地方：挡住重绘的是签名比对，不是那句提前返回；
     *   提前返回只省掉重排分栏的开销。结论是 `submitEvents` 这一条路径同样归签名比对管。
     */
    @Test
    fun repeatingSideEffectFreeRefreshesDoNotTriggerAnyRedraw() {
        val before = drawCount.get()
        instrumentation.runOnMainSync {
            repeat(NO_OP_ROUNDS) {
                view.setScrollOffset(view.getScrollOffset())
                view.setConfig(view.getConfig())
                view.submitEvents(dayEvents(DAY_SIZE))
            }
        }
        settle()
        assertEquals(
            "无副作用的重复刷新（setScrollOffset / setConfig / submitEvents 同数据）共 $NO_OP_ROUNDS 轮，" +
                "不得触发任何一帧绘制（AD-06 / D17）：刷新前 $before 帧，刷新后 ${drawCount.get()} 帧",
            before,
            drawCount.get(),
        )
    }

    /**
     * D17：静止时不得存在不收敛的重绘循环。
     *
     * 这是 §12.1「界面停留 1 小时后耗电无明显上升」能被自动化的那一小部分：
     * 真正的不收敛循环（`invalidate` 自我触发）在 2 秒内就会画出上百帧，
     * 而正确实现在这 2 秒里应该一帧都不画。
     *
     * 时长取 2 秒：远小于 30 秒定时周期（且 [setUp] 已把时钟固定），
     * 短到不会拖慢 CI，长到足以让任何「每帧重绘」的缺陷显形。
     *
     * ## 回滚验证（已实测）
     *
     * 把 `nowTicker` 改成「每次触发都 `invalidate()`，且周期缩到 200ms」后，
     * 本用例转红：2 秒内 1 帧 → **11 帧**。
     *
     * 第一次做这个实验时**没有转红**，原因是只改了重发间隔、没改
     * `onAttachedToWindow` 里的首次 `postDelayed` 延迟（那还是 30 秒），
     * 于是整个 2 秒窗口里定时器压根没触发。这条记在这里：
     * 「改周期」类实验要同时改**首次投递**与**重发**，否则实验无效且看起来像「测试不灵敏」。
     */
    @Test
    fun stayingIdleDoesNotRedraw() {
        val before = drawCount.get()
        Thread.sleep(IDLE_OBSERVE_MILLIS)
        instrumentation.waitForIdleSync()
        val after = drawCount.get()
        assertEquals(
            "静止 ${IDLE_OBSERVE_MILLIS}ms 内不得发生任何绘制（D17：不收敛的重绘循环）：" +
                "静置前 $before 帧，静置后 $after 帧",
            before,
            after,
        )
    }

    /**
     * PRD §12.1「网格区常驻界面元素数 = 0」/ D16 的运行时一侧。
     *
     * 编译期那一侧由 `:library:verifyNoChildViews` 源码扫描门禁负责（无需设备、进 CI 首个 job）；
     * 这里守的是另一件事：**挂进真实窗口后，视图树里确实没有多出任何节点**。
     * 两者缺一不可——只扫源码会漏掉「通过反射 / 通过第三方库」塞进来的节点。
     *
     * 口径：只看宿主容器子树。对话框（删除二次确认、表单面板）是**独立 window**，
     * 不在这棵树里，也正因为如此才允许存在。
     */
    @Test
    fun gridAreaAddsNoChildViewsToTheHierarchy() {
        var descendants: List<View> = emptyList()
        var childCount = -1
        var onlyChild: View? = null
        scenario.onActivity { activity ->
            val container = activity.container
            childCount = container.childCount
            onlyChild = if (childCount > 0) container.getChildAt(0) else null
            // 起点是**组件本身**而非容器：要数的是「组件之下」有没有多出节点。
            // 从容器出发会把组件自己算进去，于是永远至少有 1 个。
            descendants = collectDescendants(view)
        }

        assertEquals("宿主容器里应当只有组件这一个直接子节点", 1, childCount)
        assertSame("容器里唯一的子节点应当就是组件本身", view, onlyChild)
        assertEquals(
            "PRD §12.1 要求网格区常驻界面元素数为 0（D16）：组件之下不应存在任何子 View，" +
                "实际发现 ${descendants.size} 个 —— $descendants",
            emptyList<View>(),
            descendants,
        )
    }

    /** 递归收集 [root] 之下的全部后代（不含 [root] 自身），只对 ViewGroup 向下递归。 */
    private fun collectDescendants(root: View): List<View> {
        val out = ArrayList<View>()
        if (root !is ViewGroup) return out
        val queue = ArrayDeque<View>()
        for (i in 0 until root.childCount) queue.addLast(root.getChildAt(i))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            out += node
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) queue.addLast(node.getChildAt(i))
            }
        }
        return out
    }

    /**
     * 等待「所有已排队的绘制都画完」。
     *
     * 单靠 `waitForIdleSync()` 不够：它只等主线程队列空，而绘制发生在渲染线程回调
     * `doFrame` 之后，队列空时那一帧可能还没提交。这里先等队列空、再睡一小段、
     * 再等一次队列空，模拟「让出一帧时间」。
     */
    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MILLIS)
        instrumentation.waitForIdleSync()
    }

    private data class Ev(
        override val id: String,
        private val s: Int,
        private val e: Int,
        override val content: CharSequence? = null,
    ) : TimelineEvent {
        override val start get() = MinuteOfDay.ofMinute(s)
        override val end get() = MinuteOfDay.ofMinute(e)
    }

    /** 一天 200 条（PRD §12.1 的承诺基准量级），时刻确定，不含随机数。 */
    private fun dayEvents(count: Int): List<TimelineEvent> {
        val stride = MinuteOfDay.MINUTES_PER_DAY / count
        return (0 until count).map { i ->
            val start = i * stride
            Ev("e_$i", start, minOf(start + (stride * 3) / 2, MinuteOfDay.MINUTES_PER_DAY))
        }
    }

    private companion object {
        /** 一天 200 条（PRD §12.1 的承诺基准量级），时刻确定，不含随机数。 */
        const val DAY_SIZE = 200

        /** 固定在 12:00，让三态推导走遍已过 / 进行中 / 未到三条分支。 */
        const val FIXED_NOW = 720

        /** 无副作用刷新的重复轮数：够多，才能说明「不是碰巧」。 */
        const val NO_OP_ROUNDS = 50

        /** 静止观测时长。远小于 30 秒定时周期，短到不拖慢 CI。 */
        const val IDLE_OBSERVE_MILLIS = 2_000L

        /** 每轮「排空绘制」的等待时间，一帧足够，留 4 倍余量。 */
        const val SETTLE_MILLIS = 64L
    }
}
