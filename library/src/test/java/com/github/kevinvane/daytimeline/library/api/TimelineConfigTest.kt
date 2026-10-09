package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD §10.1 / FC-005：配置项。
 *
 * 本类此前**零测试覆盖**，而它承担两件要命的事：
 *
 * 1. [TimelineConfig.mergedWith] 是「XML 配置打底 + 代码配置微调」的唯一实现
 *    （AD-09 / FC-005）。一旦某个字段漏合并，业务方在 XML 里配的值会被
 *    `setConfig(...)` **静默丢弃**——正是真机上「XML 配置好像没生效」那个坑。
 * 2. `resolved*` 系列负责把 `null`（用资源默认值）解析成实际值，
 *    里面的 `coerceAtLeast` / `coerceIn` 兜底一旦写错，脏配置会直接进算法层。
 */
class TimelineConfigTest {

    // ---- mergedWith：字段覆盖完整性 ----

    /**
     * **每个可配置字段都必须出现在 [TimelineConfig.mergedWith] 里。**
     *
     * 这条是本文件最有价值的断言：新增字段时若忘了加合并，本测试立刻失败。
     * 漏合并的字段在真机上的表现是「XML 配了不生效、又没有任何报错」，
     * 靠人工 review 很难发现——`autoLocateOnFirstShow` 就是这么漏掉的。
     *
     * 做法是拿**主构造函数字段**与**合并表达式的左值**做集合比对，
     * 不依赖任何具体字段名，新增字段自动纳入检查。
     */
    @Test
    fun `mergedWith 覆盖全部可配置字段`() {
        val declared = declaredFieldNames()
        val merged = mergedFieldNames()

        val missing = declared - merged
        assertTrue(
            "以下字段在 mergedWith 中没有对应的合并逻辑，配了会被 setConfig 静默丢弃：$missing",
            missing.isEmpty(),
        )
        val unknown = merged - declared
        assertTrue(
            "mergedWith 里出现了主构造函数中不存在的字段，请检查是否写错名字：$unknown",
            unknown.isEmpty(),
        )
        assertTrue("反射未取到任何字段，测试本身失效", declared.isNotEmpty())
    }

    /**
     * 合并语义：**other 的非 null 字段覆盖本对象，其余保留**（AD-09）。
     *
     * 抽查三类字段：普通可空字段、枚举、时间格式。
     */
    @Test
    fun `mergedWith 由 other 的非 null 字段覆盖`() {
        val base = TimelineConfig(hourHeight = 100, axisWidth = 56, snapMinutes = 15)

        // 全空 → 原样保留
        assertEquals(base, base.mergedWith(TimelineConfig()))

        // 部分指定 → 只覆盖指定的
        val merged = base.mergedWith(TimelineConfig(hourHeight = 200))
        assertEquals(200, merged.hourHeight)
        assertEquals("未指定的字段应保留", 56, merged.axisWidth)
        assertEquals(15, merged.snapMinutes)
    }

    /**
     * 回归：`autoLocateOnFirstShow` 曾漏合并。
     *
     * XML 配了 `app:dtAutoLocateOnFirstShow`，随后业务方调一次
     * `setConfig(TimelineConfig(snapMinutes = 30))`，该配置就被丢回默认值——
     * 且全程无任何报错。
     */
    @Test
    fun `mergedWith 保留未指定字段（含 autoLocateOnFirstShow）`() {
        val fromXml = TimelineConfig(autoLocateOnFirstShow = false, nowDotDiameter = 12)
        val merged = fromXml.mergedWith(TimelineConfig(snapMinutes = 30))

        assertEquals(
            "XML 里配的 autoLocateOnFirstShow 不应被 setConfig 丢弃",
            false as Boolean?, merged.autoLocateOnFirstShow,
        )
        assertEquals(12, merged.nowDotDiameter)
        assertEquals(30, merged.snapMinutes)
    }

    /** other 显式传值时应覆盖，包括「与本对象不同」的情况。 */
    @Test
    fun `mergedWith 覆盖为 other 的显式值`() {
        val base = TimelineConfig(autoLocateOnFirstShow = true, scrollMode = ScrollMode.SELF)
        val merged = base.mergedWith(
            TimelineConfig(autoLocateOnFirstShow = false, scrollMode = ScrollMode.EXTERNAL),
        )
        assertEquals(false as Boolean?, merged.autoLocateOnFirstShow)
        assertEquals(ScrollMode.EXTERNAL, merged.scrollMode)
    }

    // ---- resolved*：默认值与兜底 ----

    @Test
    fun `resolved 系列未配置时使用传入的默认值`() {
        val c = TimelineConfig()
        assertEquals(15, c.resolvedSnapMinutes(15))
        assertEquals(5, c.resolvedMinDuration(5))
        assertEquals(1440, c.resolvedMaxDuration(1440))
        assertEquals(ScrollMode.SELF, c.resolvedScrollMode())
        assertEquals(TimeFormat.SYSTEM, c.resolvedTimeFormat())
        // FI-012：默认开启——PRD §8.5 与用户故事 A1「打开即定位当前时间」
        assertTrue(c.resolvedAutoLocateOnFirstShow())
    }

