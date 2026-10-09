package com.github.kevinvane.daytimeline.library.core

/** 单条脏数据的修正记录（PRD §9.3 / §11.3「数据异常」事件）。 */
data class DataIssue(
    /** 出问题的日程标识。无法定位时为空串。 */
    val eventId: String,
    /** 该条目在业务方传入列表中的下标。 */
    val index: Int,
    /** 问题类型。 */
    val kind: Kind,
    /** 人类可读的说明，供业务方记录排查。 */
    val message: String,
) {
    enum class Kind {
        /** 结束时间早于或等于开始时间，已按最短时长修正（E3）。 */
        NON_POSITIVE_DURATION,

        /** 结束时间晚于 24:00，已截断（E4）。 */
        END_AFTER_MIDNIGHT,

        /** 开始时间早于 00:00，已修正（E5）。 */
        START_BEFORE_MIDNIGHT,

        /** 唯一标识重复（E7）。 */
        DUPLICATE_ID,

        /** 标识为空。 */
        BLANK_ID,
    }
}

/**
 * 已完成兜底修正、可直接交给布局引擎的日程。
 *
 * 这是**组件内部**类型，不对业务方暴露：业务方看到的始终是自己传入的对象。
 * 修正对用户不可见（PRD §9.3：避免视觉跳变），只通过事件上抛。
 */
data class SanitizedEvent(
    val id: String,
    val start: MinuteOfDay,
    val end: MinuteOfDay,
    val source: TimelineEvent,
) {
    /** 供 §9.4 状态推导与配色选择。 */
    val expiredOverride: Boolean? get() = source.expiredOverride

    /** 供绘制层取显示内容。 */
    val content: CharSequence? get() = source.content

    /** 业务色（PRD §7.7.1）；null 表示使用组件默认块配色。 */
    val color: Int? get() = source.color
}

/**
 * 脏数据兜底修正（PRD §9.3 / E3–E7 / Q4）。
 *
 * 原则：**容错 + 上抛，绝不崩溃**。所有修正都在这里集中完成，
 * 布局引擎拿到的必然是 `[00:00, 24:00]` 内且 `end > start` 的数据，
 * 因此下游（尤其 [OverlapLayoutEngine]）可以完全不做防御性判断——这是 Q4 能稳定成立的关键。
 *
 * 纯 Kotlin，无 `android.*` 依赖（AD-02 / T2）。
 */
object EventSanitizer {

    /**
     * 批量修正。返回修正后的列表与异常清单。
     *
     * @param events 业务方传入的日程，顺序不重要（[OverlapLayoutEngine] 内部会重排）。
     * @param minDurationMinutes 最短时长（分钟）。E3 的非正时长按此修正。
     * @param maxDurationMinutes 最长时长（分钟）。截断到 24:00 后天然不会超过。
     */
    fun sanitize(
        events: List<TimelineEvent>,
        minDurationMinutes: Int = 5,
        maxDurationMinutes: Int = MinuteOfDay.MINUTES_PER_DAY,
    ): SanitizeResult {
        val issues = ArrayList<DataIssue>()
        val seenIds = HashSet<String>(events.size.coerceAtLeast(16))
        val out = ArrayList<SanitizedEvent>(events.size)
        val safeMin = minDurationMinutes.coerceAtLeast(1)

        events.forEachIndexed { index, event ->
            val id = event.id
            if (id.isBlank()) {
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.BLANK_ID,
                    message = "第 $index 条日程的标识为空，已按空标识处理",
                )
            } else if (!seenIds.add(id)) {
                // E7：重复标识正常显示，不去重（业务方可能真的有两段同名日程）
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.DUPLICATE_ID,
                    message = "标识「$id」重复，已正常显示",
                )
            }

            var start = event.start
            var end = event.end

            if (start.minuteOfDay < 0) {
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.START_BEFORE_MIDNIGHT,
                    message = "第 $index 条日程的开始时间早于 00:00，已修正",
                )
                start = MinuteOfDay.START_OF_DAY
            }

            // 开始时间晚于 24:00：属于「整体越界」，一并归入同一类问题。
            // 钳到 24:00 后若 end <= start，会被下面的最短时长逻辑接住。
            if (start.minuteOfDay > MinuteOfDay.END_OF_DAY_MINUTE) {
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.START_BEFORE_MIDNIGHT,
                    message = "第 $index 条日程的开始时间晚于 24:00，已修正",
                )
                start = MinuteOfDay.END_OF_DAY
            }

            if (end.minuteOfDay < 0) {
                // 结束时间早于 00:00：等价于非正时长，交给最短时长逻辑处理。
                end = MinuteOfDay.START_OF_DAY
            }

            if (end.minuteOfDay > MinuteOfDay.END_OF_DAY_MINUTE) {
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.END_AFTER_MIDNIGHT,
                    message = "第 $index 条日程的结束时间晚于 24:00，已截断",
                )
                end = MinuteOfDay.END_OF_DAY
            }

            // E3：结束时间不晚于开始时间 → 按最短时长兜底，并保证不越过 24:00。
            if (!end.isStrictlyAfter(start)) {
                issues += DataIssue(
                    eventId = id,
                    index = index,
                    kind = DataIssue.Kind.NON_POSITIVE_DURATION,
                    message = "第 $index 条日程的结束时间不晚于开始时间，已修正为最短时长",
                )
                val anchoredStart = if (start.minuteOfDay + safeMin <= MinuteOfDay.END_OF_DAY_MINUTE) {
                    start
                } else {
                    // 起点已贴近 24:00，无法向后撑开最短时长，改为向前回退。
                    MinuteOfDay.ofMinute(
                        (MinuteOfDay.END_OF_DAY_MINUTE - safeMin).coerceAtLeast(0),
                    )
                }
                start = anchoredStart
                end = MinuteOfDay.ofMinute(
                    (anchoredStart.minuteOfDay + safeMin)
                        .coerceAtMost(MinuteOfDay.END_OF_DAY_MINUTE),
                )
            }

            // 最长时长兜底（§8.3）。起点优先下移以保留「已开始」语义。
            if (maxDurationMinutes in 1 until MinuteOfDay.MINUTES_PER_DAY) {
                val span = end.minuteOfDay - start.minuteOfDay
                if (span > maxDurationMinutes) {
                    val newEnd = MinuteOfDay.ofMinute(start.minuteOfDay + maxDurationMinutes)
                    if (newEnd.minuteOfDay <= MinuteOfDay.END_OF_DAY_MINUTE) {
                        end = newEnd
                    } else {
                        end = MinuteOfDay.END_OF_DAY
                        start = MinuteOfDay.ofMinute(
                            (MinuteOfDay.END_OF_DAY_MINUTE - maxDurationMinutes).coerceAtLeast(0),
                        )
                    }
                }
            }

            out += SanitizedEvent(
                id = id,
                start = start,
                end = end,
                source = event,
            )
        }

        return SanitizeResult(events = out, issues = issues)
    }

    data class SanitizeResult(
        val events: List<SanitizedEvent>,
        val issues: List<DataIssue>,
    )
}
