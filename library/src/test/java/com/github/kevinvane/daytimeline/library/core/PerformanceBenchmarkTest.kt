package com.github.kevinvane.daytimeline.library.core

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12.1 的两条**可自动化**性能指标：全量布局耗时与列表增量更新耗时。
 *
 * 这两条之所以能自动测，是因为整条计算链路都在纯 `core` 里、零 `android.*` 依赖
 * （AD-02 / T2）——不需要真机、不需要模拟器，`testDebugUnitTest` 就能跑。
 *
 * ## 测的是什么、没测什么（这一段是口径，不是免责）
 *
 * **测到了**：`OverlapLayoutEngine` 的布局、`EventSanitizer` 的兜底修正、
 * `EventDiff` 的滚动锚点、`TimeStateResolver` 的三态推导——也就是 §12.1 里
 * 「全量布局」与「列表增量更新」两个数字对应的**全部计算**。
 *
 * **没测到**：`DayTimelineView` 那侧的赋值、`invalidate()`、以及真实绘制。
 * 绘制在 JVM 上不存在（`Canvas` 是 Android 类），因此：
 *
 * - 「滚动掉帧率 ≤ 3%」「拖拽跟手 ≤ 1 帧」**仍不能自动测**——它们不是「懒得做」，
 *   而是必须真机 + 真滚动才能测的指标，模拟器上的帧时序不代表任何真实设备（见 DL-09）；
 * - 本文件里的绝对耗时数字**不能**被当作「§12.1 全部达标」的证据。
 *
 * ## 回滚验证（AGENTS.md 第二条：写完就绿不算数）
 *
 * 本机基线（2026-10-13）：全量布局 200 条最坏 0.072ms / 真实分布 0.064ms，
 * 增量更新 100 条 0.032ms，翻倍比值 2.06。
 *
 * 做过两次破坏性验证，结论都写在这里：
 *
 * - 把 [OverlapLayoutEngine.assignColumns] 的贪心首次适配换成「两两区间比较」后，
 *   **只有增长形状那条转红**（比值 2.06 → 3.66）；三条绝对耗时用例全部仍然通过
 *   （真实分布 0.064ms → 1.10ms，仍在 4ms 预算内，增量更新 0.032ms → 0.19ms，
 *   仍在 16ms 预算内）。
 * - 把 `ColumnOccupancy.hasOverlap` 的二分探测换成线性扫描后，三条用例的数值**几乎没变**。
 *   该退化在按时间排序的列桶上会提前 `break`，因此它并不构成复杂度回归，
 *   不能拿来当灵敏度证据——这一点也一并记下，免得下次重复这个无效实验。
 *
 * 一句话：**绝对耗时断言的 headroom 有 3.6–58 倍，中等程度的算法退化在它下面完全隐形；
 * 真正咬得住的只有增长形状那条。** 报告与 PRD 里引用这些数字时必须带上这个前提。
 */
class PerformanceBenchmarkTest {

    /**
     * §12.1「全量布局耗时（200 条）≤ 4ms」——按**最坏情况**卡。
     *
     * [PerfData.allOverlapping] 让 200 条完全同时，最大同时重叠数 = 200，
     * [OverlapLayoutEngine] 因此走满 `O(条数 × 最大同时重叠数)` 的上界。
     *
     * ## 回滚验证
     *
     * 把 [OverlapLayoutEngine.assignColumns] 的贪心首次适配换成「两两区间比较」后，
     * 本用例**没有**转红（0.072ms，与退化前基本相同）。
     * 原因值得记下来：全部同时的数据里，任何两列一比较就冲突，
     * 退化实现的内层循环在第一次比较就 `break`，于是「最坏重叠」反而是最快的退化路径。
     * 这条门禁守的是「彻底失控」，算法复杂度回退由下面的形状用例守。
     */
    @Test
    fun `全量布局 200 条（最坏情况：完全同时）不超过 4ms`() {
        val events = PerfData.allOverlapping(PerfData.DAY_SIZE)
        val result = Bench.measure("全量布局 200 条·完全同时", SAMPLES) {
            OverlapLayoutEngine.layout(events, PerfData.AVAILABLE_WIDTH, PerfData.GAP).size
        }
        println("[§12.1 全量布局] ${result.describe()}")
        assertWithinBudget(result.medianNanos, FULL_LAYOUT_BUDGET_NANOS, "全量布局 200 条（最坏情况）")
    }

