package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTest {

    @Test
    fun `由小时与分钟构造并正确拆解`() {
        val t = MinuteOfDay.of(9, 30)
        assertEquals(570, t.minuteOfDay)
        assertEquals(9, t.hour)
        assertEquals(30, t.minute)
    }

    @Test
    fun `24 点整点表示为 1440 且 hour 为 24`() {
        val end = MinuteOfDay.END_OF_DAY
        assertEquals(1440, end.minuteOfDay)
        assertEquals(24, end.hour)
        assertEquals(0, end.minute)
        assertEquals("24:00", end.toString())
    }

    @Test
    fun `toString 输出零填充的 24 小时制文本`() {
        assertEquals("00:00", MinuteOfDay.of(0, 0).toString())
        assertEquals("09:05", MinuteOfDay.of(9, 5).toString())
        assertEquals("23:59", MinuteOfDay.of(23, 59).toString())
    }

    @Test
    fun `越界值原样保留交由 EventSanitizer 校正而不是在构造时钳制`() {
        // 刻意不钳制：否则 E4/E5 校验永远无法触发，数据异常事件也就永远不会上抛。
        // 归一化是校验层的职责（EventSanitizer），不是值类型的职责。
        assertEquals(-30, MinuteOfDay.ofMinute(-30).minuteOfDay)
        assertEquals(9999, MinuteOfDay.ofMinute(9999).minuteOfDay)
        assertEquals(-60, MinuteOfDay.of(-1, 0).minuteOfDay)
    }

    @Test
    fun `parse 接受常见写法`() {
        assertEquals(570, MinuteOfDay.parse("09:30")?.minuteOfDay)
        assertEquals(570, MinuteOfDay.parse("9:30")?.minuteOfDay)
        assertEquals(570, MinuteOfDay.parse("  09:30  ")?.minuteOfDay)
        assertEquals(1440, MinuteOfDay.parse("24:00")?.minuteOfDay)
    }

    @Test
    fun `parse 对非法格式返回 null 而不是抛异常`() {
        // Q4：任何输入下都不把异常抛给业务方
        assertNull(MinuteOfDay.parse(""))
        assertNull(MinuteOfDay.parse("0930"))
        assertNull(MinuteOfDay.parse("09:30:45"))
        assertNull(MinuteOfDay.parse("ab:cd"))
        assertNull(MinuteOfDay.parse("25:00"))
        assertNull(MinuteOfDay.parse("09:60"))
        assertNull(MinuteOfDay.parse(":30"))
    }

    @Test
    fun `首尾相接不算严格晚于`() {
        val a = MinuteOfDay.of(9, 0)
        val b = MinuteOfDay.of(10, 0)
        assertEquals(true, b.isStrictlyAfter(a))
        assertEquals(false, b.isStrictlyAfter(b))
        assertEquals(true, a.isAtOrBefore(b))
    }

    @Test
    fun `minutesUntil 不返回负值`() {
        val a = MinuteOfDay.of(9, 0)
        val b = MinuteOfDay.of(10, 0)
        assertEquals(60, a.minutesUntil(b))
        assertEquals(0, b.minutesUntil(a))
    }
}
