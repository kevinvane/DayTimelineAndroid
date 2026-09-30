package com.github.kevinvane.daytimeline.library.core

/**
 * §7.9 W1–W6 的验收用例表。
 *
 * 覆盖 PRD §14.2 的 U1–U16，每条一个具名用例，便于「门禁未通过时精确知道是哪条」。
 * 同时作为 AD-03 的可执行规格说明——读这个文件即可知道算法该产出什么。
 *
 * 用 `Triple` 而非 `id to start to end`：中缀 `to` 左结合，会把
 * `"a" to 540 to 720` 解析成 `("a" to 540) to 720`，类型对不上。
 */
internal object LayoutCases {

    /** 便于断言的固定宽度：600px 可用宽。 */
    const val WIDTH = 600

    /** 3 列场景（900 = 3×300）。 */
    const val WIDTH_3 = 900

    // ---------- U1 两条不重叠，各占满宽度 ----------
    val U1 = layout(WIDTH, Triple("U1_a", 540, 720), Triple("U1_b", 720, 840))

    // ---------- U2 两条部分重叠，各占 1/2 ----------
    val U2 = layout(WIDTH, Triple("U2_a", 540, 720), Triple("U2_b", 600, 660))

    // ---------- U3 长日程包含短日程，各占 1/2 ----------
    val U3 = layout(WIDTH, Triple("U3_long", 540, 1080), Triple("U3_short", 600, 660))

    // ---------- U4 三条互相重叠，各占 1/3 ----------
    val U4 = layout(
        WIDTH_3,
        Triple("U4_a", 540, 720),
        Triple("U4_b", 600, 780),
        Triple("U4_c", 660, 840),
    )

    // ---------- U5 前序日程结束后空出的空闲列必须被复用 ----------
    // A 09:00-12:00 占列0；B 09:30-10:00 占列1；C 11:00-12:00 与 B 不重叠 → 复用列1
    val U5 = layout(
        WIDTH,
        Triple("U5_a", 540, 720),
        Triple("U5_b", 570, 600),
        Triple("U5_c", 660, 720),
    )

    // ---------- U6 长日程 + 内部三条互不重叠短日程，只需两列 ----------
    val U6 = layout(
        WIDTH,
        Triple("U6_long", 540, 1080),
        Triple("U6_s1", 570, 600),
        Triple("U6_s2", 630, 660),
        Triple("U6_s3", 690, 720),
    )

    // ---------- U7 首尾相接判定为不重叠 ----------
    val U7 = layout(WIDTH, Triple("U7_a", 540, 600), Triple("U7_b", 600, 660))

    // ---------- U8 无任何日程 ----------
    val U8: List<PlacedBlock> = layout(WIDTH)

    // ---------- U9 仅一条日程 ----------
    val U9 = layout(WIDTH, Triple("U9", 540, 720))

    // ---------- U10 同一组数据以不同顺序传入，结果必须完全一致 ----------
    private val U10_INPUT = sanitize(
        Triple("U10_a", 540, 720),
        Triple("U10_b", 600, 780),
        Triple("U10_c", 660, 840),
        Triple("U10_d", 600, 660),
    )
    val U10_forward = OverlapLayoutEngine.layout(U10_INPUT, WIDTH_3, 0)
    val U10_reversed = OverlapLayoutEngine.layout(U10_INPUT.reversed(), WIDTH_3, 0)
    val U10_shuffled = OverlapLayoutEngine.layout(
        listOf(U10_INPUT[2], U10_INPUT[0], U10_INPUT[3], U10_INPUT[1]),
        WIDTH_3, 0,
    )

    // ---------- U11 200 条随机日程 ----------
    val U11 = OverlapLayoutEngine.layout(
        sanitize(
            *(1..200).map { i ->
                val start = (i * 7) % 1300
                Triple("U11_$i", start, start + 15 + (i * 13) % 90)
            }.toTypedArray(),
        ),
        WIDTH, 1,
    )

    // ---------- U12 200 条完全同时 ----------
    val U12 = OverlapLayoutEngine.layout(
        sanitize(*(1..200).map { i -> Triple("U12_$i", 540, 600) }.toTypedArray()),
        WIDTH, 0,
    )

    // ---------- U14 右侧相邻列在其纵向范围内为空 → 向右扩展 ----------
    // A 09:00-10:00 占列0；B 10:00-10:30 与 C 10:00-10:15 相互重叠 → 总列数 2。
    // A 的范围内列1 空闲（B/C 从 10:00 才开始）→ A 扩展占满整行（W3）。
    val U14 = layout(
        WIDTH,
        Triple("U14_a", 540, 600),
        Triple("U14_b", 600, 630),
        Triple("U14_c", 600, 615),
    )

    // ---------- U15 右侧相邻列被占用，但再往右一列为空 → 遇占用列立即停止 ----------
    // 列0：A 09:00-12:00；列1：B 09:30-10:30；列2：C 10:00-10:10
    // A 右侧列1 在其范围内被 B 占用 → span=1，**不得跨过列1 去占空闲的列2**。
    val U15 = layout(
        WIDTH_3,
        Triple("U15_a", 540, 720),
        Triple("U15_b", 570, 630),
        Triple("U15_c", 600, 610),
    )

    // ---------- U16 纵向横跨全天的日程占满整行（W4） ----------
    val U16 = layout(WIDTH, Triple("U16_all", 0, 1440))

    // ---------- 工具 ----------

    /** 直接跑布局的简写（gap = 0）。 */
    fun layout(width: Int, vararg entries: Triple<String, Int, Int>): List<PlacedBlock> =
        OverlapLayoutEngine.layout(sanitize(*entries), width, 0)

    /**
     * 把测试数据转成已修正事件。
     * 这些数据都合法（`0 <= start < end <= 1440`），因此不应产生任何 [DataIssue]。
     */
    fun sanitize(vararg entries: Triple<String, Int, Int>): List<SanitizedEvent> =
        EventSanitizer.sanitize(
            entries.map { (id, s, e) -> TestEvent(id, s, e) },
        ).events
}

/** 测试用数据类，模拟业务方对 [TimelineEvent] 的实现。 */
internal data class TestEvent(
    override val id: String,
    private val startMinute: Int,
    private val endMinute: Int,
    override val expiredOverride: Boolean? = null,
    override val content: CharSequence? = null,
) : TimelineEvent {
    override val start: MinuteOfDay get() = MinuteOfDay.ofMinute(startMinute)
    override val end: MinuteOfDay get() = MinuteOfDay.ofMinute(endMinute)
}
