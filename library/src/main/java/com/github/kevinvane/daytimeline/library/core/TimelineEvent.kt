package com.github.kevinvane.daytimeline.library.core

/**
 * 业务方日程数据的对外契约（PRD §9.1 / FD-001）。
 *
 * **刻意做成 interface 而非 abstract class**：PRD FD-001 明确「不要求业务方继承任何基类」，
 * 避免侵入业务方既有模型。
 *
 * 三个必填项全部使用 [MinuteOfDay] 值类型（D18/D19）：把「小时」误传成「分钟」编译不过。
 *
 * 业务方声明自己数据的写法：
 * ```kotlin
 * data class Meeting(
 *     val id: String,
 *     val beginAt: LocalTime,
 *     val endAt: LocalTime,
 *     val title: String,
 * ) : TimelineEvent {
 *     override val start get() = MinuteOfDay.of(beginAt.hour, beginAt.minute)
 *     override val end get() = MinuteOfDay.of(endAt.hour, endAt.minute)
 *     override val content get() = title
 * }
 * ```
 */
interface TimelineEvent {

    /** 唯一标识。重复时按 PRD E7：正常显示 + 上抛数据异常，不崩溃。 */
    val id: String

    /** 当天开始时刻。 */
    val start: MinuteOfDay

    /** 当天结束时刻。必须晚于 [start]；不满足时由组件兜底修正（E3）。 */
    val end: MinuteOfDay

    /**
     * 「是否已过期」的状态覆盖（PRD §9.4）。业务方如需按任务状态而非时间判断，可显式声明。
     *
     * - `true`：强制使用已过配色。
     * - `false`：跳过「已过」判定，继续按「进行中 / 未到」归类。
     * - `null`（默认）：完全按时间推导。
     *
     * 业务方**不能**直接指定「进行中」；需要该状态请用 `false` + 时间落在区间内。
     */
    val expiredOverride: Boolean? get() = null

    /** 显示内容。色块上显示什么由业务方自定，无格式限制；为 null 时读屏用占位文案。 */
    val content: CharSequence? get() = null
}