    /**
     * 同一条指标在**真实分布**下的表现：200 条集中在 08:00–22:00，密度随条数缓慢增长。
     *
     * 与上一条并存的意义：最坏情况说明「上界没失控」，真实分布说明
     * §12.1 承诺的**典型区间**离指标还有多大余量——DL-09 里要引用的正是后一个数字。
     *
     * ## 回滚验证
     *
     * `assignColumns` 退化成两两区间比较后，本用例由 0.064ms 涨到 1.10ms，
     * **仍未超过 4ms 预算**，用例保持绿色。headroom 3.6 倍——这正是它作为
     * 「复杂度守卫」不够格、需要下面那条形状用例的原因。
     */
    @Test
    fun `全量布局 200 条（真实分布）不超过 4ms`() {
        val events = PerfData.realistic(PerfData.DAY_SIZE)
        val result = Bench.measure("全量布局 200 条·真实分布", SAMPLES) {
            OverlapLayoutEngine.layout(events, PerfData.AVAILABLE_WIDTH, PerfData.GAP).size
        }
        println("[§12.1 全量布局] ${result.describe()}")
        assertWithinBudget(result.medianNanos, FULL_LAYOUT_BUDGET_NANOS, "全量布局 200 条（真实分布）")
    }

    /**
     * §12.1「列表增量更新耗时（100 条）≤ 16ms（1 帧）」。
     *
     * 测的是**改一条之后重新提交**的完整计算链路，而不是「只改一条」——
     * 组件当前的增量策略是「整表重算 + 保持滚动锚点」（`DayTimelineView.submitEvents`），
     * 所以这里如实按整表计。若将来真改成局部重算，本用例只会变快，
     * 它守的仍然是「重算一整天的代价不超过 1 帧」这条线。
     *
     * ## 回滚验证
     *
     * 同一处 `assignColumns` 退化后由 0.032ms 涨到 0.19ms，仍未超过 16ms，
     * 用例保持绿色——**这条门禁的 headroom 有 500 倍，基本只在「彻底失控」时才响**。
     * 它的价值是兜底与留痕（把「增量更新到底多贵」变成 CI 里每次都能看到的数字），
     * 不是复杂度守卫。
     */
    @Test
    fun `增量更新一条（100 条基准）不超过 16ms`() {
        val baseline = PerfData.realistic(PerfData.UPDATE_BASELINE)
        var previous = OverlapLayoutEngine.layout(baseline, PerfData.AVAILABLE_WIDTH, PerfData.GAP)
        val source = baseline.map { it.source }.toMutableList()
        val targetIndex = source.size / 2
        val targetId = source[targetIndex].id

        val result = Bench.measure("增量更新 100 条", SAMPLES) {
            // 每轮改一条的内容：内容变化必须触发重算，是最常见也最坏的一种日常更新
            source[targetIndex] = TestEvent(targetId, 600, 660, content = "改过的标题")
            val outcome = submitCore(source, previous)
            previous = outcome.blocks
            outcome.checksum
        }
        println("[§12.1 增量更新] ${result.describe()}")
        assertWithinBudget(result.medianNanos, INCREMENTAL_BUDGET_NANOS, "增量更新 100 条")
    }

    /**
     * 增长形状门禁：`T(400) / T(200)` 不得明显超过 2。
     *
     * **这是本文件里唯一真正咬得住的一条。** 绝对耗时断言有 10–50 倍 headroom，
     * 中等程度的算法退化在它下面完全隐形；而复杂度退化恰好是最容易被引入、
     * 又最难在 review 里看出来的缺陷——§12.1 的「4ms 预算」很容易给人「已经很安全了」的错觉。
     *
     * 用最小值而不是中位数算比值：中位数里混着 GC 与调度抖动，做除法时抖动会被
     * 按比例放大到 30%–50%，阈值就没法卡了。
     *
     * [PerfData.fixedOverlap] 把最大同时重叠数钉死为常数 2，
     * 因此理论上「条数翻倍 → 耗时翻倍」；明显超线性就一定是算法出了问题。
     *
     * ## 回滚验证
     *
     * `assignColumns` 退化成两两区间比较后，本用例转红：比值 2.06 → **3.66**，
     * 超过阈值 3.0；同一次退化下另外三条用例全部保持绿色。
     * 这个对照就是本用例存在的理由——没有它，整个文件对复杂度回归是瞎的。
     */
    @Test
    fun `条数翻倍时布局耗时的增长保持近线性`() {
        val small = PerfData.fixedOverlap(PerfData.DAY_SIZE)
        val large = PerfData.fixedOverlap(PerfData.DOUBLE_SIZE)

        val smallResult = Bench.measure("布局 200 条", SHAPE_SAMPLES) {
            OverlapLayoutEngine.layout(small, PerfData.AVAILABLE_WIDTH, PerfData.GAP).size
        }
        val largeResult = Bench.measure("布局 400 条", SHAPE_SAMPLES) {
            OverlapLayoutEngine.layout(large, PerfData.AVAILABLE_WIDTH, PerfData.GAP).size
        }

        // 比值方向：T(400) / T(200)。近线性算法应≈2，二次复杂度会顶到 4。
        val ratio = largeResult.minNanos.toDouble() / smallResult.minNanos.toDouble()
        println(
            "[增长形状] ${smallResult.describe()}；${largeResult.describe()}；" +
                "T(400)/T(200) = " + ratio.format(2) +
                "（阈值 1.0–" + MAX_GROWTH_RATIO.format(1) + "；比值 2.0 即理想近线性，4.0 即二次）",
        )
        assertTrue(
            "布局耗时随条数翻倍而增长过快（比值 " + ratio.format(2) +
                "，阈值上限 " + MAX_GROWTH_RATIO.format(1) + "）：200 条 " + smallResult.describe() +
                "，400 条 " + largeResult.describe() + "。布局算法是 O(条数 × 最大同时重叠数)，" +
                "而数据集已把最大同时重叠数钉死为常数 2，出现超线性增长说明被引入了额外的一重循环",
            ratio > 1.0 && ratio < MAX_GROWTH_RATIO,
        )
    }

