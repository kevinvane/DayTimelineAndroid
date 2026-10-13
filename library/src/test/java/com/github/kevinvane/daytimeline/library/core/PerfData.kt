package com.github.kevinvane.daytimeline.library.core

import java.util.Locale

/**
 * §12.1 性能基准的数据集与计时夹具。
 *
 * ## 为什么要单独一个文件
 *
 * §12.1 的三个数字——「全量布局耗时（200 条）≤ 4ms」「列表增量更新耗时（100 条）≤ 16ms」
 * 「滚动流畅度」——此前只有两个 U 用例在**验算法对不对**（`LayoutCases.U11` / `U12`），
 * 从来没有测过**跑得快不快**。于是 PRD §12.4 的「性能基准」这一栏只能写「部分落地」。
 * 本文件把能自动化的那部分补上：布局与增量更新都在纯 `core` 里、零 `android.*` 依赖，
 * 因此**不需要设备**就能在 JVM 上计时。
 *
 * ## 三种数据集各自守什么
 *
 * | 数据集 | 最大同时重叠数 | 用途 |
 * |---|---|---|
 * | [allOverlapping] | = 条数（最坏） | 绝对耗时门禁——门禁必须按最坏情况卡 |
 * | [realistic] | 随条数缓慢增长 | 绝对耗时门禁——§12.1 承诺的典型区间 |
 * | [fixedOverlap] | **恒为 2** | 增长形状门禁——见该函数的说明 |
 *
 * 用稀疏数据集去卡 4ms 是自欺：算法在稀疏数据上本来就快。**必须有一条最坏情况的门禁**，
 * 否则「4ms 达标」这句话在 PRD 里就没有对应的证据。
 *
 * 反过来也有一条实测结论值得记下来：**「完全同时」这组数据对算法退化最不敏感**。
 * 退化实现（如两两区间比较）在全部重叠的数据上第一次比较就冲突、内层循环立刻 `break`，
 * 耗时几乎不变（0.072ms vs 0.072ms）；同一处退化在 [realistic] 上却涨了 17 倍。
 * 所以三条用例必须并存——最坏情况卡上界，真实分布卡灵敏度，固定重叠卡增长形状。
 */
internal object PerfData {

    /** §12.1 的承诺基准量级：单日 200 条日程。 */
    const val DAY_SIZE = 200

    /** §12.1 中「列表增量更新耗时」那一行单独标注的基准量级。 */
    const val UPDATE_BASELINE = 100

    /** 增长形状门禁的翻倍档位：与 [DAY_SIZE] 组成 200 → 400 的对照。 */
    const val DOUBLE_SIZE = 400

    /** 可用宽度（已扣除时间轴与右侧边距）。取 1080px ≈ 360dp × density 3。 */
    const val AVAILABLE_WIDTH = 1080

    /** 相邻列间距。 */
    const val GAP = 8

    /** 每小时格高 56dp × density 3。 */
    const val HOUR_HEIGHT = 168

    /** 顶部留白。 */
    const val TOP_PADDING = 32

    /** 视口内用于计算滚动锚点的偏移。 */
    const val SCROLL_OFFSET = 2400

    /** 「当前时间」固定在 12:00，让三态推导走遍已过 / 进行中 / 未到三条分支。 */
    const val NOW_MINUTE = 720

    /**
     * 最坏情况：[count] 条**完全同时**。
     *
     * 这是 §12.1 承诺范围（≤200 条）里最密集的一种排布，
     * 也是 [OverlapLayoutEngine] 复杂度 `O(n × 最大同时重叠数)` 的上界取值——
     * 此时最大同时重叠数 = n，整体退化为 `O(n²)`。它必须进绝对耗时门禁。
     */
    fun allOverlapping(count: Int): List<SanitizedEvent> =
        sanitizeAll(count, 540, 600, "worst")

    /**
     * 真实分布：条数越多、密度越高，但最大同时重叠数只随条数**缓慢**增长。
     *
     * 这才是 §12.1 说的「从稀疏到密集的典型区间」——一天的日程天然集中在工作时段，
     * 不可能 200 条全部重叠。
     */
    fun realistic(count: Int): List<SanitizedEvent> {
        val random = Lcg(SEED)
        val out = ArrayList<TestEvent>(count)
        // 08:00–22:00 是日程密集段，夜里留空——与真实一天同形
        val workStart = 8 * 60
        val workEnd = 22 * 60
        val span = workEnd - workStart
        for (i in 0 until count) {
            val start = workStart + random.next(span)
            val duration = 15 + random.next(105) // 15–120 分钟
            out += TestEvent("real_$i", start, minOf(start + duration, workEnd))
        }
        return EventSanitizer.sanitize(out).events
    }