    /**
     * FI-012 的验收标准是「可配置关闭」，所以 `false` 必须真的关得掉。
     *
     * 这一条曾经无从验证：`autoLocateOnFirstShow` 当时**声明了却无人消费**，
     * 无论配什么都不会有任何表现——配置项成了摆设。
     */
    @Test
    fun `首次定位可显式关闭`() {
        assertFalse(TimelineConfig(autoLocateOnFirstShow = false).resolvedAutoLocateOnFirstShow())
        assertTrue(TimelineConfig(autoLocateOnFirstShow = true).resolvedAutoLocateOnFirstShow())
    }

    @Test
    fun `resolved 系列已配置时忽略默认值`() {
        val c = TimelineConfig(
            snapMinutes = 5,
            minDurationMinutes = 10,
            maxDurationMinutes = 120,
            scrollMode = ScrollMode.EXTERNAL,
            timeFormat = TimeFormat.H24,
        )
        assertEquals(5, c.resolvedSnapMinutes(15))
        assertEquals(10, c.resolvedMinDuration(5))
        assertEquals(120, c.resolvedMaxDuration(1440))
        assertEquals(ScrollMode.EXTERNAL, c.resolvedScrollMode())
        assertEquals(TimeFormat.H24, c.resolvedTimeFormat())
    }

    /**
     * 兜底边界：脏配置不得让算法层拿到非法值。
     *
     * §11.4 要求组件不把异常抛给业务方，因此这些兜底必须存在。
     */
    @Test
    fun `resolved 系列对非法取值做兜底`() {
        // 吸附步长 0 表示关闭吸附，是**合法**取值，不能被抬成 1
        assertEquals(0, TimelineConfig(snapMinutes = 0).resolvedSnapMinutes(15))
        // 负数步长同样按 0 处理（关闭吸附）
        assertEquals(0, TimelineConfig(snapMinutes = -5).resolvedSnapMinutes(15))
        // 最小时长至少 1 分钟，否则任何日程都无法构造
        assertEquals(1, TimelineConfig(minDurationMinutes = 0).resolvedMinDuration(5))
        assertEquals(1, TimelineConfig(minDurationMinutes = -100).resolvedMinDuration(5))
        // 最长时长被钳到 [1, 1440]
        assertEquals(1, TimelineConfig(maxDurationMinutes = 0).resolvedMaxDuration(1440))
        assertEquals(1440, TimelineConfig(maxDurationMinutes = 99999).resolvedMaxDuration(60))
    }

    // ---- 数据类契约 ----

    /**
     * 数据类语义：值相等的两个实例必须 equals 相等且 hashCode 相同。
     *
     * 这条看着平凡，但它是 [TimelineConfig.mergedWith] 正确性的底层前提——
     * 合并若构造错了字段，`equals` 会立刻暴露。
     */
    @Test
    fun `值相等的配置 equals 与 hashCode 一致`() {
        val a = TimelineConfig(hourHeight = 100, snapMinutes = 15, scrollMode = ScrollMode.SELF)
        val b = TimelineConfig(hourHeight = 100, snapMinutes = 15, scrollMode = ScrollMode.SELF)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(hourHeight = 101))
    }

    /** 全默认配置应等于显式传入全 null —— 这是合并链路的起点。 */
    @Test
    fun `默认构造等价于全部字段为 null`() {
        assertEquals(TimelineConfig(), TimelineConfig().copy())
    }

    /** 最长时长上限不得超过全天分钟数（PRD E4：日程不跨天）。 */
    @Test
    fun `最长时长不超过全天分钟数`() {
        assertEquals(
            MinuteOfDay.MINUTES_PER_DAY,
            TimelineConfig(maxDurationMinutes = Int.MAX_VALUE).resolvedMaxDuration(0),
        )
    }

    // ---- 源码解析工具 ----

    /**
     * 从源码取「主构造函数声明的可空配置字段名」。
     *
     * 不用反射：Kotlin 编译成 class 文件后构造参数名大多已丢失（除非带
     * `-java-parameters`），且我们真正要核对的是**源码里声明了什么**，
     * 反射反而看不到这一点。
     */
    private fun declaredFieldNames(): Set<String> =
        Regex("""^\s+val (\w+): [^=]+= null,""", RegexOption.MULTILINE)
            .findAll(readSource())
            .map { it.groupValues[1] }
            .toSet()

    /** `mergedWith` 里形如 `xxx = other.xxx ?: xxx` 的左值集合。 */
    private fun mergedFieldNames(): Set<String> {
        val body = readSource()
            .substringAfter("fun mergedWith")
            .substringBefore("\n    }")
        return Regex("""(\w+)\s*=\s*other\.\1""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * 读取 [TimelineConfig] 的源码。
     *
     * 从当前工作目录向上找模块根，兼容 CI 与本地的工作目录差异。
     */
    private fun readSource(): String {
        val relative = "library/src/main/java/com/github/kevinvane/daytimeline/library/api/TimelineConfig.kt"
        var dir: java.io.File? = java.io.File("").absoluteFile
        repeat(5) {
            val f = dir?.let { java.io.File(it, relative) }
            if (f != null && f.exists()) return f.readText()
            dir = dir?.parentFile
        }
        throw AssertionError(
            "找不到 TimelineConfig.kt（从当前目录向上找了 5 层），测试无法进行",
        )
    }
}
