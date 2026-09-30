package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** PRD §9.4：三态推导与状态覆盖规则。 */
class TimeStateResolverTest {

    private fun resolve(start: Int, end: Int, now: Int, override: Boolean? = null) =
        TimeStateResolver.resolve(
            SanitizedEvent("x", MinuteOfDay.ofMinute(start), MinuteOfDay.ofMinute(end), TestEvent("x", start, end, override)),
            now,
        )

    @Test
    fun `结束时间等于当前时刻即判定为已过`() {
        // 边界用 ≤ 而非 <，这条容易写错
        assertEquals(EventState.PAST, resolve(500, 600, 600))
    }

    @Test
    fun `开始时间等于当前时刻判定为进行中`() {
        assertEquals(EventState.ONGOING, resolve(600, 700, 600))
    }

    @Test
    fun `开始时间晚于当前时刻判定为未到`() {
        assertEquals(EventState.UPCOMING, resolve(700, 800, 600))
    }

    @Test
    fun `已过覆盖强制使用已过配色`() {
        assertEquals(EventState.PAST, resolve(700, 800, 600, override = true))
    }

    @Test
    fun `未过期覆盖跳过已过判定改为进行中`() {
        // 规则 2：时间上已结束，但业务方声明未过期 → 按进行中处理，不判已过
        assertEquals(EventState.ONGOING, resolve(500, 550, 600, override = false))
    }

    @Test
    fun `未过期覆盖且时间未到仍为未到`() {
        assertEquals(EventState.UPCOMING, resolve(700, 800, 600, override = false))
    }

    @Test
    fun `不会产生既非已过又非进行中却被当作矛盾状态的组合`() {
        // 规则 3 的直接验证：对所有 (start,end,now) 组合，结果必属于三个枚举值之一且语义自洽
        for (now in 0..1440 step 7) {
            for (start in 0..1440 step 60) {
                for (end in (start + 1)..1440 step 60) {
                    val s = resolve(start, end, now)
                    when (s) {
                        EventState.PAST -> assertEquals(true, end <= now)
                        EventState.ONGOING -> assertEquals(true, start <= now && now < end)
                        EventState.UPCOMING -> assertEquals(true, now < start)
                    }
                }
            }
        }
    }
}
