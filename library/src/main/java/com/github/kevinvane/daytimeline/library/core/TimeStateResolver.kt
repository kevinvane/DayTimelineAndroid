package com.github.kevinvane.daytimeline.library.core

/** 日程相对当前时刻的三态（PRD §9.4）。 */
enum class EventState {
    /** 已过：结束时间 ≤ 当前时刻。 */
    PAST,

    /** 进行中：开始时间 ≤ 当前时刻 < 结束时间。 */
    ONGOING,

    /** 未到。 */
    UPCOMING,
}

/**
 * 三态推导（PRD §9.4 / FR-009）。
 *
 * 规则原文：
 * ```
 * 某日程为「已过」  ⟺  结束时间 ≤ 当前时刻
 * 某日程为「进行中」⟺  开始时间 ≤ 当前时刻 < 结束时间
 * 其余               ⟺  「未到」
 * ```
 * 加上业务方覆盖（§9.4 状态覆盖规则）：
 * 1. 声明「已过期」→ 强制已过配色；
 * 2. 声明「未过期」→ **跳过「已过」判定**，继续按进行中/未到归类；
 * 3. 因此不会出现「既非已过、又非进行中、却被当作未到」的矛盾状态。
 *
 * 纯函数，无状态，可直接单测。
 */
object TimeStateResolver {

    fun resolve(event: SanitizedEvent, nowMinute: Int): EventState {
        val override = event.expiredOverride
        // 规则 1：显式已过期 → 强制已过，跳过时间判定
        if (override == true) return EventState.PAST
        // 规则 2：显式未过期 → 跳过「已过」判定
        if (override == false) {
            return if (event.start.minuteOfDay <= nowMinute) EventState.ONGOING else EventState.UPCOMING
        }
        if (event.end.minuteOfDay <= nowMinute) return EventState.PAST
        if (event.start.minuteOfDay <= nowMinute) return EventState.ONGOING
        return EventState.UPCOMING
    }

    fun resolveAll(events: List<SanitizedEvent>, nowMinute: Int): Map<String, EventState> {
        if (events.isEmpty()) return emptyMap()
        val out = HashMap<String, EventState>(events.size.coerceAtLeast(16))
        for (e in events) {
            // E7 允许重复 id；这里保留**最后一条**的推导结果，
            // 与布局层「重复 id 不去重、都画出来」的行为在视觉上保持一致。
            out[e.id] = resolve(e, nowMinute)
        }
        return out
    }
}
