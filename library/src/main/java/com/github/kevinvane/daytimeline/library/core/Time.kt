package com.github.kevinvane.daytimeline.library.core

/**
 * 「当天的第几分钟」值类型。
 *
 * PRD D18 / D19 / §9.1：时间必须以「几点几分」表达，且要让「传错时间单位」在**编译期**
 * 就不可能发生。这里的做法是 `@JvmInline value class` + **私有构造函数**：
 * 业务方拿不到裸 `Int`，只能经 [of] / [parse] / [START_OF_DAY] 等工厂构造，
 * 因此 `event.start = 90` 这类把「小时」误当「分钟」的代码编译不过。
 *
 * 私有构造函数是本类型成立的关键，**不要**为了方便改成 public，也不要加 `operator invoke`。
 *
 * 取值域固定为 `[0, 1440]`：0 表示 00:00，1440 表示 24:00（当日末尾，不跨天）。
 * 越界值一律被静默钳制而非抛异常——PRD Q4 要求任何输入下组件都不把异常抛给业务方。
 *
 * 纯 Kotlin，无任何 `android.*` 依赖（AD-02 / T2）。
 */
@JvmInline
value class MinuteOfDay private constructor(val minuteOfDay: Int) {

    /** 小时部分 `[0, 24]`。1440 分钟对应 24。 */
    val hour: Int get() = minuteOfDay / MINUTES_PER_HOUR

    /** 分钟部分 `[0, 59]`。 */
    val minute: Int get() = minuteOfDay % MINUTES_PER_HOUR

    /** 距 24:00 还有多少分钟，用于「是否全天贯穿」这类判断。 */
    val minutesToEndOfDay: Int get() = END_OF_DAY_MINUTE - minuteOfDay

    fun plusMinutes(delta: Int): MinuteOfDay = ofMinute(minuteOfDay + delta)

    fun minusMinutes(delta: Int): MinuteOfDay = ofMinute(minuteOfDay - delta)

    /**
     * 与 [other] 的间隔分钟数；为负时取 0（不做负值，因为调用方只关心「相隔多远」）。
     */
    fun minutesUntil(other: MinuteOfDay): Int = (other.minuteOfDay - minuteOfDay).coerceAtLeast(0)

    /** 严格晚于 [other] 为真；相等不算重叠（PRD U7：首尾相接判定为不重叠）。 */
    fun isStrictlyAfter(other: MinuteOfDay): Boolean = minuteOfDay > other.minuteOfDay

    fun isAtOrBefore(other: MinuteOfDay): Boolean = minuteOfDay <= other.minuteOfDay

    override fun toString(): String {
        val h = hour
        val m = minute
        return if (h == HOURS_PER_DAY && m == 0) {
            "24:00"
        } else {
            "${if (h < 10) "0$h" else "$h"}:${if (m < 10) "0$m" else "$m"}"
        }
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val HOURS_PER_DAY = 24
        const val MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY
        const val END_OF_DAY_MINUTE = MINUTES_PER_DAY

        /** 00:00。 */
        val START_OF_DAY: MinuteOfDay = MinuteOfDay(0)

        /** 24:00。当日末尾，不跨天（PRD E4：超过 24:00 的结束时间应截断到此）。 */
        val END_OF_DAY: MinuteOfDay = MinuteOfDay(END_OF_DAY_MINUTE)

        /**
         * 由小时 + 分钟构造。
         *
         * **刻意不做范围钳制**：脏数据必须能原样传进来，才能被
         * [EventSanitizer] 检出并通过「数据异常」事件上抛（PRD E4 / E5）。
         * 如果在构造时就静默钳制，这两条校验会变成永远不执行的死代码，
         * 业务方也就永远不知道自己传了越界数据。
         *
         * 归一化（钳制到 `[0, 1440]`、修正非正时长）统一由
         * [EventSanitizer] 负责——它才是校验层，值类型只负责保证类型安全。
         */
        fun of(hour: Int, minute: Int): MinuteOfDay =
            MinuteOfDay(hour * MINUTES_PER_HOUR + minute)

        /**
         * 由「当天第几分钟」构造，`0..1440`。
         *
         * 供组件内部算法自由用整数运算，避免反复构造/解包。
         * 同样不钳制，理由见 [of]。
         */
        fun ofMinute(minuteOfDay: Int): MinuteOfDay = MinuteOfDay(minuteOfDay)

        /**
         * 解析 `"09:30"` / `"9:30"` / `"24:00"`。格式非法返回 null，由上层记为数据异常。
         * 刻意不抛异常，与 Q4「不把异常抛给业务方」一致。
         */
        fun parse(text: String): MinuteOfDay? {
            val trimmed = text.trim()
            val colon = trimmed.indexOf(':')
            if (colon <= 0 || colon == trimmed.length - 1) return null
            val h = trimmed.substring(0, colon).trim()
            val m = trimmed.substring(colon + 1).trim()
            val hour = h.toIntOrNull() ?: return null
            val minute = m.toIntOrNull() ?: return null
            if (hour < 0 || hour > HOURS_PER_DAY) return null
            if (minute < 0 || minute >= MINUTES_PER_HOUR) {
                // 允许 "24:00" 这种整点写法，其余非法
                return if (hour == HOURS_PER_DAY && minute == 0) END_OF_DAY else null
            }
            return of(hour, minute)
        }
    }
}
