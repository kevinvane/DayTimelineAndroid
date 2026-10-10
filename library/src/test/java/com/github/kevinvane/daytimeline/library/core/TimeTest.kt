package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /**
     * 普通类必须自己实现相等性——value class 时代这两件事由编译器按底层 Int 免费生成，
     * 现在换成了手写代码（见 MinuteOfDay 的 KDoc「为什么不是 value class」）。
     * 这三条用例就是防止有人改动 equals/hashCode 后静默破坏集合行为。
     */
    @Test
    fun `同一时刻的两个实例相等且哈希一致`() {
        val a = MinuteOfDay.of(9, 30)
        val b = MinuteOfDay.of(9, 30)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(MinuteOfDay.START_OF_DAY, MinuteOfDay.of(0, 0))
        assertEquals(MinuteOfDay.END_OF_DAY, MinuteOfDay.ofMinute(1440))
    }

    @Test
    fun `不同时刻不相等且不等于其它类型`() {
        val a = MinuteOfDay.of(9, 30)
        val b = MinuteOfDay.of(9, 31)
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
        // 裸分钟数（int）与文本都不是 MinuteOfDay——equals 必须自己挡住它们。
        // value class 时代这件事由编译器保证，普通类必须手写，故钉住。
        assertNotEquals(a as Any, 570)
        assertNotEquals(a as Any, "09:30")
        assertNotEquals(a as Any, null as Any?)
    }

    @Test
    fun `可作为 Set 与 Map 的键使用`() {
        // equals/hashCode 不一致时这里会静默多算一条——普通类转换最典型的连带风险
        val set = setOf(MinuteOfDay.of(9, 30), MinuteOfDay.of(9, 30), MinuteOfDay.of(9, 31))
        assertEquals(2, set.size)
        val map = hashMapOf(MinuteOfDay.of(9, 30) to "早会")
        assertEquals("早会", map[MinuteOfDay.of(9, 30)])
    }
}
