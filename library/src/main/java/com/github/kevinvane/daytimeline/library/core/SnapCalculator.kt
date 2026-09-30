package com.github.kevinvane.daytimeline.library.core

/**
 * 时间吸附（PRD §8.3 / D11）。
 *
 * **上下对称是硬要求（D11）**：§8.3 明确「距刻度线向上 7 分钟与向下 8 分钟，吸附结果应一致」。
 * 本实现采用「四舍五入到最近步长」而非「向下取整」，正是为满足该条：
 * 取整算子对正负方向天然对称。
 *
 * 吸附步长为 0 时不做对齐（§8.3「关闭吸附」），但最短/最长时长兜底**仍然生效**。
 *
 * 纯函数，无 `android.*` 依赖（AD-02 / T2）。
 */
object SnapCalculator {

    /**
     * 把 [raw] 吸附到最近的 [stepMinutes] 刻度。
     *
     * 对称性证明：结果为 `((raw + step/2) / step) * step`（整数除法向下取整）。
     * 对 raw = k*step - 1 与 raw = k*step + 1，两者分别得到 (k-1)*step 与 k*step，
     * 即「越过刻度线前 1 分钟」与「越过刻度线后 1 分钟」落到**相邻两个**刻度，
     * 距各自最近刻度均为 1 分钟——方向无关，故上下对称。
     *
     * @param stepMinutes 吸附步长，`<= 0` 表示关闭吸附。
     * @param boundMin 吸附后的下界（含）。
     * @param boundMax 吸附后的上界（含）。
     */
    fun snap(
        raw: Int,
        stepMinutes: Int,
        boundMin: Int = 0,
        boundMax: Int = MinuteOfDay.END_OF_DAY_MINUTE,
    ): Int {
        val lo = minOf(boundMin, boundMax)
        val hi = maxOf(boundMin, boundMax)
        val snapped = if (stepMinutes <= 0) {
            raw
        } else {
            val half = stepMinutes / 2
            // +half 后整除再乘，等价于 round-half-up，且不依赖浮点。
            ((raw + half) / stepMinutes) * stepMinutes
        }
        return snapped.coerceIn(lo, hi)
    }

    /**
     * 拖拽移动日程：吸附开始时间，结束时间跟随保持时长（§8.3「上下拖拽移动日程」）。
     */
    fun snapMove(
        start: Int,
        end: Int,
        stepMinutes: Int,
        minDurationMinutes: Int,
        maxDurationMinutes: Int,
    ): IntRange {
        val duration = (end - start).coerceAtLeast(1)
        val snappedStart = snap(start, stepMinutes)
        // 时长兜底：最短 / 最长（§8.3）
        val boundedDuration = duration
            .coerceIn(
                minDurationMinutes.coerceAtLeast(1),
                maxDurationMinutes.coerceAtLeast(1),
            )
        // 若吸附后越界，整体平移而不是钳制，保持时长不变（手感更好）
        val overflowEnd = snappedStart + boundedDuration - MinuteOfDay.END_OF_DAY_MINUTE
        val correctedStart = if (overflowEnd > 0) {
            (snappedStart - overflowEnd).coerceAtLeast(0)
        } else {
            snappedStart
        }
        return correctedStart..(correctedStart + boundedDuration)
    }

    /**
     * 拖拽上边缘：结束时间吸附，开始时间不动；不得早于 [floor]（§8.3「不得早于 00:00」）。
     */
    fun snapResizeEnd(
        start: Int,
        rawEnd: Int,
        stepMinutes: Int,
        minDurationMinutes: Int,
    ): Int {
        val snapped = snap(rawEnd, stepMinutes)
        val lowerBound = start + minDurationMinutes.coerceAtLeast(1)
        // 短于最小时长自动兜底（§8.3 / FI-007）
        return snapped.coerceIn(lowerBound, MinuteOfDay.END_OF_DAY_MINUTE)
    }

    /**
     * 拖拽下边缘：结束时间吸附并不得晚于 24:00（§8.3）。
     */
    fun snapResizeStart(
        rawStart: Int,
        end: Int,
        stepMinutes: Int,
        minDurationMinutes: Int,
    ): Int {
        val snapped = snap(rawStart, stepMinutes)
        val upperBound = end - minDurationMinutes.coerceAtLeast(1)
        return snapped.coerceIn(0, maxOf(0, upperBound))
    }

    /**
     * 空白处点击 → 新建日程的默认时间（§6.2 FI-005 / §7.2 新建默认时长 1 小时）。
     * 起点吸附到刻度，跨过 24:00 时整体回退，保证默认时长完整。
     */
    fun newEventRangeAt(
        tapMinute: Int,
        stepMinutes: Int,
        defaultDurationMinutes: Int,
    ): IntRange {
        val duration = defaultDurationMinutes.coerceAtLeast(1)
        val start = snap(tapMinute, stepMinutes)
        val overflow = start + duration - MinuteOfDay.END_OF_DAY_MINUTE
        val corrected = if (overflow > 0) (start - overflow).coerceAtLeast(0) else start
        return corrected..(corrected + duration)
    }
}