    /**
     * 固定重叠度：每条只与**紧邻的下一条**重叠，最大同时重叠数**恒为 2**。
     *
     * 这是增长形状门禁（`T(400) / T(200)`）能成立的前提：
     * 若把条数翻倍的同时密度也翻倍，`O(n × 最大重叠数)` 里的第二项也跟着翻倍，
     * 比值就会逼近 4——**算法没退化也会红**，那种门禁只会制造噪声。
     * 这里把第二项钉死为常数，条数翻倍带来的理论比值约为 2（实测 2.0–2.3），
     * 于是「比值明显大于 2」就一定意味着有人在算法里引入了额外的一重循环。
     */
    fun fixedOverlap(count: Int): List<SanitizedEvent> {
        val stride = MinuteOfDay.MINUTES_PER_DAY / count
        val out = ArrayList<TestEvent>(count)
        for (i in 0 until count) {
            val start = i * stride
            // 时长取 1.5 倍步长：只压到下一条，最大同时重叠数因此恒为 2
            val end = minOf(start + (stride * 3) / 2, MinuteOfDay.MINUTES_PER_DAY)
            out += TestEvent("shape_$i", start, end)
        }
        return EventSanitizer.sanitize(out).events
    }

    /** 批量构造并兜底修正；[prefix] 只影响 id，便于失败信息里看出是哪组数据。 */
    private fun sanitizeAll(count: Int, start: Int, end: Int, prefix: String): List<SanitizedEvent> =
        EventSanitizer.sanitize(
            (0 until count).map { i -> TestEvent("${prefix}_$i", start, end) },
        ).events

    /**
     * 线性同余发生器。
     *
     * 刻意不用 `kotlin.random.Random`：性能用例失败时要能原样复现，
     * 固定种子的自写发生器在任何 JDK 上都给出同一串数。
     */
    private class Lcg(seed: Long) {
        private var state = seed

        fun next(bound: Int): Int {
            state = state * MULTIPLIER + INCREMENT
            return ((state ushr 33) % bound).toInt()
        }
    }

    private const val MULTIPLIER = 6364136223846793005L
    private const val INCREMENT = 1442695040888963407L
    private const val SEED = 20261013L
}

/**
 * 计时夹具：预热 → 采样 → 排序，返回可查询的耗时分布。
 *
 * 两个口径各有用途，**不能混用**：
 *
 * | 口径 | 抗干扰 | 用途 |
 * |---|---|---|
 * | [Result.medianNanos] | 抗 GC 与调度抖动 | 拿去和 PRD 的毫秒指标比 |
 * | [Result.minNanos] | 抗抖动（多次里最快的那次） | 拿去算比值——中位数里的噪声会被除法放大 |
 *
 * 预热不是可选项：不预热的话前若干次调用还在解释执行或边解释边编译，
 * 测出来的数字里混着 JIT 编译成本，条数越多噪声占比越不均匀。
 */
internal object Bench {

    /**
     * 被测结果的累加值。
     *
     * 计时循环里如果被测代码的返回值没人用，JIT 有可能判定它无副作用而整段消掉，
     * 于是「跑得飞快」——这正是性能测试最容易自欺的一种方式。这里把每次结果都吃进 [sink]。
     */
    private var sink = 0L

    private const val WARMUP_BUDGET_NANOS = 400L * 1000L * 1000L
    private const val MAX_WARMUP_ROUNDS = 200_000

    class Result(private val label: String, private val nanos: LongArray) {

        val medianNanos: Long get() = nanos[nanos.size / 2]

        val minNanos: Long get() = nanos[0]

        fun describe(): String =
            "$label：最小 ${millis(minNanos)}，中位数 ${millis(medianNanos)}（${nanos.size} 次采样）"

        private fun millis(value: Long): String =
            String.format(Locale.ROOT, "%.3f ms", value / 1_000_000.0)
    }

    /**
     * 跑 [block] [samples] 次并记录每次耗时（调用顺序即数据顺序，耗时会被排序后返回）。
     *
     * [block] 必须有返回值且结果会被消费，见 [sink]。
     */
    fun measure(label: String, samples: Int, block: () -> Int): Result {
        val warmStart = System.nanoTime()
        var rounds = 0
        while (System.nanoTime() - warmStart < WARMUP_BUDGET_NANOS && rounds < MAX_WARMUP_ROUNDS) {
            sink += block()
            rounds++
        }
        val collected = LongArray(samples)
        for (i in 0 until samples) {
            val begin = System.nanoTime()
            val produced = block()
            collected[i] = System.nanoTime() - begin
            sink += produced
        }
        collected.sort()
        check(sink != 0L) { "计时夹具异常：被测代码一次都没产出结果，说明 block 被优化掉了" }
        return Result(label, collected)
    }
}