    /** [submitCore] 的结果：布局结果本身 + 一个用于喂给计时夹具的校验值。 */
    private class SubmitOutcome(val blocks: List<PlacedBlock>, val checksum: Int)

    /**
     * 复刻 `DayTimelineView.submitEvents` 的**计算部分**，与 View 源码逐行对应：
     * 记滚动锚点 → 兜底修正 → 推导三态 → 重排分栏 → 还原滚动位置。
     *
     * View 侧的字段赋值、`cacheBlockGeometry` 与 `invalidate()` 没有包含在内——
     * 它们无法在 JVM 侧执行。这一条边界必须在 DL-09 里如实写明。
     */
    private fun submitCore(
        source: List<TimelineEvent>,
        previous: List<PlacedBlock>,
    ): SubmitOutcome {
        val anchor = EventDiff.anchorOf(
            previous, PerfData.SCROLL_OFFSET, PerfData.HOUR_HEIGHT, PerfData.TOP_PADDING,
        )
        val sanitized = EventSanitizer.sanitize(source).events
        val states = TimeStateResolver.resolveAll(sanitized, PerfData.NOW_MINUTE)
        val blocks = OverlapLayoutEngine.layout(sanitized, PerfData.AVAILABLE_WIDTH, PerfData.GAP)
        EventDiff.restoreOffset(
            anchor, blocks, PerfData.HOUR_HEIGHT, PerfData.TOP_PADDING, PerfData.SCROLL_OFFSET,
        )
        // 三态结果在 View 侧会被逐块读取。这里折进校验值，避免整段调用被判定为无副作用
        return SubmitOutcome(blocks, blocks.size + states.size)
    }

    private fun assertWithinBudget(medianNanos: Long, budgetNanos: Long, what: String) {
        val actual = (medianNanos / 1_000_000.0).format3()
        val budget = (budgetNanos / 1_000_000.0).format3()
        assertTrue("$what 中位数 $actual ms，超出 PRD §12.1 的预算 $budget ms", medianNanos <= budgetNanos)
    }

    /** 毫秒数保留三位小数；固定 [Locale.ROOT]，避免土耳其语等 locale 把小数点变成逗号。 */
    private fun Double.format3(): String = String.format(Locale.ROOT, "%.3f", this)

    private fun Double.format(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", this)

    private companion object {
        /** 绝对耗时断言的采样次数：够挡住偶发抖动，又不至于让单测跑太久。 */
        const val SAMPLES = 200

        /** 形状断言的采样次数：比值对噪声更敏感，多采一些。 */
        const val SHAPE_SAMPLES = 400

        /** §12.1「全量布局耗时（200 条）≤ 4ms」。 */
        const val FULL_LAYOUT_BUDGET_NANOS = 4L * 1000L * 1000L

        /** §12.1「列表增量更新耗时（100 条）≤ 16ms（1 帧）」。 */
        const val INCREMENTAL_BUDGET_NANOS = 16L * 1000L * 1000L

        /** 翻倍比值上限。理论值约 2，放到 3 以吸收噪声；真正的二次复杂度会顶到 4。 */
        const val MAX_GROWTH_RATIO = 3.0
    }
}
